package io.crawlj4ai.core;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import io.crawlj4ai.config.BrowserConfig;
import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.diff.PageSnapshot;
import io.crawlj4ai.diff.SemanticDiffDetector;
import io.crawlj4ai.diff.SemanticDiffResult;
import io.crawlj4ai.diff.SnapshotStore;
import io.crawlj4ai.foraging.AdaptiveForager;
import io.crawlj4ai.http.ConditionalCheckResult;
import io.crawlj4ai.http.ConditionalHttpPreFilter;
import io.crawlj4ai.markdown.DefaultMarkdownGenerator;
import io.crawlj4ai.model.CrawlResult;
import io.crawlj4ai.model.MarkdownResult;
import io.crawlj4ai.search.BraveSearchClient;
import io.crawlj4ai.search.BraveSearchQuery;
import io.crawlj4ai.search.BraveSearchResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread-safe headless browser crawler and extraction orchestrator backed by Playwright for Java.
 *
 * <p>Because Playwright {@code Browser} and {@code Page} instances are thread-confined,
 * {@code CrawlJ4Ai} binds each worker thread in its bounded pool to an isolated Chromium session
 * while executing {@link ConditionalHttpPreFilter} checks <em>before</em> browser initialization
 * so unchanged resources incur zero browser overhead.</p>
 *
 * <p>Implements {@link AutoCloseable} to shut down worker executors and close all thread-local
 * Chromium processes cleanly.</p>
 *
 * @see CrawlerRunConfig
 * @see CrawlResult
 */
public class CrawlJ4Ai implements AutoCloseable {

    private final BrowserConfig browserConfig;
    private final DefaultMarkdownGenerator markdownGenerator = new DefaultMarkdownGenerator();
    private final Map<String, String> knownContentHashes = new ConcurrentHashMap<>();
    private final Queue<WorkerSession> activeSessions = new ConcurrentLinkedQueue<>();
    private final ExecutorService workerPool;

    private final SemanticDiffDetector semanticDiffDetector;
    private final ConditionalHttpPreFilter conditionalPreFilter;
    private final SnapshotStore snapshotStore;
    private final BraveSearchClient braveSearchClient;

    private final ThreadLocal<WorkerSession> threadSession = ThreadLocal.withInitial(() -> {
        Playwright pw = Playwright.create();
        Browser browser = pw.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(browserConfig.headless())
        );
        WorkerSession session = new WorkerSession(pw, browser);
        activeSessions.add(session);
        return session;
    });

    private record WorkerSession(Playwright playwright, Browser browser) {}

    public CrawlJ4Ai(BrowserConfig browserConfig, int maxConcurrency) {
        this(browserConfig, maxConcurrency, null, null, null, null);
    }

    public CrawlJ4Ai(BrowserConfig browserConfig, int maxConcurrency, SemanticDiffDetector semanticDiffDetector) {
        this(browserConfig, maxConcurrency, semanticDiffDetector, null, null, null);
    }

    public CrawlJ4Ai(
            BrowserConfig browserConfig,
            int maxConcurrency,
            SemanticDiffDetector semanticDiffDetector,
            ConditionalHttpPreFilter conditionalPreFilter,
            SnapshotStore snapshotStore
    ) {
        this(browserConfig, maxConcurrency, semanticDiffDetector, conditionalPreFilter, snapshotStore, null);
    }

    public CrawlJ4Ai(
            BrowserConfig browserConfig,
            int maxConcurrency,
            SemanticDiffDetector semanticDiffDetector,
            ConditionalHttpPreFilter conditionalPreFilter,
            SnapshotStore snapshotStore,
            BraveSearchClient braveSearchClient
    ) {
        this.browserConfig = browserConfig;
        this.semanticDiffDetector = semanticDiffDetector;
        this.conditionalPreFilter = conditionalPreFilter;
        this.snapshotStore = snapshotStore;
        this.braveSearchClient = braveSearchClient;
        this.workerPool = Executors.newFixedThreadPool(maxConcurrency);
    }

    /**
     * Crawls a single URL, normalizes its DOM into {@code fitMarkdown}, evaluates semantic diffs,
     * and executes any configured {@link io.crawlj4ai.extraction.ExtractionStrategy}.
     *
     * @param url       absolute HTTP or HTTPS target URL
     * @param runConfig per-request pruning, caching, extraction, and LLM profile routing configuration
     * @return immutable {@link CrawlResult} containing Markdown, links, extracted JSON, and diff metadata
     */
    public CrawlResult crawl(String url, CrawlerRunConfig runConfig) {
        if (conditionalPreFilter != null && !runConfig.bypassCache()) {
            ConditionalCheckResult preCheck = conditionalPreFilter.check(url);
            if (preCheck.canSkipBrowser()) {
                MarkdownResult cachedMarkdown = restoreCachedMarkdown(url);
                SemanticDiffResult diff = semanticDiffDetector != null
                        ? SemanticDiffResult.unchanged()
                        : null;
                return new CrawlResult(
                        url,
                        true,
                        304,
                        "",
                        cachedMarkdown,
                        null,
                        List.of(),
                        List.of(),
                        false,
                        diff,
                        true,
                        null
                );
            }
        }

        WorkerSession session = threadSession.get();
        try (BrowserContext context = session.browser().newContext(
                new Browser.NewContextOptions().setUserAgent(browserConfig.userAgent())
        )) {
            Page page = context.newPage();
            page.setDefaultTimeout(browserConfig.timeoutMs());

            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            if (runConfig.waitForSelector() != null) {
                page.waitForSelector(runConfig.waitForSelector());
            }

            int status = response != null ? response.status() : 200;

            if (conditionalPreFilter != null && response != null && status >= 200 && status < 400) {
                conditionalPreFilter.recordFromResponseHeaders(url, response.allHeaders());
            }

            String rawHtml = page.content();
            var processed = markdownGenerator.process(url, rawHtml, runConfig);

            String newHash = processed.markdown().sha256Hash();
            String oldHash = knownContentHashes.put(url, newHash);
            boolean changed = (oldHash == null) || !oldHash.equals(newHash);

            SemanticDiffResult semanticDiff = null;
            if (semanticDiffDetector != null) {
                semanticDiff = semanticDiffDetector.detect(
                        url,
                        processed.markdown(),
                        runConfig.llmProfile().orElse(null),
                        runConfig.monitoringFocus().orElse(null)
                );
                changed = semanticDiff.substantive();
            }

            String extractedJson = null;
            if (runConfig.extractionStrategy().isPresent()) {
                extractedJson = runConfig.extractionStrategy().get().extract(
                        url,
                        processed.cleanedHtml(),
                        processed.markdown().fitMarkdown(),
                        runConfig.llmProfile().orElse(null)
                );
            }

            return new CrawlResult(
                    url,
                    status >= 200 && status < 400,
                    status,
                    processed.cleanedHtml(),
                    processed.markdown(),
                    extractedJson,
                    processed.internalLinks(),
                    processed.externalLinks(),
                    changed,
                    semanticDiff,
                    false,
                    null
            );
        } catch (Exception e) {
            return new CrawlResult(
                    url, false, 0, "", null, null,
                    List.of(), List.of(), false, null, false, e.getMessage()
            );
        }
    }

    private MarkdownResult restoreCachedMarkdown(String url) {
        if (snapshotStore == null) return null;
        Optional<PageSnapshot> snapshot = snapshotStore.get(url);
        return snapshot.map(s -> new MarkdownResult(s.fitMarkdown(), s.fitMarkdown(), s.sha256Hash()))
                .orElse(null);
    }

    public List<CrawlResult> crawlMany(List<String> urls, CrawlerRunConfig runConfig) {
        List<CompletableFuture<CrawlResult>> futures = urls.stream()
                .map(url -> CompletableFuture.supplyAsync(() -> crawl(url, runConfig), workerPool))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    public List<CrawlResult> searchAndCrawl(BraveSearchQuery query, CrawlerRunConfig runConfig)
            throws Exception {
        if (braveSearchClient == null) {
            throw new IllegalStateException("BraveSearchClient is not configured on this CrawlJ4Ai instance.");
        }
        BraveSearchResult searchResult = braveSearchClient.searchWeb(query);
        return crawlMany(searchResult.urls(), runConfig);
    }

    public List<CrawlResult> searchAndForage(
            BraveSearchQuery query,
            String foragingGoal,
            int maxPages,
            double minRelevance,
            CrawlerRunConfig runConfig
    ) throws Exception {
        if (braveSearchClient == null) {
            throw new IllegalStateException("BraveSearchClient is not configured on this CrawlJ4Ai instance.");
        }
        BraveSearchResult searchResult = braveSearchClient.searchWeb(query);
        if (searchResult.urls().isEmpty()) {
            return List.of();
        }
        AdaptiveForager forager = new AdaptiveForager(this, maxPages, minRelevance);
        return forager.forage(searchResult.urls().getFirst(), foragingGoal, runConfig);
    }

    @Override
    public void close() {
        workerPool.shutdown();
        while (!activeSessions.isEmpty()) {
            WorkerSession session = activeSessions.poll();
            if (session != null) {
                session.browser().close();
                session.playwright().close();
            }
        }
    }
}
