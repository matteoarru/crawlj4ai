package io.crawlj4ai.model;

/**
 * Normalized Markdown representations and SHA-256 fingerprint of {@code fitMarkdown}.
 */
public record MarkdownResult(
        String rawMarkdown,
        String fitMarkdown,
        String sha256Hash
) {}
