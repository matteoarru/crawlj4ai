package io.crawlj4ai.search;

import java.time.Duration;

/**
 * Configuration for {@link BraveSearchClient} authenticating via {@code X-Subscription-Token}.
 */
public record BraveSearchConfig(
        String apiKey,
        String baseUrl,
        Duration timeout,
        int maxRetries,
        long minRequestIntervalMs
) {
    public static final String DEFAULT_BASE_URL = "https://api.search.brave.com/res/v1";

    public BraveSearchConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Brave API key is missing. Pass it explicitly or set the BRAVE_API environment variable."
            );
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(15);
        }
        if (maxRetries < 0) {
            maxRetries = 2;
        }
        if (minRequestIntervalMs < 0) {
            minRequestIntervalMs = 0;
        }
    }

    public static BraveSearchConfig fromEnv() {
        String key = System.getenv("BRAVE_API");
        if (key == null || key.isBlank()) {
            key = System.getenv("BRAVE_API_KEY");
        }
        return builder().apiKey(key).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiKey = System.getenv("BRAVE_API");
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration timeout = Duration.ofSeconds(15);
        private int maxRetries = 2;
        private long minRequestIntervalMs = 250L;

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder minRequestIntervalMs(long ms) { this.minRequestIntervalMs = ms; return this; }

        public BraveSearchConfig build() {
            return new BraveSearchConfig(apiKey, baseUrl, timeout, maxRetries, minRequestIntervalMs);
        }
    }
}
