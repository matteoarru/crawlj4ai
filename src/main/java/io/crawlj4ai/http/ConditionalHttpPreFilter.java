package io.crawlj4ai.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight HTTP {@code HEAD} pre-filter that checks {@code If-None-Match} and
 * {@code If-Modified-Since} headers before launching headless Chromium.
 */
public class ConditionalHttpPreFilter {

    private final HttpValidatorStore validatorStore;
    private final HttpClient httpClient;
    private final String userAgent;
    private final Duration requestTimeout;

    public ConditionalHttpPreFilter(
            HttpValidatorStore validatorStore,
            String userAgent,
            Duration requestTimeout
    ) {
        this.validatorStore = validatorStore;
        this.userAgent = userAgent;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(requestTimeout)
                .build();
    }

    public ConditionalHttpPreFilter(HttpValidatorStore validatorStore, String userAgent) {
        this(validatorStore, userAgent, Duration.ofSeconds(8));
    }

    public ConditionalCheckResult check(String url) {
        Optional<HttpValidatorMetadata> cachedOpt = validatorStore.get(url);
        if (cachedOpt.isEmpty()) {
            return ConditionalCheckResult.needsFullCrawl(0, null, null, "No cached HTTP validators");
        }

        HttpValidatorMetadata cached = cachedOpt.get();
        if (!cached.headSupported()) {
            return ConditionalCheckResult.needsFullCrawl(0, null, null, "Origin previously rejected HEAD");
        }
        if (!cached.hasValidators()) {
            return ConditionalCheckResult.needsFullCrawl(0, null, null, "Origin emitted no ETag or Last-Modified");
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(requestTimeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            if (cached.eTag() != null) {
                reqBuilder.header("If-None-Match", cached.eTag());
            }
            if (cached.lastModified() != null) {
                reqBuilder.header("If-Modified-Since", cached.lastModified());
            }

            HttpResponse<Void> response = httpClient.send(
                    reqBuilder.build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            int status = response.statusCode();
            String respETag = response.headers().firstValue("etag").orElse(null);
            String respLastMod = response.headers().firstValue("last-modified").orElse(null);

            if (status == 304) {
                validatorStore.put(cached.touched(respETag, respLastMod));
                return ConditionalCheckResult.notModified(304, respETag, respLastMod, "HTTP 304 Not Modified");
            }

            if (status >= 200 && status < 300) {
                if (validatorsMatch(cached, respETag, respLastMod)) {
                    validatorStore.put(cached.touched(respETag, respLastMod));
                    return ConditionalCheckResult.notModified(
                            status,
                            respETag,
                            respLastMod,
                            "HEAD 200 returned identical ETag/Last-Modified"
                    );
                }
                return ConditionalCheckResult.needsFullCrawl(
                        status,
                        respETag,
                        respLastMod,
                        "Validators changed on origin"
                );
            }

            if (status == 405 || status == 501) {
                validatorStore.put(cached.withHeadUnsupported());
                return ConditionalCheckResult.needsFullCrawl(
                        status,
                        null,
                        null,
                        "Origin returned HTTP " + status + " for HEAD"
                );
            }

            return ConditionalCheckResult.needsFullCrawl(
                    status,
                    respETag,
                    respLastMod,
                    "HEAD returned HTTP " + status + "; falling back to browser"
            );
        } catch (Exception e) {
            return ConditionalCheckResult.needsFullCrawl(
                    0,
                    null,
                    null,
                    "HEAD probe failed (" + e.getClass().getSimpleName() + "); falling back to browser"
            );
        }
    }

    public void recordFromResponseHeaders(String url, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            validatorStore.put(HttpValidatorMetadata.of(url, null, null));
            return;
        }
        String eTag = findHeaderIgnoreCase(headers, "etag");
        String lastModified = findHeaderIgnoreCase(headers, "last-modified");
        validatorStore.put(HttpValidatorMetadata.of(url, eTag, lastModified));
    }

    private boolean validatorsMatch(HttpValidatorMetadata cached, String respETag, String respLastMod) {
        if (cached.eTag() != null && respETag != null) {
            return normalizeWeakETag(cached.eTag()).equals(normalizeWeakETag(respETag));
        }
        if (cached.lastModified() != null && respLastMod != null) {
            return cached.lastModified().trim().equalsIgnoreCase(respLastMod.trim());
        }
        return false;
    }

    private static String normalizeWeakETag(String tag) {
        String trimmed = tag.trim();
        if (trimmed.startsWith("W/") || trimmed.startsWith("w/")) {
            return trimmed.substring(2).trim();
        }
        return trimmed;
    }

    private static String findHeaderIgnoreCase(Map<String, String> headers, String targetName) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(targetName)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
