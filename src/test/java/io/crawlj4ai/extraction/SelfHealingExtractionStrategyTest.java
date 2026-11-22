package io.crawlj4ai.extraction;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.crawlj4ai.llm.ChatModelRegistry;
import io.crawlj4ai.llm.FailoverEvent.FailureReason;
import io.crawlj4ai.llm.FallbackChatModel;
import io.crawlj4ai.llm.FallbackChatModel.NamedChatModel;
import io.crawlj4ai.llm.LlmProfileConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SelfHealingExtractionStrategyTest {

    private static final String REDESIGNED_HTML = """
            <section class="catalog-grid">
              <article data-testid="product-item">
                <h2 class="product-heading">Mechanical Keyboard</h2>
                <span class="amount">$149.00</span>
              </article>
              <article data-testid="product-item">
                <h2 class="product-heading">Wireless Mouse</h2>
                <span class="amount">$79.00</span>
              </article>
            </section>
            """;

    private static ChatModel stubModel(Function<ChatRequest, String> responder) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(responder.apply(chatRequest)))
                        .build();
            }

            @Override
            public Set<Capability> supportedCapabilities() {
                return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
            }
        };
    }

    @Test
    @DisplayName("Repairs broken selectors via FallbackChatModel when primary profile times out and caches result")
    void repairsBrokenSelectorsViaFallbackChatModelAndCachesResult() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();

        ChatModel primaryLocal = stubModel(req -> {
            primaryCalls.incrementAndGet();
            throw new RuntimeException("Ollama timed out", new HttpTimeoutException("read timed out"));
        });

        ChatModel backupCloud = stubModel(req -> {
            backupCalls.incrementAndGet();
            return """
                    {
                      "baseSelector": "article[data-testid='product-item']",
                      "fieldSelectors": {
                        "title": "h2.product-heading",
                        "price": "span.amount"
                      }
                    }
                    """;
        });

        FallbackChatModel chain = new FallbackChatModel(
                List.of(
                        new NamedChatModel("qwen-local", primaryLocal),
                        new NamedChatModel("openai", backupCloud)
                ),
                Duration.ofSeconds(30),
                null
        );

        ChatModelRegistry registry = new ChatModelRegistry(
                "default",
                Map.of("qwen-local", LlmProfileConfig.ollama("http://localhost:11434", "qwen3.8:27b", 32_768))
        ) {
            @Override
            public Optional<ChatModel> get(String profileName) {
                if ("qwen-local".equals(profileName)) {
                    return Optional.of(chain);
                }
                return super.get(profileName);
            }
        };

        SelectorCache selectorCache = SelectorCache.inMemory();
        SelfHealingExtractionStrategy strategy = SelfHealingExtractionStrategy.builder(
                        "catalog",
                        new CssSelectorSchema("div.old-card", Map.of("title", "h3.old", "price", "span.old"))
                )
                .fieldDescriptions(Map.of("title", "Product name", "price", "Product price"))
                .requiredFields(Set.of("title", "price"))
                .minExpectedItems(2)
                .cache(selectorCache)
                .chatModelRegistry(registry)
                .llmProfile("qwen-local")
                .build();

        String firstJson = strategy.extract("https://store.example.com/items", REDESIGNED_HTML, "");
        assertTrue(firstJson.contains("Mechanical Keyboard"));
        assertTrue(firstJson.contains("$149.00"));
        assertEquals(1, primaryCalls.get());
        assertEquals(1, backupCalls.get());
        assertEquals(FailureReason.TIMEOUT, chain.recentFailoverEvents().getFirst().reason());

        CssSelectorSchema cachedSchema = selectorCache.get("store.example.com:catalog").orElseThrow();
        assertEquals("article[data-testid='product-item']", cachedSchema.baseSelector());

        String secondJson = strategy.extract("https://store.example.com/items", REDESIGNED_HTML, "");
        assertTrue(secondJson.contains("Wireless Mouse"));
        assertEquals(1, primaryCalls.get());
        assertEquals(1, backupCalls.get());
    }

    @Test
    @DisplayName("Inherits runtime llmProfileOverride when strategy does not pin a profile")
    void inheritsRuntimeProfileOverrideWhenUnpinned() throws Exception {
        ChatModel overrideModel = stubModel(req -> """
                {
                  "baseSelector": "article[data-testid='product-item']",
                  "fieldSelectors": {
                    "title": "h2",
                    "price": "span.amount"
                  }
                }
                """);

        ChatModelRegistry registry = new ChatModelRegistry(
                "default",
                Map.of(
                        "default", LlmProfileConfig.disabled(),
                        "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                )
        ) {
            @Override
            public Optional<ChatModel> get(String profileName) {
                if ("gemma4-local".equals(profileName)) {
                    return Optional.of(overrideModel);
                }
                return super.get(profileName);
            }
        };

        SelfHealingExtractionStrategy unpinnedStrategy = SelfHealingExtractionStrategy.builder(
                        "catalog_unpinned",
                        new CssSelectorSchema("div.missing", Map.of("title", "h2", "price", "span"))
                )
                .requiredFields(Set.of("title", "price"))
                .minExpectedItems(2)
                .chatModelRegistry(registry)
                .build();

        assertThrows(
                IllegalStateException.class,
                () -> unpinnedStrategy.extract("https://store.example.com/items", REDESIGNED_HTML, "")
        );

        String json = unpinnedStrategy.extract(
                "https://store.example.com/items",
                REDESIGNED_HTML,
                "",
                "gemma4-local"
        );
        assertTrue(json.contains("Mechanical Keyboard"));
    }
}
