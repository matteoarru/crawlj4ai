package io.crawlj4ai.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChatModelRegistryTest {

    private static LlmProfileConfig dummyOpenAiProfile(String modelName, List<String> fallbacks) {
        return new LlmProfileConfig(
                LlmProviderType.OPENAI,
                "https://api.openai.com/v1",
                "sk-test-key",
                modelName,
                0.0,
                null,
                Duration.ofSeconds(30),
                true,
                false,
                fallbacks
        );
    }

    @Test
    @DisplayName("Returns single SanitizingChatModel directly when profile declares no fallbacks")
    void resolvesSingleProfileDirectlyWithoutFallbackWrapper() {
        ChatModelRegistry registry = new ChatModelRegistry(
                "gemma4-local",
                Map.of(
                        "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                )
        );

        Optional<ChatModel> modelOpt = registry.getDefault();

        assertTrue(modelOpt.isPresent());
        assertInstanceOf(SanitizingChatModel.class, modelOpt.get());
        assertFalse(modelOpt.get() instanceof FallbackChatModel);
    }

    @Test
    @DisplayName("Builds ordered multi-hop FallbackChatModel chain across local and cloud profiles")
    void resolvesMultiHopFallbackChainInOrder() {
        ChatModelRegistry registry = new ChatModelRegistry(
                "gemma4-local",
                Map.of(
                        "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                                .withFallbacks(List.of("qwen-cloud", "openai")),
                        "qwen-cloud", LlmProfileConfig.openAiCompatible(
                                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                                "sk-qwen-test",
                                "qwen-plus",
                                false
                        ),
                        "openai", dummyOpenAiProfile("gpt-4o-mini", List.of())
                )
        );

        ChatModel resolved = registry.get("gemma4-local").orElseThrow();

        FallbackChatModel fallback = assertInstanceOf(FallbackChatModel.class, resolved);
        assertEquals(List.of("gemma4-local", "qwen-cloud", "openai"), fallback.profileChainNames());
    }

    @Test
    @DisplayName("Guards against direct self-referential circular fallback (A -> A)")
    void circularFallbackProtectionHandlesDirectSelfLoop() {
        ChatModelRegistry registry = new ChatModelRegistry(
                "gemma4-local",
                Map.of(
                        "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                                .withFallbacks(List.of("gemma4-local"))
                )
        );

        ChatModel resolved = assertDoesNotThrow(() -> registry.get("gemma4-local").orElseThrow());
        assertFalse(resolved instanceof FallbackChatModel);
    }

    @Test
    @DisplayName("Guards against mutual and multi-node circular fallback cycles (A -> B -> C -> A)")
    void circularFallbackProtectionHandlesMultiNodeCycle() {
        ChatModelRegistry registry = new ChatModelRegistry(
                "alpha",
                Map.of(
                        "alpha", dummyOpenAiProfile("model-a", List.of("beta")),
                        "beta", dummyOpenAiProfile("model-b", List.of("gamma")),
                        "gamma", dummyOpenAiProfile("model-c", List.of("alpha", "beta"))
                )
        );

        ChatModel resolvedAlpha = assertDoesNotThrow(() -> registry.get("alpha").orElseThrow());
        FallbackChatModel alphaChain = assertInstanceOf(FallbackChatModel.class, resolvedAlpha);
        assertEquals(List.of("alpha", "beta", "gamma"), alphaChain.profileChainNames());

        ChatModel resolvedBeta = assertDoesNotThrow(() -> registry.get("beta").orElseThrow());
        FallbackChatModel betaChain = assertInstanceOf(FallbackChatModel.class, resolvedBeta);
        assertEquals(List.of("beta", "gamma", "alpha"), betaChain.profileChainNames());
    }

    @Test
    @DisplayName("Skips disabled NONE profiles in chain and returns empty when all profiles are NONE")
    void skipsDisabledNoneProfilesInFallbackChain() {
        ChatModelRegistry registry = new ChatModelRegistry(
                "disabled-only",
                Map.of(
                        "disabled-only", LlmProfileConfig.disabled(),
                        "disabled-with-backup", LlmProfileConfig.disabled().withFallbacks(List.of("openai")),
                        "openai", dummyOpenAiProfile("gpt-4o-mini", List.of())
                )
        );

        assertTrue(registry.get("disabled-only").isEmpty());

        ChatModel resolvedBackup = registry.get("disabled-with-backup").orElseThrow();
        assertFalse(resolvedBackup instanceof FallbackChatModel);
    }

    @Test
    @DisplayName("Throws IllegalArgumentException for unknown profile or dangling fallback reference")
    void throwsOnUnknownProfileOrBrokenFallbackReference() {
        ChatModelRegistry registry = new ChatModelRegistry(
                "gemma4-local",
                Map.of(
                        "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                                .withFallbacks(List.of("non-existent-backup"))
                )
        );

        assertThrows(IllegalArgumentException.class, () -> registry.get("unknown-profile"));
        assertThrows(IllegalArgumentException.class, () -> registry.get("gemma4-local"));
    }

    @Test
    @DisplayName("Invalidates cached chains on registerProfile and exposes failover diagnostics")
    void registerProfileInvalidatesCachedChainsAndExposesDiagnostics() {
        ChatModelRegistry registry = new ChatModelRegistry(
                "gemma4-local",
                Map.of(
                        "gemma4-local", LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                )
        );

        assertFalse(registry.get("gemma4-local").orElseThrow() instanceof FallbackChatModel);
        assertTrue(registry.getFailoverDiagnostics().isEmpty());

        registry.registerProfile("openai", dummyOpenAiProfile("gpt-4o-mini", List.of()));
        registry.registerProfile(
                "gemma4-local",
                LlmProfileConfig.ollama("http://localhost:11434", "gemma4:e4b", 32_768)
                        .withFallbacks(List.of("openai"))
        );

        ChatModel updated = registry.get("gemma4-local").orElseThrow();
        FallbackChatModel chain = assertInstanceOf(FallbackChatModel.class, updated);
        assertEquals(List.of("gemma4-local", "openai"), chain.profileChainNames());

        Map<String, Map<String, Object>> diagnostics = registry.getFailoverDiagnostics();
        assertTrue(diagnostics.containsKey("gemma4-local"));
        assertEquals(List.of("gemma4-local", "openai"), diagnostics.get("gemma4-local").get("chain"));
        assertEquals(0L, diagnostics.get("gemma4-local").get("primarySuccesses"));
        assertEquals(0L, diagnostics.get("gemma4-local").get("failoverSuccesses"));
    }
}
