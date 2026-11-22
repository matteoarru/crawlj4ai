package io.crawlj4ai.diff;

import java.time.Instant;

/**
 * Stored baseline snapshot of a webpage's {@code fitMarkdown} and SHA-256 fingerprint.
 */
public record PageSnapshot(
        String url,
        String sha256Hash,
        String fitMarkdown,
        String capturedAt
) {
    public static PageSnapshot of(String url, String sha256Hash, String fitMarkdown) {
        return new PageSnapshot(url, sha256Hash, fitMarkdown, Instant.now().toString());
    }
}
