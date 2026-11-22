package io.crawlj4ai.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.crawlj4ai.api.CrawlJ4AiApplication;
import io.crawlj4ai.llm.ChatModelRegistry;
import io.crawlj4ai.llm.FallbackChatModel;
import io.crawlj4ai.llm.FallbackChatModel.NamedChatModel;
import io.crawlj4ai.llm.LlmProfileConfig;
import io.crawlj4ai.search.BraveSearchClient;
import io.crawlj4ai.search.BraveSearchConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        classes = {CrawlJ4AiApplication.class, ScoutEndToEndTest.E2ETestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScoutEndToEndTest {

    @TempDir
    static Path tempDataDir;

    private static HttpServer mockInternet;
    private static String localOriginUrl;
    private static String mockBraveBaseUrl;

    private static final AtomicReference<String> pricingETag = new AtomicReference<>("\"etag-v1\"");
    private static final AtomicReference<String> pricingHtml = new AtomicReference<>("""
            <html><body>
              <main>
                <h1>API Pricing</h1>
                <div class="plan-card" data-tier="pro">
                  <h2 class="tier-name">Pro Plan</h2>
                  <span class="tier-price">$20/month</span>
                </div>
                <p class="footer-ts">Updated 1 minute ago</p>
              </main>
            </body></html>
            """);

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void startMockInternet() throws Exception {
        mockInternet = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        mockInternet.createContext("/pricing", exchange -> {
            String currentTag = pricingETag.get();
            exchange.getResponseHeaders().add("ETag", currentTag);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");

            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                String reqTag = exchange.getRequestHeaders().getFirst("If-None-Match");
                exchange.sendResponseHeaders(currentTag.equals(reqTag) ? 304 : 200, -1);
                exchange.close();
                return;
            }

            byte[] body = pricingHtml.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockInternet.createContext("/res/v1/web/search", exchange -> {
            String json = """
                    {
                      "query": { "original": "api pricing updates", "more_results_available": false },
                      "web": {
                        "results": [
                          {
                            "title": "API Pricing Page",
                            "url": "%s/pricing",
                            "description": "Official API pricing tiers",
                            "page_age": "1h"
                          }
                        ]
                      }
                    }
                    """.formatted(localOriginUrl);
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        mockInternet.start();
        int port = mockInternet.getAddress().getPort();
        localOriginUrl = "http://127.0.0.1:" + port;
        mockBraveBaseUrl = localOriginUrl + "/res/v1";
    }

    @AfterAll
    static void stopMockInternet() {
        if (mockInternet != null) {
            mockInternet.stop(0);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("crawlj4ai.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("crawlj4ai.max-concurrency", () -> "2");
        registry.add("crawlj4ai.scheduler-tick-seconds", () -> "1");
        registry.add("crawlj4ai.brave-api-key", () -> "e2e-brave-key");
    }

    @TestConfiguration
    static class E2ETestBeans {

        @Bean
        @Primary
        public BraveSearchClient testBraveSearchClient() {
            return new BraveSearchClient(
                    new BraveSearchConfig("e2e-brave-key", mockBraveBaseUrl, Duration.ofSeconds(5), 1, 0L)
            );
        }

        @Bean
        @Primary
        public ChatModelRegistry testChatModelRegistry() {
            ChatModel slowLocalModel = new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest chatRequest) {
                    throw new RuntimeException("Local Ollama timeout", new HttpTimeoutException("timed out"));
                }

                @Override
                public Set<Capability> supportedCapabilities() {
                    return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
                }
            };

            ChatModel reliableBackupModel = new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest chatRequest) {
                    String prompt = chatRequest.messages().stream()
                            .filter(m -> m instanceof UserMessage)
                            .map(m -> ((UserMessage) m).singleText())
                            .findFirst()
                            .orElse("");

                    if (prompt.contains("Previous broken baseSelector")) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("""
                                {
                                  "baseSelector": "div.plan-card",
                                  "fieldSelectors": {
                                    "tier": "h2.tier-name",
                                    "price": "span.tier-price"
                                  }
                                }
                                """)).build();
                    }

                    boolean priceChanged = prompt.contains("$35/month");
                    String diffJson = priceChanged
                            ? """
                              {
                                "substantive": true,
                                "category": "PRICING_OR_POLICY_UPDATE",
                                "significanceScore": 0.95,
                                "summary": "Pro Plan price increased from $20/month to $35/month.",
                                "keyChanges": ["Pro Plan: $20/month -> $35/month"]
                              }
                              """
                            : """
                              {
                                "substantive": false,
                                "category": "COSMETIC_OR_NOISE",
                                "significanceScore": 0.05,
                                "summary": "Only relative footer timestamp changed.",
                                "keyChanges": []
                              }
                              """;
                    return ChatResponse.builder().aiMessage(AiMessage.from(diffJson)).build();
                }

                @Override
                public Set<Capability> supportedCapabilities() {
                    return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
                }
            };

            FallbackChatModel hybridChain = new FallbackChatModel(
                    List.of(
                            new NamedChatModel("gemma4-local", slowLocalModel),
                            new NamedChatModel("openai-backup", reliableBackupModel)
                    ),
                    Duration.ofSeconds(1),
                    null
            );

            return new ChatModelRegistry(
                    "hybrid-default",
                    Map.of(
                            "hybrid-default", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                                    .withFallbacks(List.of("openai-backup")),
                            "openai-backup", LlmProfileConfig.openAiCompatible(
                                    "https://api.openai.com/v1", "sk-test", "gpt-4o-mini", true
                            )
                    )
            ) {
                @Override
                public Optional<ChatModel> get(String profileName) {
                    if ("hybrid-default".equals(profileName)) {
                        return Optional.of(hybridChain);
                    }
                    return super.get(profileName);
                }
            };
        }
    }

    @Test
    @Order(1)
    @DisplayName("E2E UC1: First crawl renders via Playwright; second crawl skips Chromium via HTTP 304")
    void endToEndCrawlAndHttp304BrowserSkip() throws Exception {
        String requestBody = """
                {
                  "url": "%s/pricing",
                  "llmProfile": "hybrid-default"
                }
                """.formatted(localOriginUrl);

        MvcResult firstRes = mockMvc.perform(post("/api/v1/scout/crawl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = mapper.readTree(firstRes.getResponse().getContentAsString());
        assertFalse(firstJson.get("skippedBrowserViaHttp304").asBoolean());
        assertTrue(firstJson.get("fitMarkdown").asText().contains("Pro Plan"));

        MvcResult secondRes = mockMvc.perform(post("/api/v1/scout/crawl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode secondJson = mapper.readTree(secondRes.getResponse().getContentAsString());
        assertTrue(secondJson.get("skippedBrowserViaHttp304").asBoolean());
        assertEquals(304, secondJson.get("statusCode").asInt());
    }

    @Test
    @Order(2)
    @DisplayName("E2E UC2: Self-healing CSS extraction repairs broken selectors via FallbackChatModel")
    void endToEndSelfHealingExtraction() throws Exception {
        String extractPayload = """
                {
                  "url": "%s/pricing",
                  "schemaName": "pricing_tiers",
                  "baseSelector": "div.broken-legacy-card",
                  "fieldSelectors": {
                    "tier": "h3.broken",
                    "price": "span.broken"
                  },
                  "requiredFields": ["tier", "price"],
                  "selfHealing": true,
                  "llmProfile": "hybrid-default"
                }
                """.formatted(localOriginUrl);

        MvcResult res = mockMvc.perform(post("/api/v1/scout/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(extractPayload))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertTrue(body.get("success").asBoolean());
        assertEquals("div.plan-card", body.get("activeSelectorSchema").get("baseSelector").asText());
        assertEquals("Pro Plan", body.get("extractedData").get(0).get("tier").asText());
        assertEquals("$20/month", body.get("extractedData").get(0).get("price").asText());
    }

    @Test
    @Order(3)
    @DisplayName("E2E UC3 & UC4: Search monitor discovers URL, filters timestamp noise, and alerts on real price change")
    void endToEndSearchDiscoveryNoiseSuppressionAndSubstantiveAlert() throws Exception {
        String monitorPayload = """
                {
                  "monitorId": "e2e-pricing-watch",
                  "query": "api pricing updates",
                  "count": 3,
                  "llmProfile": "hybrid-default",
                  "volatileTarget": true,
                  "triggerImmediately": true
                }
                """;

        mockMvc.perform(post("/api/v1/scout/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monitorPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.monitorId").value("e2e-pricing-watch"));

        Thread.sleep(600);

        mockMvc.perform(get("/api/v1/scout/monitors/e2e-pricing-watch/urls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value(localOriginUrl + "/pricing"));

        pricingETag.set("\"etag-v2-noise\"");
        pricingHtml.set("""
                <html><body><main>
                  <h1>API Pricing</h1>
                  <div class="plan-card" data-tier="pro">
                    <h2 class="tier-name">Pro Plan</h2>
                    <span class="tier-price">$20/month</span>
                  </div>
                  <p class="footer-ts">Updated just now</p>
                </main></body></html>
                """);

        MvcResult noiseCrawl = mockMvc.perform(post("/api/v1/scout/crawl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + localOriginUrl + "/pricing\",\"llmProfile\":\"hybrid-default\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(mapper.readTree(noiseCrawl.getResponse().getContentAsString())
                .get("changedFromPrevious").asBoolean());

        pricingETag.set("\"etag-v3-price-hike\"");
        pricingHtml.set("""
                <html><body><main>
                  <h1>API Pricing</h1>
                  <div class="plan-card" data-tier="pro">
                    <h2 class="tier-name">Pro Plan</h2>
                    <span class="tier-price">$35/month</span>
                  </div>
                  <p class="footer-ts">Updated just now</p>
                </main></body></html>
                """);

        mockMvc.perform(post("/api/v1/scout/schedules/url/trigger")
                        .param("url", localOriginUrl + "/pricing"))
                .andExpect(status().isAccepted());

        Thread.sleep(800);

        mockMvc.perform(get("/api/v1/scout/alerts/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'NEW_URL_DISCOVERED')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'CONTENT_UPDATED')]").exists());

        mockMvc.perform(get("/api/v1/scout/llm/failovers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hybrid-default.failoverSuccesses").isNumber());
    }
}
