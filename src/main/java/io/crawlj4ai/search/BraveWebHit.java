package io.crawlj4ai.search;

import java.util.List;

/**
 * Single ranked web result returned by Brave Web Search.
 */
public record BraveWebHit(
        int rank,
        String title,
        String url,
        String description,
        String pageAge,
        List<String> extraSnippets
) {}
