package io.crawlj4ai.extraction;

/**
 * Strategy interface for extracting structured JSON records from a normalized webpage.
 */
@FunctionalInterface
public interface ExtractionStrategy {
    String extract(String url, String cleanedHtml, String fitMarkdown) throws Exception;

    default String extract(
            String url,
            String cleanedHtml,
            String fitMarkdown,
            String llmProfileOverride
    ) throws Exception {
        return extract(url, cleanedHtml, fitMarkdown);
    }
}
