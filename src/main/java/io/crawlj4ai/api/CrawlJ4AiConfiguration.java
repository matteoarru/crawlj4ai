package io.crawlj4ai.api;

import io.crawlj4ai.config.BrowserConfig;
import io.crawlj4ai.core.CrawlJ4Ai;
import io.crawlj4ai.diff.FileSnapshotStore;
import io.crawlj4ai.diff.SemanticDiffDetector;
import io.crawlj4ai.diff.SnapshotStore;
import io.crawlj4ai.extraction.FileSelectorCache;
import io.crawlj4ai.extraction.SelectorCache;
import io.crawlj4ai.http.ConditionalHttpPreFilter;
import io.crawlj4ai.http.FileHttpValidatorStore;
import io.crawlj4ai.http.HttpValidatorStore;
import io.crawlj4ai.llm.ChatModelRegistry;
import io.crawlj4ai.llm.LlmProfileConfig;
import io.crawlj4ai.scheduler.AdaptiveRefreshScheduler;
import io.crawlj4ai.scheduler.FileScheduleStore;
import io.crawlj4ai.scheduler.FileSearchMonitorStore;
import io.crawlj4ai.scheduler.ScheduleStore;
import io.crawlj4ai.scheduler.SchedulerPolicy;
import io.crawlj4ai.scheduler.SearchMonitorStore;
import io.crawlj4ai.search.BraveSearchClient;
import io.crawlj4ai.search.BraveSearchConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class CrawlJ4AiConfiguration {

    @Bean
    public BrowserConfig browserConfig(CrawlJ4AiProperties props) {
        return BrowserConfig.builder()
                .headless(props.headless())
                .build();
    }

    @Bean
    public HttpValidatorStore httpValidatorStore(CrawlJ4AiProperties props) {
        return new FileHttpValidatorStore(Path.of(props.dataDir(), "http-validators.json"));
    }

    @Bean
    public SnapshotStore snapshotStore(CrawlJ4AiProperties props) {
        return new FileSnapshotStore(Path.of(props.dataDir(), "snapshots.json"));
    }

    @Bean
    public SelectorCache selectorCache(CrawlJ4AiProperties props) {
        return new FileSelectorCache(Path.of(props.dataDir(), "selectors-cache.json"));
    }

    @Bean
    public ScheduleStore scheduleStore(CrawlJ4AiProperties props) {
        return new FileScheduleStore(Path.of(props.dataDir(), "schedules.json"));
    }

    @Bean
    public SearchMonitorStore searchMonitorStore(CrawlJ4AiProperties props) {
        return new FileSearchMonitorStore(Path.of(props.dataDir(), "search-monitors.json"));
    }

    @Bean
    public ConditionalHttpPreFilter conditionalHttpPreFilter(
            HttpValidatorStore validatorStore,
            BrowserConfig browserConfig
    ) {
        return new ConditionalHttpPreFilter(validatorStore, browserConfig.userAgent());
    }

    @Bean
    public ChatModelRegistry chatModelRegistry(CrawlJ4AiProperties props) {
        Map<String, LlmProfileConfig> mapped = new LinkedHashMap<>();
        String defaultProfile = "default";

        if (props.llm() != null) {
            if (props.llm().defaultProfile() != null && !props.llm().defaultProfile().isBlank()) {
                defaultProfile = props.llm().defaultProfile();
            }
            if (props.llm().profiles() != null) {
                props.llm().profiles().forEach((name, p) -> mapped.put(name, p.toConfig()));
            }
        }
        return new ChatModelRegistry(defaultProfile, mapped);
    }

    @Bean
    @Nullable
    public BraveSearchClient braveSearchClient(CrawlJ4AiProperties props) {
        if (props.braveApiKey() == null || props.braveApiKey().isBlank()) {
            return null;
        }
        return new BraveSearchClient(
                BraveSearchConfig.builder()
                        .apiKey(props.braveApiKey())
                        .build()
        );
    }

    @Bean
    public SemanticDiffDetector semanticDiffDetector(
            CrawlJ4AiProperties props,
            SnapshotStore snapshotStore,
            ChatModelRegistry chatModelRegistry
    ) {
        return new SemanticDiffDetector(snapshotStore, chatModelRegistry, props.monitoringFocus());
    }

    @Bean(destroyMethod = "close")
    public CrawlJ4Ai crawlJ4Ai(
            BrowserConfig browserConfig,
            CrawlJ4AiProperties props,
            SemanticDiffDetector semanticDiffDetector,
            ConditionalHttpPreFilter conditionalHttpPreFilter,
            SnapshotStore snapshotStore,
            @Nullable BraveSearchClient braveSearchClient
    ) {
        return new CrawlJ4Ai(
                browserConfig,
                Math.max(1, props.maxConcurrency()),
                semanticDiffDetector,
                conditionalHttpPreFilter,
                snapshotStore,
                braveSearchClient
        );
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public AdaptiveRefreshScheduler adaptiveRefreshScheduler(
            CrawlJ4Ai crawler,
            ScheduleStore scheduleStore,
            CrawlJ4AiProperties props,
            @Nullable BraveSearchClient braveSearchClient,
            SearchMonitorStore searchMonitorStore,
            ScoutAlertBroadcaster alertBroadcaster
    ) {
        return new AdaptiveRefreshScheduler(
                crawler,
                scheduleStore,
                SchedulerPolicy.defaults(),
                Duration.ofSeconds(Math.max(1L, props.schedulerTickSeconds())),
                alertBroadcaster::publishContentUpdate,
                braveSearchClient,
                searchMonitorStore,
                alertBroadcaster::publishDiscovery
        );
    }
}
