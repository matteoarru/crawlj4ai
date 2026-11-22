package io.crawlj4ai.scheduler;

import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.core.CrawlJ4Ai;
import io.crawlj4ai.diff.SemanticDiffResult;
import io.crawlj4ai.diff.SemanticDiffResult.ChangeCategory;
import io.crawlj4ai.model.CrawlResult;
import io.crawlj4ai.search.BraveSearchClient;
import io.crawlj4ai.search.BraveSearchResult;
import io.crawlj4ai.search.BraveWebHit;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Continuous monitoring scheduler that models both webpage updates and Brave Search URL discoveries
 * as continuous-time Poisson processes.
 *
 * <p>Using an Exponential Moving Average (EMA) of substantive change rates {@code lambda} (events/hour),
 * each target's next polling interval is computed as {@code T = -ln(1 - p*) / lambda}, clamped within
 * {@link SchedulerPolicy#minInterval()} and {@link SchedulerPolicy#maxInterval()} with random jitter.</p>
 */
public class AdaptiveRefreshScheduler implements AutoCloseable {

    private record MonitoredTarget(
            String url,
            CrawlerRunConfig runConfig,
            SchedulerPolicy policy
    ) {}

    private final CrawlJ4Ai crawler;
    private final ScheduleStore scheduleStore;
    private final SchedulerPolicy defaultPolicy;
    private final Duration tickInterval;
    private final Consumer<CrawlResult> substantiveUpdateListener;

    private final BraveSearchClient braveSearchClient;
    private final SearchMonitorStore searchMonitorStore;
    private final Consumer<DiscoveredUrlEvent> newUrlDiscoveredListener;

    private final ConcurrentHashMap<String, MonitoredTarget> targets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> urlLocks = new ConcurrentHashMap<>();
    private final Set<String> inFlightUrls = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, SearchMonitorSpec> searchMonitors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> searchLocks = new ConcurrentHashMap<>();
    private final Set<String> inFlightSearches = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService timerExecutor;
    private final ExecutorService dispatchExecutor;
    private volatile boolean started = false;

    public AdaptiveRefreshScheduler(
            CrawlJ4Ai crawler,
            ScheduleStore scheduleStore,
            SchedulerPolicy defaultPolicy,
            Duration tickInterval,
            Consumer<CrawlResult> substantiveUpdateListener
    ) {
        this(
                crawler,
                scheduleStore,
                defaultPolicy,
                tickInterval,
                substantiveUpdateListener,
                null,
                SearchMonitorStore.inMemory(),
                null
        );
    }

    public AdaptiveRefreshScheduler(
            CrawlJ4Ai crawler,
            ScheduleStore scheduleStore,
            Consumer<CrawlResult> substantiveUpdateListener
    ) {
        this(crawler, scheduleStore, SchedulerPolicy.defaults(), Duration.ofSeconds(15), substantiveUpdateListener);
    }

    public AdaptiveRefreshScheduler(
            CrawlJ4Ai crawler,
            ScheduleStore scheduleStore,
            SchedulerPolicy defaultPolicy,
            Duration tickInterval,
            Consumer<CrawlResult> substantiveUpdateListener,
            BraveSearchClient braveSearchClient,
            SearchMonitorStore searchMonitorStore,
            Consumer<DiscoveredUrlEvent> newUrlDiscoveredListener
    ) {
        this.crawler = crawler;
        this.scheduleStore = scheduleStore;
        this.defaultPolicy = defaultPolicy;
        this.tickInterval = tickInterval;
        this.substantiveUpdateListener = substantiveUpdateListener != null
                ? substantiveUpdateListener
                : result -> {};

        this.braveSearchClient = braveSearchClient;
        this.searchMonitorStore = searchMonitorStore != null
                ? searchMonitorStore
                : SearchMonitorStore.inMemory();
        this.newUrlDiscoveredListener = newUrlDiscoveredListener != null
                ? newUrlDiscoveredListener
                : event -> {};

        this.timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crawlj4ai-scheduler-timer");
            t.setDaemon(true);
            return t;
        });
        this.dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void register(String url, CrawlerRunConfig runConfig) {
        register(url, runConfig, defaultPolicy);
    }

    public void register(String url, CrawlerRunConfig runConfig, SchedulerPolicy policy) {
        String normalized = normalizeUrl(url);
        targets.put(normalized, new MonitoredTarget(normalized, runConfig, policy));
        ReentrantLock lock = urlLocks.computeIfAbsent(normalized, k -> new ReentrantLock());
        lock.lock();
        try {
            if (scheduleStore.get(normalized).isEmpty()) {
                scheduleStore.put(UrlScheduleState.initial(normalized, Instant.now().toEpochMilli(), policy));
            }
        } finally {
            lock.unlock();
        }
    }

    public void unregister(String url) {
        String normalized = normalizeUrl(url);
        targets.remove(normalized);
        scheduleStore.remove(normalized);
    }

    public void triggerNow(String url) {
        String normalized = normalizeUrl(url);
        if (!targets.containsKey(normalized)) {
            throw new IllegalArgumentException("URL is not registered: " + normalized);
        }
        ReentrantLock lock = urlLocks.computeIfAbsent(normalized, k -> new ReentrantLock());
        lock.lock();
        try {
            long now = Instant.now().toEpochMilli();
            UrlScheduleState current = scheduleStore.get(normalized)
                    .orElseGet(() -> UrlScheduleState.initial(normalized, now, targets.get(normalized).policy()));
            scheduleStore.put(new UrlScheduleState(
                    current.url(),
                    now,
                    current.lastCrawledAtEpochMs(),
                    current.currentIntervalMs(),
                    current.estimatedChangesPerHour(),
                    current.emaSubstantiveScore(),
                    current.totalChecks(),
                    current.substantiveHits(),
                    current.consecutiveNonSubstantive(),
                    current.lastCategory()
            ));
        } finally {
            lock.unlock();
        }
        dispatchDueTargets();
    }

    public void registerSearchMonitor(SearchMonitorSpec spec) {
        if (braveSearchClient == null) {
            throw new IllegalStateException(
                    "BraveSearchClient must be provided to AdaptiveRefreshScheduler to register search monitors."
            );
        }
        searchMonitors.put(spec.monitorId(), spec);
        CrawlerRunConfig effectiveRunConfig = spec.effectiveTargetRunConfig();
        String profileLabel = spec.llmProfile().orElse("default");

        ReentrantLock lock = searchLocks.computeIfAbsent(spec.monitorId(), k -> new ReentrantLock());
        lock.lock();
        try {
            Optional<SearchMonitorState> existingOpt = searchMonitorStore.get(spec.monitorId());
            if (existingOpt.isEmpty()) {
                searchMonitorStore.put(SearchMonitorState.initial(
                        spec.monitorId(),
                        spec.query().query(),
                        profileLabel,
                        Instant.now().toEpochMilli(),
                        spec.discoveryPolicy()
                ));
            } else {
                SearchMonitorState existing = existingOpt.get();
                searchMonitorStore.put(new SearchMonitorState(
                        existing.monitorId(),
                        existing.queryText(),
                        profileLabel,
                        existing.nextSearchAtEpochMs(),
                        existing.lastSearchedAtEpochMs(),
                        existing.currentIntervalMs(),
                        existing.estimatedDiscoveriesPerHour(),
                        existing.totalSearches(),
                        existing.totalUrlsDiscovered(),
                        existing.knownUrls()
                ));
                for (String knownUrl : existing.knownUrls()) {
                    register(knownUrl, effectiveRunConfig, spec.targetUrlPolicy());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void unregisterSearchMonitor(String monitorId, boolean unregisterDiscoveredUrls) {
        searchMonitors.remove(monitorId);
        Optional<SearchMonitorState> stateOpt = searchMonitorStore.get(monitorId);
        searchMonitorStore.remove(monitorId);

        if (unregisterDiscoveredUrls && stateOpt.isPresent()) {
            for (String url : stateOpt.get().knownUrls()) {
                unregister(url);
            }
        }
    }

    public void triggerSearchNow(String monitorId) {
        SearchMonitorSpec spec = searchMonitors.get(monitorId);
        if (spec == null) {
            throw new IllegalArgumentException("Search monitor is not registered: " + monitorId);
        }
        ReentrantLock lock = searchLocks.computeIfAbsent(monitorId, k -> new ReentrantLock());
        lock.lock();
        try {
            long now = Instant.now().toEpochMilli();
            SearchMonitorState current = searchMonitorStore.get(monitorId)
                    .orElseGet(() -> SearchMonitorState.initial(
                            monitorId, spec.query().query(), spec.llmProfile().orElse("default"), now, spec.discoveryPolicy()
                    ));
            searchMonitorStore.put(new SearchMonitorState(
                    current.monitorId(),
                    current.queryText(),
                    current.llmProfile(),
                    now,
                    current.lastSearchedAtEpochMs(),
                    current.currentIntervalMs(),
                    current.estimatedDiscoveriesPerHour(),
                    current.totalSearches(),
                    current.totalUrlsDiscovered(),
                    current.knownUrls()
            ));
        } finally {
            lock.unlock();
        }
        dispatchDueTargets();
    }

    public Optional<SearchMonitorState> getSearchMonitorState(String monitorId) {
        return searchMonitorStore.get(monitorId);
    }

    public Collection<SearchMonitorState> getAllSearchMonitors() {
        return searchMonitorStore.findAll();
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        long periodMs = Math.max(500L, tickInterval.toMillis());
        timerExecutor.scheduleWithFixedDelay(this::dispatchDueTargets, 0L, periodMs, TimeUnit.MILLISECONDS);
    }

    public Optional<UrlScheduleState> getScheduleState(String url) {
        return scheduleStore.get(normalizeUrl(url));
    }

    public Collection<UrlScheduleState> getAllSchedules() {
        return scheduleStore.findAll();
    }

    private void dispatchDueTargets() {
        long now = Instant.now().toEpochMilli();

        for (SearchMonitorSpec spec : searchMonitors.values()) {
            SearchMonitorState state = searchMonitorStore.get(spec.monitorId()).orElse(null);
            if (state == null || now < state.nextSearchAtEpochMs()) {
                continue;
            }
            if (!inFlightSearches.add(spec.monitorId())) {
                continue;
            }
            dispatchExecutor.submit(() -> executeSearchDiscovery(spec));
        }

        for (MonitoredTarget target : targets.values()) {
            UrlScheduleState state = scheduleStore.get(target.url()).orElse(null);
            if (state == null || now < state.nextCrawlAtEpochMs()) {
                continue;
            }
            if (!inFlightUrls.add(target.url())) {
                continue;
            }
            dispatchExecutor.submit(() -> executeAndReschedule(target));
        }
    }

    private void executeSearchDiscovery(SearchMonitorSpec spec) {
        try {
            BraveSearchResult searchResult = braveSearchClient.searchWeb(spec.query());
            long completedAt = Instant.now().toEpochMilli();

            List<BraveWebHit> filteredHits = searchResult.webHits().stream()
                    .filter(spec.hitFilter())
                    .toList();

            List<DiscoveredUrlEvent> newlyDiscoveredEvents = new ArrayList<>();

            ReentrantLock lock = searchLocks.computeIfAbsent(spec.monitorId(), k -> new ReentrantLock());
            lock.lock();
            try {
                SearchMonitorState prev = searchMonitorStore.get(spec.monitorId())
                        .orElseGet(() -> SearchMonitorState.initial(
                                spec.monitorId(), spec.query().query(), spec.llmProfile().orElse("default"),
                                completedAt, spec.discoveryPolicy()
                        ));

                Set<String> knownSet = new LinkedHashSet<>(prev.knownUrls());

                for (BraveWebHit hit : filteredHits) {
                    if (knownSet.size() >= spec.maxTrackedUrls()) {
                        break;
                    }
                    String normalizedUrl = normalizeUrl(hit.url());
                    if (normalizedUrl == null) continue;

                    if (knownSet.add(normalizedUrl)) {
                        register(normalizedUrl, spec.effectiveTargetRunConfig(), spec.targetUrlPolicy());
                        newlyDiscoveredEvents.add(new DiscoveredUrlEvent(
                                spec.monitorId(),
                                spec.query().query(),
                                hit,
                                completedAt
                        ));
                    }
                }

                SearchMonitorState updated = computeNextSearchState(
                        prev,
                        filteredHits.size(),
                        newlyDiscoveredEvents.size(),
                        List.copyOf(knownSet),
                        completedAt,
                        spec.discoveryPolicy()
                );
                searchMonitorStore.put(updated);
            } finally {
                lock.unlock();
            }

            for (DiscoveredUrlEvent event : newlyDiscoveredEvents) {
                newUrlDiscoveredListener.accept(event);
            }

            if (!newlyDiscoveredEvents.isEmpty()) {
                dispatchDueTargets();
            }
        } catch (Exception e) {
            long now = Instant.now().toEpochMilli();
            ReentrantLock lock = searchLocks.computeIfAbsent(spec.monitorId(), k -> new ReentrantLock());
            lock.lock();
            try {
                SearchMonitorState prev = searchMonitorStore.get(spec.monitorId()).orElse(null);
                if (prev != null) {
                    SchedulerPolicy policy = spec.discoveryPolicy();
                    long backoffMs = clamp(
                            (long) (prev.currentIntervalMs() * 1.5),
                            policy.minInterval().toMillis(),
                            policy.maxInterval().toMillis()
                    );
                    searchMonitorStore.put(new SearchMonitorState(
                            prev.monitorId(),
                            prev.queryText(),
                            prev.llmProfile(),
                            now + applyJitter(backoffMs, policy.jitterFactor()),
                            now,
                            backoffMs,
                            prev.estimatedDiscoveriesPerHour(),
                            prev.totalSearches() + 1,
                            prev.totalUrlsDiscovered(),
                            prev.knownUrls()
                    ));
                }
            } finally {
                lock.unlock();
            }
        } finally {
            inFlightSearches.remove(spec.monitorId());
        }
    }

    private void executeAndReschedule(MonitoredTarget target) {
        try {
            CrawlResult result = crawler.crawl(target.url(), target.runConfig());
            long completedAt = Instant.now().toEpochMilli();

            ReentrantLock lock = urlLocks.computeIfAbsent(target.url(), k -> new ReentrantLock());
            lock.lock();
            try {
                UrlScheduleState prev = scheduleStore.get(target.url())
                        .orElseGet(() -> UrlScheduleState.initial(target.url(), completedAt, target.policy()));
                UrlScheduleState updated = computeNextState(prev, result, completedAt, target.policy());
                scheduleStore.put(updated);
            } finally {
                lock.unlock();
            }

            if (result.success() && result.changedFromPrevious()) {
                substantiveUpdateListener.accept(result);
            }
        } finally {
            inFlightUrls.remove(target.url());
        }
    }

    SearchMonitorState computeNextSearchState(
            SearchMonitorState prev,
            int validHitsCount,
            int newUrlsCount,
            List<String> updatedKnownUrls,
            long nowEpochMs,
            SchedulerPolicy policy
    ) {
        long minMs = policy.minInterval().toMillis();
        long maxMs = policy.maxInterval().toMillis();

        if (prev.totalSearches() == 0) {
            long initialMs = clamp(policy.initialInterval().toMillis(), minMs, maxMs);
            return new SearchMonitorState(
                    prev.monitorId(),
                    prev.queryText(),
                    prev.llmProfile(),
                    nowEpochMs + applyJitter(initialMs, policy.jitterFactor()),
                    nowEpochMs,
                    initialMs,
                    prev.estimatedDiscoveriesPerHour(),
                    1L,
                    newUrlsCount,
                    updatedKnownUrls
            );
        }

        double noveltyWeight = (validHitsCount > 0 && newUrlsCount > 0)
                ? Math.min(1.0, (double) newUrlsCount / validHitsCount)
                : 0.0;

        long elapsedMs = prev.lastSearchedAtEpochMs() > 0
                ? Math.max(nowEpochMs - prev.lastSearchedAtEpochMs(), minMs)
                : prev.currentIntervalMs();
        double elapsedHours = Math.max(elapsedMs / 3_600_000.0, 1e-4);

        double observedRatePerHour = noveltyWeight / elapsedHours;
        double alpha = policy.emaAlpha();
        double updatedRatePerHour = (alpha * observedRatePerHour)
                + ((1.0 - alpha) * prev.estimatedDiscoveriesPerHour());

        double minRatePerHour = -Math.log(1.0 - policy.targetChangeProbability()) / (maxMs / 3_600_000.0);
        updatedRatePerHour = Math.max(updatedRatePerHour, minRatePerHour);

        double targetHours = -Math.log(1.0 - policy.targetChangeProbability()) / updatedRatePerHour;
        long boundedIntervalMs = clamp((long) (targetHours * 3_600_000.0), minMs, maxMs);

        return new SearchMonitorState(
                prev.monitorId(),
                prev.queryText(),
                prev.llmProfile(),
                nowEpochMs + applyJitter(boundedIntervalMs, policy.jitterFactor()),
                nowEpochMs,
                boundedIntervalMs,
                updatedRatePerHour,
                prev.totalSearches() + 1,
                prev.totalUrlsDiscovered() + newUrlsCount,
                updatedKnownUrls
        );
    }

    UrlScheduleState computeNextState(
            UrlScheduleState prev,
            CrawlResult result,
            long nowEpochMs,
            SchedulerPolicy policy
    ) {
        long minMs = policy.minInterval().toMillis();
        long maxMs = policy.maxInterval().toMillis();

        if (!result.success()) {
            long backoffMs = clamp((long) (prev.currentIntervalMs() * 1.5), minMs, maxMs);
            return new UrlScheduleState(
                    prev.url(),
                    nowEpochMs + applyJitter(backoffMs, policy.jitterFactor()),
                    nowEpochMs,
                    backoffMs,
                    prev.estimatedChangesPerHour(),
                    prev.emaSubstantiveScore(),
                    prev.totalChecks() + 1,
                    prev.substantiveHits(),
                    prev.consecutiveNonSubstantive(),
                    prev.lastCategory()
            );
        }

        SemanticDiffResult diff = result.semanticDiff();
        ChangeCategory category = diff != null ? diff.category() : null;

        if (category == ChangeCategory.INITIAL_CAPTURE) {
            long initialMs = clamp(policy.initialInterval().toMillis(), minMs, maxMs);
            return new UrlScheduleState(
                    prev.url(),
                    nowEpochMs + applyJitter(initialMs, policy.jitterFactor()),
                    nowEpochMs,
                    initialMs,
                    prev.estimatedChangesPerHour(),
                    prev.emaSubstantiveScore(),
                    prev.totalChecks() + 1,
                    prev.substantiveHits(),
                    0,
                    category
            );
        }

        boolean isSubstantive = diff != null ? diff.substantive() : result.changedFromPrevious();
        double observationWeight = 0.0;
        if (isSubstantive) {
            double sig = diff != null ? diff.significanceScore() : 1.0;
            observationWeight = Math.max(0.50, Math.min(1.0, sig));
        }

        long elapsedMs = prev.lastCrawledAtEpochMs() > 0
                ? Math.max(nowEpochMs - prev.lastCrawledAtEpochMs(), minMs)
                : prev.currentIntervalMs();
        double elapsedHours = Math.max(elapsedMs / 3_600_000.0, 1e-4);

        double observedRatePerHour = observationWeight / elapsedHours;
        double alpha = policy.emaAlpha();
        double updatedRatePerHour = (alpha * observedRatePerHour)
                + ((1.0 - alpha) * prev.estimatedChangesPerHour());

        double minRatePerHour = -Math.log(1.0 - policy.targetChangeProbability()) / (maxMs / 3_600_000.0);
        updatedRatePerHour = Math.max(updatedRatePerHour, minRatePerHour);

        double targetHours = -Math.log(1.0 - policy.targetChangeProbability()) / updatedRatePerHour;
        long rawNextIntervalMs = (long) (targetHours * 3_600_000.0);
        long boundedIntervalMs = clamp(rawNextIntervalMs, minMs, maxMs);

        double updatedEmaScore = (alpha * observationWeight) + ((1.0 - alpha) * prev.emaSubstantiveScore());
        long nextHits = isSubstantive ? prev.substantiveHits() + 1 : prev.substantiveHits();
        int nextConsecutiveNonSub = isSubstantive ? 0 : prev.consecutiveNonSubstantive() + 1;

        return new UrlScheduleState(
                prev.url(),
                nowEpochMs + applyJitter(boundedIntervalMs, policy.jitterFactor()),
                nowEpochMs,
                boundedIntervalMs,
                updatedRatePerHour,
                updatedEmaScore,
                prev.totalChecks() + 1,
                nextHits,
                nextConsecutiveNonSub,
                category
        );
    }

    private static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        String withoutFragment = rawUrl.trim().split("#")[0];
        try {
            URI uri = URI.create(withoutFragment);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static long applyJitter(long intervalMs, double jitterFactor) {
        if (jitterFactor <= 0.0) return intervalMs;
        double delta = ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
        return Math.max(1_000L, (long) (intervalMs * (1.0 + delta)));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        timerExecutor.shutdown();
        dispatchExecutor.shutdown();
        try {
            if (!timerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                timerExecutor.shutdownNow();
            }
            if (!dispatchExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                dispatchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timerExecutor.shutdownNow();
            dispatchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
