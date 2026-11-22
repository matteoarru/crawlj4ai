package io.crawlj4ai.diff;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.crawlj4ai.diff.SemanticDiffResult.ChangeCategory;
import io.crawlj4ai.llm.ChatModelRegistry;
import io.crawlj4ai.llm.LlmProfileConfig;
import io.crawlj4ai.model.MarkdownResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SemanticDiffDetectorTest {

    private static ChatModel stubModel(Function<ChatRequest, String> fn) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                return ChatResponse.builder().aiMessage(AiMessage.from(fn.apply(chatRequest))).build();
            }

            @Override
            public Set<Capability> supportedCapabilities() {
                return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
            }
        };
    }

    @Test
    @DisplayName("Short-circuits initial capture, identical SHA-256, and whitespace-only edits without calling LLM")
    void shortCircuitsBaselineIdenticalHashAndWhitespaceWithoutLlm(@TempDir Path tempDir) {
        AtomicInteger llmCalls = new AtomicInteger();
        ChatModel model = stubModel(req -> {
            llmCalls.incrementAndGet();
            return "{\"substantive\": false}";
        });

        SnapshotStore store = new FileSnapshotStore(tempDir.resolve("snapshots.json"));
        SemanticDiffDetector detector = new SemanticDiffDetector(store, model, "Track pricing updates");
        String url = "https://api.example.com/pricing";

        SemanticDiffResult r1 = detector.detect(url, new MarkdownResult("Pro: $20/mo", "Pro: $20/mo", "hash-1"));
        assertTrue(r1.substantive());
        assertEquals(ChangeCategory.INITIAL_CAPTURE, r1.category());
        assertEquals(0, llmCalls.get());

        SemanticDiffResult r2 = detector.detect(url, new MarkdownResult("Pro: $20/mo", "Pro: $20/mo", "hash-1"));
        assertFalse(r2.substantive());
        assertEquals(ChangeCategory.NO_CHANGE, r2.category());
        assertEquals(0, llmCalls.get());

        SemanticDiffResult r3 = detector.detect(url, new MarkdownResult("Pro: $20/mo\n\n  ", "Pro: $20/mo\n\n  ", "hash-2"));
        assertFalse(r3.substantive());
        assertEquals(ChangeCategory.COSMETIC_OR_NOISE, r3.category());
        assertEquals(0, llmCalls.get());
    }

    @Test
    @DisplayName("Routes diff evaluation through monitor-specific llmProfile in ChatModelRegistry")
    void routesDiffEvaluationToRequestedProfileInRegistry() {
        AtomicInteger gemmaCalls = new AtomicInteger();
        AtomicInteger qwenCalls = new AtomicInteger();

        ChatModel gemmaStub = stubModel(req -> {
            gemmaCalls.incrementAndGet();
            return """
                    {
                      "substantive": false,
                      "category": "COSMETIC_OR_NOISE",
                      "significanceScore": 0.1,
                      "summary": "Only relative timestamp changed.",
                      "keyChanges": []
                    }
                    """;
        });
        ChatModel qwenStub = stubModel(req -> {
            qwenCalls.incrementAndGet();
            return """
                    {
                      "substantive": true,
                      "category": "PRICING_OR_POLICY_UPDATE",
                      "significanceScore": 0.95,
                      "summary": "Enterprise plan increased from $99 to $129.",
                      "keyChanges": ["Enterprise price: $99 -> $129"]
                    }
                    """;
        });

        ChatModelRegistry registry = new ChatModelRegistry(
                "gemma4-local",
                Map.of(
                        "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768),
                        "qwen-local", LlmProfileConfig.ollama("http://localhost:11434", "qwen3.8:27b", 32_768),
                        "none", LlmProfileConfig.disabled()
                )
        ) {
            @Override
            public Optional<ChatModel> get(String profileName) {
                return switch (profileName) {
                    case "gemma4-local" -> Optional.of(gemmaStub);
                    case "qwen-local" -> Optional.of(qwenStub);
                    default -> super.get(profileName);
                };
            }
        };

        SemanticDiffDetector detector = new SemanticDiffDetector(SnapshotStore.inMemory(), registry, "Default focus");
        String url = "https://example.com/plans";

        detector.detect(url, new MarkdownResult("", "Enterprise: $99/mo", "h1"), "qwen-local", null);
        SemanticDiffResult substantive = detector.detect(
                url,
                new MarkdownResult("", "Enterprise: $129/mo", "h2"),
                "qwen-local",
                "Track exact plan dollar amounts"
        );

        assertTrue(substantive.substantive());
        assertEquals(ChangeCategory.PRICING_OR_POLICY_UPDATE, substantive.category());
        assertEquals(0.95, substantive.significanceScore());
        assertEquals(1, qwenCalls.get());
        assertEquals(0, gemmaCalls.get());

        SemanticDiffResult deterministic = detector.detect(
                url,
                new MarkdownResult("", "Enterprise: $149/mo", "h3"),
                "none",
                null
        );
        assertTrue(deterministic.substantive());
        assertEquals(ChangeCategory.CONTENT_MODIFIED, deterministic.category());
    }
}
