package io.crawlj4ai.search;

import java.util.List;

/**
 * Aggregated web search results and rate-limit header metadata from Brave Search API.
 */
public record BraveSearchResult(
        String originalQuery,
        boolean moreResultsAvailable,
        List<BraveWebHit> webHits,
        String rateLimitRemaining,
        String rateLimitResetSeconds
) {
    public List<String> urls() {
        return webHits.stream().map(BraveWebHit::url).toList();
    }
}
