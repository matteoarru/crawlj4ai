package io.crawlj4ai.scheduler;

import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.search.BraveSearchQuery;
import io.crawlj4ai.search.BraveWebHit;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Specification for a periodic {@link BraveSearchQuery} discovery monitor with per-monitor LLM routing.
 */
public record SearchMonitorSpec(
        String monitorId,
        BraveSearchQuery query,
        CrawlerRunConfig targetRunConfig,
        SchedulerPolicy discoveryPolicy,
        SchedulerPolicy targetUrlPolicy,
        int maxTrackedUrls,
        Predicate<BraveWebHit> hitFilter,
        Optional<String> llmProfile,
        Optional<String> monitoringFocus
) {
    public SearchMonitorSpec {
        if (monitorId == null || monitorId.isBlank()) {
            throw new IllegalArgumentException("monitorId must not be blank");
        }
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (targetRunConfig == null) {
            targetRunConfig = CrawlerRunConfig.builder().build();
        }
        if (discoveryPolicy == null) {
            discoveryPolicy = defaultDiscoveryPolicy();
        }
        if (targetUrlPolicy == null) {
            targetUrlPolicy = SchedulerPolicy.defaults();
        }
        if (maxTrackedUrls <= 0) {
            maxTrackedUrls = 100;
        }
        if (hitFilter == null) {
            hitFilter = hit -> true;
        }
        if (llmProfile == null) {
            llmProfile = Optional.empty();
        }
        if (monitoringFocus == null) {
            monitoringFocus = Optional.empty();
        }
    }

    public SearchMonitorSpec(
            String monitorId,
            BraveSearchQuery query,
            CrawlerRunConfig targetRunConfig,
            SchedulerPolicy discoveryPolicy,
            SchedulerPolicy targetUrlPolicy,
            int maxTrackedUrls,
            Predicate<BraveWebHit> hitFilter
    ) {
        this(
                monitorId,
                query,
                targetRunConfig,
                discoveryPolicy,
                targetUrlPolicy,
                maxTrackedUrls,
                hitFilter,
                targetRunConfig != null ? targetRunConfig.llmProfile() : Optional.empty(),
                targetRunConfig != null ? targetRunConfig.monitoringFocus() : Optional.empty()
        );
    }

    public CrawlerRunConfig effectiveTargetRunConfig() {
        return targetRunConfig.withLlmRouting(llmProfile, monitoringFocus);
    }

    public static SchedulerPolicy defaultDiscoveryPolicy() {
        return new SchedulerPolicy(
                Duration.ofMinutes(15),
                Duration.ofHours(24),
                Duration.ofHours(2),
                0.35,
                0.50,
                0.10
        );
    }

    public static Builder builder(String monitorId, BraveSearchQuery query) {
        return new Builder(monitorId, query);
    }

    public static final class Builder {
        private final String monitorId;
        private final BraveSearchQuery query;
        private CrawlerRunConfig targetRunConfig = CrawlerRunConfig.builder().build();
        private SchedulerPolicy discoveryPolicy = defaultDiscoveryPolicy();
        private SchedulerPolicy targetUrlPolicy = SchedulerPolicy.defaults();
        private int maxTrackedUrls = 100;
        private Predicate<BraveWebHit> hitFilter = hit -> true;
        private String llmProfile = null;
        private String monitoringFocus = null;

        private Builder(String monitorId, BraveSearchQuery query) {
            this.monitorId = monitorId;
            this.query = query;
        }

        public Builder targetRunConfig(CrawlerRunConfig config) { this.targetRunConfig = config; return this; }
        public Builder discoveryPolicy(SchedulerPolicy policy) { this.discoveryPolicy = policy; return this; }
        public Builder targetUrlPolicy(SchedulerPolicy policy) { this.targetUrlPolicy = policy; return this; }
        public Builder maxTrackedUrls(int maxTrackedUrls) { this.maxTrackedUrls = maxTrackedUrls; return this; }
        public Builder hitFilter(Predicate<BraveWebHit> filter) { this.hitFilter = filter; return this; }
        public Builder llmProfile(String llmProfile) { this.llmProfile = llmProfile; return this; }
        public Builder monitoringFocus(String monitoringFocus) { this.monitoringFocus = monitoringFocus; return this; }

        public SearchMonitorSpec build() {
            return new SearchMonitorSpec(
                    monitorId,
                    query,
                    targetRunConfig,
                    discoveryPolicy,
                    targetUrlPolicy,
                    maxTrackedUrls,
                    hitFilter,
                    normalize(llmProfile),
                    normalize(monitoringFocus)
            );
        }

        private static Optional<String> normalize(String value) {
            return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.trim());
        }
    }
}
