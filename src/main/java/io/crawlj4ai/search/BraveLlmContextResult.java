package io.crawlj4ai.search;

import java.util.List;

/**
 * Grounding text chunks returned by Brave's {@code /res/v1/llm/context} endpoint.
 */
public record BraveLlmContextResult(
        String query,
        List<ContextChunk> chunks
) {
    public record ContextChunk(
            String url,
            String title,
            List<String> snippets
    ) {}
}
