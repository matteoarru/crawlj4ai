package io.crawlj4ai.example;

import io.crawlj4ai.config.BrowserConfig;
import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.core.CrawlJ4Ai;
import io.crawlj4ai.diff.FileSnapshotStore;
import io.crawlj4ai.diff.SemanticDiffDetector;
import io.crawlj4ai.http.ConditionalHttpPreFilter;
import io.crawlj4ai.http.FileHttpValidatorStore;
import io.crawlj4ai.llm.ChatModelRegistry;
import io.crawlj4ai.llm.LlmProfileConfig;
import io.crawlj4ai.scheduler.AdaptiveRefreshScheduler;
import io.crawlj4ai.scheduler.FileScheduleStore;
import io.crawlj4ai.scheduler.FileSearchMonitorStore;
import io.crawlj4ai.scheduler.SchedulerPolicy;
import io.crawlj4ai.scheduler.SearchMonitorSpec;
import io.crawlj4ai.search.BraveSearchClient;
import io.crawlj4ai.search.BraveSearchQuery;
import io.crawlj4ai.search.BraveSearchQuery.Freshness;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public class SearchDiscoveryScoutExample {
    public static void main(String[] args) throws InterruptedException {
        BrowserConfig browserConfig = BrowserConfig.builder().build();
        BraveSearchClient braveClient = BraveSearchClient.fromEnv();

        var registry = new ChatModelRegistry("gemma4-local", Map.of(
                "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768),
                "qwen-local", LlmProfileConfig.ollama("http://localhost:11434", "qwen3.8:27b", 32_768)
        ));

        var validatorStore = new FileHttpValidatorStore(Path.of(".crawlj4ai/http-validators.json"));
        var snapshotStore = new FileSnapshotStore(Path.of(".crawlj4ai/snapshots.json"));
        var scheduleStore = new FileScheduleStore(Path.of(".crawlj4ai/schedules.json"));
        var searchMonitorStore = new FileSearchMonitorStore(Path.of(".crawlj4ai/search-monitors.json"));

        var preFilter = new ConditionalHttpPreFilter(validatorStore, browserConfig.userAgent());
        var diffDetector = new SemanticDiffDetector(
                snapshotStore,
                registry,
                "Track new Java/Playwright release notes and breaking API changes."
        );

        try (CrawlJ4Ai crawler = new CrawlJ4Ai(browserConfig, 3, diffDetector, preFilter, snapshotStore, braveClient);
             AdaptiveRefreshScheduler scheduler = new AdaptiveRefreshScheduler(
                     crawler,
                     scheduleStore,
                     SchedulerPolicy.defaults(),
                     Duration.ofSeconds(10),
                     update -> System.out.printf("[CONTENT UPDATE] %s -> %s%n",
                             update.url(),
                             update.semanticDiff() != null ? update.semanticDiff().summary() : "Updated"),
                     braveClient,
                     searchMonitorStore,
                     discovered -> System.out.printf("[NEW URL DISCOVERED] monitor=%s | %s%n",
                             discovered.monitorId(), discovered.hit().url())
             )) {

            BraveSearchQuery discoveryQuery = BraveSearchQuery.builder("Playwright Java release notes")
                    .count(8)
                    .freshness(Freshness.PAST_WEEK)
                    .build();

            SearchMonitorSpec monitorSpec = SearchMonitorSpec.builder("playwright-java-releases", discoveryQuery)
                    .targetRunConfig(CrawlerRunConfig.builder().build())
                    .targetUrlPolicy(SchedulerPolicy.volatileTarget())
                    .llmProfile("gemma4-local")
                    .build();

            scheduler.registerSearchMonitor(monitorSpec);
            scheduler.start();
            Thread.sleep(Duration.ofSeconds(30));
        }
    }
}
