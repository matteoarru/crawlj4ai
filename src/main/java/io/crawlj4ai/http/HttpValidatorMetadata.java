package io.crawlj4ai.http;

import java.time.Instant;

/**
 * Cached HTTP conditional validator headers ({@code ETag} and {@code Last-Modified}) for a URL.
 */
public record HttpValidatorMetadata(
        String url,
        String eTag,
        String lastModified,
        boolean headSupported,
        long updatedAtEpochMs
) {
    public static HttpValidatorMetadata of(String url, String eTag, String lastModified) {
        return new HttpValidatorMetadata(
                url,
                normalize(eTag),
                normalize(lastModified),
                true,
                Instant.now().toEpochMilli()
        );
    }

    public HttpValidatorMetadata withHeadUnsupported() {
        return new HttpValidatorMetadata(
                url,
                eTag,
                lastModified,
                false,
                Instant.now().toEpochMilli()
        );
    }

    public HttpValidatorMetadata touched(String refreshedETag, String refreshedLastModified) {
        String nextETag = normalize(refreshedETag) != null ? normalize(refreshedETag) : eTag;
        String nextLastMod = normalize(refreshedLastModified) != null ? normalize(refreshedLastModified) : lastModified;
        return new HttpValidatorMetadata(
                url,
                nextETag,
                nextLastMod,
                headSupported,
                Instant.now().toEpochMilli()
        );
    }

    public boolean hasValidators() {
        return (eTag != null && !eTag.isBlank()) || (lastModified != null && !lastModified.isBlank());
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
