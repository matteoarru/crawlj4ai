package io.crawlj4ai.config;

/**
 * Immutable Playwright browser launch configuration.
 */
public record BrowserConfig(
        boolean headless,
        String userAgent,
        int timeoutMs
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean headless = true;
        private String userAgent = "CrawlJ4Ai/0.1 (+https://github.com/crawlj4ai)";
        private int timeoutMs = 30_000;

        public Builder headless(boolean headless) { this.headless = headless; return this; }
        public Builder userAgent(String userAgent) { this.userAgent = userAgent; return this; }
        public Builder timeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public BrowserConfig build() { return new BrowserConfig(headless, userAgent, timeoutMs); }
    }
}
