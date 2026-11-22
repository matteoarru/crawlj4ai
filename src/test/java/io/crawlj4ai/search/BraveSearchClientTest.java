package io.crawlj4ai.search;

import com.sun.net.httpserver.HttpServer;
import io.crawlj4ai.search.BraveSearchQuery.Freshness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BraveSearchClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger webCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/res/v1/web/search", exchange -> {
            assertEquals("test-brave-token", exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            int callNum = webCalls.incrementAndGet();
            if (callNum == 1) {
                exchange.getResponseHeaders().add("X-RateLimit-Reset", "1, 60");
                byte[] err = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }

            String json = """
                    {
                      "query": { "original": "playwright java", "more_results_available": true },
                      "web": {
                        "results": [
                          {
                            "title": "Playwright Java Docs",
                            "url": "https://playwright.dev/java/",
                            "description": "Official docs",
                            "page_age": "2026-11-20",
                            "extra_snippets": ["BrowserContext isolation"]
                          }
                        ]
                      }
                    }
                    """;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(json.getBytes(StandardCharsets.UTF_8));
            }
            byte[] compressed = baos.toByteArray();
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "19");
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });

        server.createContext("/res/v1/llm/context", exchange -> {
            byte[] payload = """
                    {
                      "grounding": {
                        "generic": [
                          {
                            "url": "https://playwright.dev/java/",
                            "title": "Playwright Java",
                            "snippets": ["Supports Chromium, Firefox, and WebKit."]
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("Retries HTTP 429 rate limits, decodes gzip responses, and parses web hits and LLM context")
    void retriesRateLimitDecodesGzipAndParsesResults() throws Exception {
        BraveSearchClient client = new BraveSearchClient(
                new BraveSearchConfig("test-brave-token", baseUrl, Duration.ofSeconds(5), 2, 0L)
        );

        BraveSearchQuery query = BraveSearchQuery.builder("playwright java")
                .count(5)
                .freshness(Freshness.PAST_WEEK)
                .build();

        BraveSearchResult result = client.searchWeb(query);
        assertEquals(2, webCalls.get());
        assertTrue(result.moreResultsAvailable());
        assertEquals(1, result.webHits().size());
        assertEquals("https://playwright.dev/java/", result.urls().getFirst());
        assertEquals("19", result.rateLimitRemaining());

        BraveLlmContextResult context = client.fetchLlmContext("playwright browsers", 2048);
        assertEquals(1, context.chunks().size());
        assertEquals("Playwright Java", context.chunks().getFirst().title());
    }
}
