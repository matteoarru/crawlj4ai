package io.crawlj4ai.api;

import io.crawlj4ai.llm.LlmProfileConfig;
import io.crawlj4ai.llm.LlmProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "crawlj4ai")
public record CrawlJ4AiProperties(
        String dataDir,
        int maxConcurrency,
        boolean headless,
        long schedulerTickSeconds,
        String braveApiKey,
        String monitoringFocus,
        LlmProperties llm
) {
    public record LlmProperties(
            String defaultProfile,
            Map<String, ProfileProperties> profiles
    ) {}

    public record ProfileProperties(
            LlmProviderType provider,
            String baseUrl,
            String apiKey,
            String modelName,
            Double temperature,
            Integer numCtx,
            Long timeoutSeconds,
            Boolean strictJsonSchema,
            Boolean stripThinkingBlocks,
            List<String> fallbackProfiles
    ) {
        public LlmProfileConfig toConfig() {
            return new LlmProfileConfig(
                    provider != null ? provider : LlmProviderType.NONE,
                    baseUrl,
                    apiKey,
                    modelName,
                    temperature != null ? temperature : 0.0,
                    numCtx,
                    Duration.ofSeconds(timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : 90L),
                    Boolean.TRUE.equals(strictJsonSchema),
                    stripThinkingBlocks == null || stripThinkingBlocks,
                    fallbackProfiles != null ? fallbackProfiles : List.of()
            );
        }
    }
}
