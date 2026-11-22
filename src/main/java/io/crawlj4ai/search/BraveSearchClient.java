package io.crawlj4ai.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * HTTP client for Brave Web Search ({@code /web/search}) and LLM Context ({@code /llm/context})
 * with gzip decoding, request pacing, and {@code X-RateLimit-Reset} retry backoff.
 */
public class BraveSearchClient {

    private final BraveSearchConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong lastRequestEpochMs = new AtomicLong(0L);

    public BraveSearchClient(BraveSearchConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(config.timeout())
                .build();
    }

    public static BraveSearchClient fromEnv() {
        return new BraveSearchClient(BraveSearchConfig.fromEnv());
    }

    public BraveSearchResult searchWeb(String query) throws IOException, InterruptedException {
        return searchWeb(BraveSearchQuery.of(query));
    }

    public BraveSearchResult searchWeb(BraveSearchQuery query) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query.query());
        params.put("count", String.valueOf(query.count()));
        params.put("offset", String.valueOf(query.offset()));
        params.put("extra_snippets", String.valueOf(query.extraSnippets()));

        query.freshness().ifPresent(v -> params.put("freshness", v));
        query.country().ifPresent(v -> params.put("country", v));
        query.searchLang().ifPresent(v -> params.put("search_lang", v));
        query.safeSearch().ifPresent(v -> params.put("safesearch", v));
        query.goggles().ifPresent(v -> params.put("goggles", v));

        URI uri = buildUri("/web/search", params);
        HttpResponse<InputStream> response = sendWithRetry(uri);
        JsonNode root = parseJsonBody(response);

        String originalQuery = root.path("query").path("original").asText(query.query());
        boolean moreAvailable = root.path("query").path("more_results_available").asBoolean(false);

        List<BraveWebHit> hits = new ArrayList<>();
        JsonNode resultsNode = root.path("web").path("results");
        if (resultsNode.isArray()) {
            int rank = (query.offset() * query.count()) + 1;
            for (JsonNode item : resultsNode) {
                String url = item.path("url").asText(null);
                if (url == null || url.isBlank()) continue;

                List<String> extras = new ArrayList<>();
                JsonNode extraNode = item.path("extra_snippets");
                if (extraNode.isArray()) {
                    extraNode.forEach(s -> extras.add(s.asText()));
                }

                hits.add(new BraveWebHit(
                        rank++,
                        item.path("title").asText(""),
                        url,
                        item.path("description").asText(""),
                        item.path("page_age").asText(item.path("age").asText(null)),
                        List.copyOf(extras)
                ));
            }
        }

        String remaining = response.headers().firstValue("x-ratelimit-remaining").orElse(null);
        String reset = response.headers().firstValue("x-ratelimit-reset").orElse(null);
        return new BraveSearchResult(originalQuery, moreAvailable, List.copyOf(hits), remaining, reset);
    }

    public BraveLlmContextResult fetchLlmContext(String query, int maxTokens)
            throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("maximum_number_of_tokens", String.valueOf(Math.max(1024, Math.min(32768, maxTokens))));

        URI uri = buildUri("/llm/context", params);
        HttpResponse<InputStream> response = sendWithRetry(uri);
        JsonNode root = parseJsonBody(response);

        List<BraveLlmContextResult.ContextChunk> chunks = new ArrayList<>();
        JsonNode genericNode = root.path("grounding").path("generic");
        if (genericNode.isArray()) {
            for (JsonNode item : genericNode) {
                List<String> snippets = new ArrayList<>();
                JsonNode snippetsNode = item.path("snippets");
                if (snippetsNode.isArray()) {
                    snippetsNode.forEach(s -> snippets.add(s.asText()));
                }
                chunks.add(new BraveLlmContextResult.ContextChunk(
                        item.path("url").asText(""),
                        item.path("title").asText(""),
                        List.copyOf(snippets)
                ));
            }
        }
        return new BraveLlmContextResult(query, List.copyOf(chunks));
    }

    private HttpResponse<InputStream> sendWithRetry(URI uri) throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            paceRequests();

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(config.timeout())
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("X-Subscription-Token", config.apiKey())
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response;
            }

            String errorBody;
            try (InputStream errStream = decodeStream(response)) {
                errorBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            if ((status == 429 || status >= 500) && attempt < config.maxRetries()) {
                attempt++;
                long waitMs = computeRetryDelayMs(response, attempt);
                Thread.sleep(waitMs);
                continue;
            }

            throw new IOException("Brave Search API failed with HTTP " + status + ": " + errorBody);
        }
    }

    private void paceRequests() throws InterruptedException {
        long minGap = config.minRequestIntervalMs();
        if (minGap <= 0) return;

        synchronized (lastRequestEpochMs) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestEpochMs.get();
            if (elapsed < minGap) {
                Thread.sleep(minGap - elapsed);
            }
            lastRequestEpochMs.set(System.currentTimeMillis());
        }
    }

    private long computeRetryDelayMs(HttpResponse<?> response, int attempt) {
        String resetHeader = response.headers().firstValue("x-ratelimit-reset").orElse(null);
        if (resetHeader != null && !resetHeader.isBlank()) {
            String firstWindow = resetHeader.split(",")[0].trim();
            try {
                long resetSec = Long.parseLong(firstWindow);
                if (resetSec > 0 && resetSec <= 30) {
                    return (resetSec * 1_000L) + 150L;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return (long) Math.pow(2, attempt) * 500L;
    }

    private JsonNode parseJsonBody(HttpResponse<InputStream> response) throws IOException {
        try (InputStream decoded = decodeStream(response)) {
            return mapper.readTree(decoded);
        }
    }

    private InputStream decodeStream(HttpResponse<InputStream> response) throws IOException {
        String encoding = response.headers().firstValue("content-encoding").orElse("");
        if ("gzip".equalsIgnoreCase(encoding.trim())) {
            return new GZIPInputStream(response.body());
        }
        return response.body();
    }

    private URI buildUri(String path, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(config.baseUrl()).append(path).append('?');
        boolean first = true;
        for (var entry : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }
}
