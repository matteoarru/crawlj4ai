package io.crawlj4ai.scheduler;

import io.crawlj4ai.config.BrowserConfig;
import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.core.CrawlJ4Ai;
import io.crawlj4ai.diff.SemanticDiffResult;
import io.crawlj4ai.diff.SemanticDiffResult.ChangeCategory;
import io.crawlj4ai.model.CrawlResult;
import io.crawlj4ai.model.MarkdownResult;
import io.crawlj4ai.search.BraveSearchClient;
import io.crawlj4ai.search.BraveSearchConfig;
import io.crawlj4ai.search.BraveSearchQuery;
import io.crawlj4ai.search.BraveSearchResult;
import io.crawlj4ai.search.BraveWebHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveRefreshSchedulerTest {

    private static final SchedulerPolicy ZERO_JITTER_POLICY = new SchedulerPolicy(
            Duration.ofMinutes(5),
            Duration.ofHours(24),
            Duration.ofHours(1),
            0.50,
            0.50,
            0.0
    );

    private static CrawlResult fakeResult(String url, boolean success, boolean substantive, ChangeCategory cat) {
        SemanticDiffResult diff = new SemanticDiffResult(substantive, cat, substantive ? 0.9 : 0.0, "summary", List.of());
        return new CrawlResult(
                url, success, success ? 200 : 503, "",
                new MarkdownResult("md", "md", "hash"),
                null, List.of(), List.of(), substantive, diff, false, success ? null : "HTTP 503"
        );
    }

    @Test
    @DisplayName("Tightens interval on substantive changes and backs off on cosmetic noise or failures")
    void adjustsPollingIntervalDynamicallyBasedOnSemanticDiffResults() {
        try (CrawlJ4Ai dummyCrawler = new CrawlJ4Ai(BrowserConfig.builder().build(), 1);
             AdaptiveRefreshScheduler scheduler = new AdaptiveRefreshScheduler(
                     dummyCrawler, ScheduleStore.inMemory(), ZERO_JITTER_POLICY, Duration.ofSeconds(30), null
             )) {

            String url = "https://example.com/status";
            long t0 = 1_000_000L;
            UrlScheduleState state = UrlScheduleState.initial(url, t0, ZERO_JITTER_POLICY);

            state = scheduler.computeNextState(
                    state,
                    fakeResult(url, true, true, ChangeCategory.INITIAL_CAPTURE),
                    t0 + 1_000L,
                    ZERO_JITTER_POLICY
            );
            assertEquals(Duration.ofHours(1).toMillis(), state.currentIntervalMs());

            long tenMinutesMs = Duration.ofMinutes(10).toMillis();
            state = scheduler.computeNextState(
                    state,
                    fakeResult(url, true, true, ChangeCategory.CONTENT_MODIFIED),
                    state.lastCrawledAtEpochMs() + tenMinutesMs,
                    ZERO_JITTER_POLICY
            );
            long tightenedInterval = state.currentIntervalMs();
            assertTrue(tightenedInterval < Duration.ofHours(1).toMillis());
            assertEquals(1L, state.substantiveHits());

            for (int i = 0; i < 4; i++) {
                state = scheduler.computeNextState(
                        state,
                        fakeResult(url, true, false, ChangeCategory.COSMETIC_OR_NOISE),
                        state.lastCrawledAtEpochMs() + state.currentIntervalMs(),
                        ZERO_JITTER_POLICY
                );
            }
            assertTrue(state.currentIntervalMs() > tightenedInterval);
            assertEquals(4, state.consecutiveNonSubstantive());

            long beforeFailure = state.currentIntervalMs();
            state = scheduler.computeNextState(
                    state,
                    fakeResult(url, false, false, null),
                    state.lastCrawledAtEpochMs() + 60_000L,
                    ZERO_JITTER_POLICY
            );
            assertEquals((long) (beforeFailure * 1.5), state.currentIntervalMs());
            assertEquals(1L, state.substantiveHits());
        }
    }

    @Test
    @DisplayName("Executes BraveSearchQuery monitor, auto-registers filtered URLs, and triggers baseline crawls")
    void searchMonitorDiscoversRegistersAndCrawlsNewUrls(@TempDir Path tempDir) throws Exception {
        AtomicBoolean emitSecondBatch = new AtomicBoolean(false);
        List<String> crawledUrls = new CopyOnWriteArrayList<>();
        List<DiscoveredUrlEvent> discoveries = new CopyOnWriteArrayList<>();
        List<CrawlResult> alerts = new CopyOnWriteArrayList<>();

        BraveSearchClient stubBrave = new BraveSearchClient(new BraveSearchConfig("test-key", null, null, 0, 0)) {
            @Override
            public BraveSearchResult searchWeb(BraveSearchQuery query) {
                List<BraveWebHit> hits = new ArrayList<>();
                hits.add(new BraveWebHit(1, "Doc A", "https://docs.example.com/a#frag", "desc", "1d", List.of()));
                hits.add(new BraveWebHit(2, "Blocked Video", "https://youtube.com/watch?v=1", "desc", "1d", List.of()));
                if (emitSecondBatch.get()) {
                    hits.add(new BraveWebHit(3, "Doc B", "https://docs.example.com/b", "desc", "1h", List.of()));
                }
                return new BraveSearchResult(query.query(), false, hits, "99", "1");
            }
        };

        CrawlJ4Ai stubCrawler = new CrawlJ4Ai(BrowserConfig.builder().build(), 1) {
            @Override
            public CrawlResult crawl(String url, CrawlerRunConfig runConfig) {
                crawledUrls.add(url);
                return fakeResult(url, true, true, ChangeCategory.INITIAL_CAPTURE);
            }
        };

        ScheduleStore scheduleStore = new FileScheduleStore(tempDir.resolve("schedules.json"));
        SearchMonitorStore monitorStore = new FileSearchMonitorStore(tempDir.resolve("monitors.json"));

        try (stubCrawler;
             AdaptiveRefreshScheduler scheduler = new AdaptiveRefreshScheduler(
                     stubCrawler,
                     scheduleStore,
                     ZERO_JITTER_POLICY,
                     Duration.ofMillis(500),
                     alerts::add,
                     stubBrave,
                     monitorStore,
                     discoveries::add
             )) {

            SearchMonitorSpec spec = SearchMonitorSpec.builder(
                            "docs-watch",
                            BraveSearchQuery.of("example docs", 5)
                    )
                    .llmProfile("qwen-local")
                    .hitFilter(hit -> !hit.url().contains("youtube.com"))
                    .build();

            scheduler.registerSearchMonitor(spec);
            scheduler.triggerSearchNow("docs-watch");

            Thread.sleep(250);

            assertEquals(1, discoveries.size());
            assertEquals("https://docs.example.com/a", discoveries.getFirst().hit().url().split("#")[0]);
            assertTrue(crawledUrls.contains("https://docs.example.com/a"));
            assertEquals(1, alerts.size());

            emitSecondBatch.set(true);
            scheduler.triggerSearchNow("docs-watch");
            Thread.sleep(250);

            SearchMonitorState monitorState = scheduler.getSearchMonitorState("docs-watch").orElseThrow();
            assertEquals(
                    List.of("https://docs.example.com/a", "https://docs.example.com/b"),
                    monitorState.knownUrls()
            );
            assertEquals("qwen-local", monitorState.llmProfile());
            assertEquals(2, discoveries.size());
        }
    }
}
