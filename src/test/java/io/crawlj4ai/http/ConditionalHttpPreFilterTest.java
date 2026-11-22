package io.crawlj4ai.http;

import com.sun.net.httpserver.HttpServer;
import io.crawlj4ai.http.ConditionalCheckResult.Decision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConditionalHttpPreFilterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> serverETag = new AtomicReference<>("\"v1\"");
    private final AtomicInteger serverStatusOnHead = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resource", exchange -> {
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            int forcedStatus = serverStatusOnHead.get();
            if (forcedStatus == 405) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            String currentTag = serverETag.get();
            exchange.getResponseHeaders().add("ETag", currentTag);

            if (currentTag != null && currentTag.equals(ifNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("Skips browser on HTTP 304 Not Modified and triggers crawl when ETag rotates or HEAD returns 405")
    void skipsBrowserOn304AndHandlesValidatorRotationAnd405Downgrade() {
        HttpValidatorStore store = HttpValidatorStore.inMemory();
        ConditionalHttpPreFilter preFilter = new ConditionalHttpPreFilter(
                store, "CrawlJ4Ai-Test/1.0", Duration.ofSeconds(3)
        );
        String url = baseUrl + "/resource";

        assertEquals(Decision.NEEDS_FULL_CRAWL, preFilter.check(url).decision());

        preFilter.recordFromResponseHeaders(url, Map.of("ETag", "\"v1\"", "Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT"));
        ConditionalCheckResult hit304 = preFilter.check(url);
        assertTrue(hit304.canSkipBrowser());
        assertEquals(304, hit304.httpStatus());

        serverETag.set("\"v2\"");
        ConditionalCheckResult changed = preFilter.check(url);
        assertFalse(changed.canSkipBrowser());
        assertEquals("\"v2\"", changed.observedETag());

        serverStatusOnHead.set(405);
        assertFalse(preFilter.check(url).canSkipBrowser());
        assertFalse(store.get(url).orElseThrow().headSupported());
    }
}
