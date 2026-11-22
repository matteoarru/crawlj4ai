package io.crawlj4ai.http;

/**
 * Decision returned by {@link ConditionalHttpPreFilter} indicating whether Chromium can be skipped.
 */
public record ConditionalCheckResult(
        Decision decision,
        int httpStatus,
        String observedETag,
        String observedLastModified,
        String reason
) {
    public enum Decision {
        NOT_MODIFIED,
        NEEDS_FULL_CRAWL
    }

    public boolean canSkipBrowser() {
        return decision == Decision.NOT_MODIFIED;
    }

    public static ConditionalCheckResult notModified(int status, String eTag, String lastModified, String reason) {
        return new ConditionalCheckResult(Decision.NOT_MODIFIED, status, eTag, lastModified, reason);
    }

    public static ConditionalCheckResult needsFullCrawl(int status, String eTag, String lastModified, String reason) {
        return new ConditionalCheckResult(Decision.NEEDS_FULL_CRAWL, status, eTag, lastModified, reason);
    }
}
