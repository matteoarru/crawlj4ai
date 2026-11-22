package io.crawlj4ai.model;

import io.crawlj4ai.diff.SemanticDiffResult;
import java.util.List;

/**
 * Immutable output produced by a single {@link io.crawlj4ai.core.CrawlJ4Ai#crawl} execution.
 */
public record CrawlResult(
        String url,
        boolean success,
        int statusCode,
        String cleanedHtml,
        MarkdownResult markdown,
        String extractedJson,
        List<String> internalLinks,
        List<String> externalLinks,
        boolean changedFromPrevious,
        SemanticDiffResult semanticDiff,
        boolean skippedBrowserViaHttp304,
        String errorMessage
) {
    public CrawlResult(
            String url,
            boolean success,
            int statusCode,
            String cleanedHtml,
            MarkdownResult markdown,
            String extractedJson,
            List<String> internalLinks,
            List<String> externalLinks,
            boolean changedFromPrevious,
            SemanticDiffResult semanticDiff,
            String errorMessage
    ) {
        this(url, success, statusCode, cleanedHtml, markdown, extractedJson,
                internalLinks, externalLinks, changedFromPrevious, semanticDiff, false, errorMessage);
    }

    public CrawlResult(
            String url,
            boolean success,
            int statusCode,
            String cleanedHtml,
            MarkdownResult markdown,
            String extractedJson,
            List<String> internalLinks,
            List<String> externalLinks,
            boolean changedFromPrevious,
            String errorMessage
    ) {
        this(url, success, statusCode, cleanedHtml, markdown, extractedJson,
                internalLinks, externalLinks, changedFromPrevious, null, false, errorMessage);
    }
}
