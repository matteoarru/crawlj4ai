package io.crawlj4ai.config;

import io.crawlj4ai.extraction.ExtractionStrategy;
import java.util.List;
import java.util.Optional;

/**
 * Per-request crawl, DOM pruning, extraction, and LLM profile routing configuration.
 */
public record CrawlerRunConfig(
        int wordCountThreshold,
        List<String> excludedTags,
        String cssScopeSelector,
        String waitForSelector,
        boolean bypassCache,
        Optional<ExtractionStrategy> extractionStrategy,
        Optional<String> llmProfile,
        Optional<String> monitoringFocus
) {
    public CrawlerRunConfig(
            int wordCountThreshold,
            List<String> excludedTags,
            String cssScopeSelector,
            String waitForSelector,
            boolean bypassCache,
            Optional<ExtractionStrategy> extractionStrategy
    ) {
        this(
                wordCountThreshold,
                excludedTags,
                cssScopeSelector,
                waitForSelector,
                bypassCache,
                extractionStrategy,
                Optional.empty(),
                Optional.empty()
        );
    }

    public CrawlerRunConfig withLlmRouting(Optional<String> profileOverride, Optional<String> focusOverride) {
        Optional<String> resolvedProfile = (profileOverride != null && profileOverride.isPresent())
                ? profileOverride
                : this.llmProfile;
        Optional<String> resolvedFocus = (focusOverride != null && focusOverride.isPresent())
                ? focusOverride
                : this.monitoringFocus;

        return new CrawlerRunConfig(
                wordCountThreshold,
                excludedTags,
                cssScopeSelector,
                waitForSelector,
                bypassCache,
                extractionStrategy,
                resolvedProfile,
                resolvedFocus
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int wordCountThreshold = 5;
        private List<String> excludedTags = List.of("nav", "footer", "aside", "script", "style", "noscript", "iframe");
        private String cssScopeSelector = "body";
        private String waitForSelector = null;
        private boolean bypassCache = false;
        private ExtractionStrategy extractionStrategy = null;
        private String llmProfile = null;
        private String monitoringFocus = null;

        public Builder wordCountThreshold(int threshold) { this.wordCountThreshold = threshold; return this; }
        public Builder excludedTags(List<String> tags) { this.excludedTags = tags; return this; }
        public Builder cssScopeSelector(String selector) { this.cssScopeSelector = selector; return this; }
        public Builder waitForSelector(String selector) { this.waitForSelector = selector; return this; }
        public Builder bypassCache(boolean bypassCache) { this.bypassCache = bypassCache; return this; }
        public Builder extractionStrategy(ExtractionStrategy strategy) { this.extractionStrategy = strategy; return this; }
        public Builder llmProfile(String llmProfile) { this.llmProfile = llmProfile; return this; }
        public Builder monitoringFocus(String monitoringFocus) { this.monitoringFocus = monitoringFocus; return this; }

        public CrawlerRunConfig build() {
            return new CrawlerRunConfig(
                    wordCountThreshold,
                    excludedTags,
                    cssScopeSelector,
                    waitForSelector,
                    bypassCache,
                    Optional.ofNullable(extractionStrategy),
                    normalize(llmProfile),
                    normalize(monitoringFocus)
            );
        }

        private static Optional<String> normalize(String value) {
            return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.trim());
        }
    }
}
