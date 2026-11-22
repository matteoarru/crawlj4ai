package io.crawlj4ai.llm;

import java.time.Duration;
import java.util.List;

/**
 * Declarative configuration for a named LLM model profile and its ordered fallback profiles.
 */
public record LlmProfileConfig(
        LlmProviderType provider,
        String baseUrl,
        String apiKey,
        String modelName,
        double temperature,
        Integer numCtx,
        Duration timeout,
        boolean strictJsonSchema,
        boolean stripThinkingBlocks,
        List<String> fallbackProfiles
) {
    public LlmProfileConfig(
            LlmProviderType provider,
            String baseUrl,
            String apiKey,
            String modelName,
            double temperature,
            Integer numCtx,
            Duration timeout,
            boolean strictJsonSchema,
            boolean stripThinkingBlocks
    ) {
        this(
                provider,
                baseUrl,
                apiKey,
                modelName,
                temperature,
                numCtx,
                timeout,
                strictJsonSchema,
                stripThinkingBlocks,
                List.of()
        );
    }

    public LlmProfileConfig {
        if (provider == null) {
            provider = LlmProviderType.NONE;
        }
        if (temperature < 0.0 || temperature > 2.0) {
            temperature = 0.0;
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(90);
        }
        fallbackProfiles = (fallbackProfiles == null) ? List.of() : List.copyOf(fallbackProfiles);
    }

    public boolean isEnabled() {
        return provider != LlmProviderType.NONE
                && modelName != null
                && !modelName.isBlank();
    }

    public LlmProfileConfig withFallbacks(List<String> fallbacks) {
        return new LlmProfileConfig(
                provider,
                baseUrl,
                apiKey,
                modelName,
                temperature,
                numCtx,
                timeout,
                strictJsonSchema,
                stripThinkingBlocks,
                fallbacks
        );
    }

    public static LlmProfileConfig ollama(String baseUrl, String modelName, int numCtx) {
        return new LlmProfileConfig(
                LlmProviderType.OLLAMA,
                (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:11434" : baseUrl,
                null,
                modelName,
                0.0,
                numCtx > 0 ? numCtx : 32_768,
                Duration.ofSeconds(120),
                false,
                true,
                List.of()
        );
    }

    public static LlmProfileConfig openAiCompatible(
            String baseUrl,
            String apiKey,
            String modelName,
            boolean strictJsonSchema
    ) {
        return new LlmProfileConfig(
                LlmProviderType.OPENAI_COMPATIBLE,
                baseUrl,
                (apiKey == null || apiKey.isBlank()) ? "not-needed" : apiKey,
                modelName,
                0.0,
                null,
                Duration.ofSeconds(90),
                strictJsonSchema,
                true,
                List.of()
        );
    }

    public static LlmProfileConfig disabled() {
        return new LlmProfileConfig(
                LlmProviderType.NONE, null, null, null, 0.0, null, Duration.ofSeconds(30), false, false, List.of()
        );
    }
}
