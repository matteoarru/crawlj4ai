package io.crawlj4ai.llm;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.Optional;
import java.util.Set;

/**
 * Instantiates LangChain4j {@link ChatModel} clients for Ollama, OpenAI, or OpenAI-compatible servers.
 */
public final class ChatModelFactory {

    private ChatModelFactory() {}

    public static Optional<ChatModel> create(LlmProfileConfig config) {
        if (config == null || !config.isEnabled()) {
            return Optional.empty();
        }

        ChatModel rawModel = switch (config.provider()) {
            case NONE -> null;

            case OLLAMA -> {
                String baseUrl = (config.baseUrl() == null || config.baseUrl().isBlank())
                        ? "http://localhost:11434"
                        : config.baseUrl();

                var builder = OllamaChatModel.builder()
                        .baseUrl(baseUrl)
                        .modelName(config.modelName())
                        .temperature(config.temperature())
                        .timeout(config.timeout())
                        .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

                if (config.numCtx() != null && config.numCtx() > 0) {
                    builder.numCtx(config.numCtx());
                }
                yield builder.build();
            }

            case OPENAI -> {
                requireApiKey(config);
                var builder = OpenAiChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.modelName())
                        .temperature(config.temperature())
                        .timeout(config.timeout())
                        .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                        .strictJsonSchema(config.strictJsonSchema());

                if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                    builder.baseUrl(config.baseUrl());
                }
                yield builder.build();
            }

            case OPENAI_COMPATIBLE -> {
                if (config.baseUrl() == null || config.baseUrl().isBlank()) {
                    throw new IllegalArgumentException(
                            "baseUrl is required for OPENAI_COMPATIBLE provider (e.g., http://localhost:1234/v1)"
                    );
                }
                String apiKey = (config.apiKey() == null || config.apiKey().isBlank())
                        ? "not-needed"
                        : config.apiKey();

                var builder = OpenAiChatModel.builder()
                        .baseUrl(config.baseUrl())
                        .apiKey(apiKey)
                        .modelName(config.modelName())
                        .temperature(config.temperature())
                        .timeout(config.timeout());

                if (config.strictJsonSchema()) {
                    builder.supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                           .strictJsonSchema(true);
                }
                yield builder.build();
            }
        };

        if (rawModel == null) {
            return Optional.empty();
        }

        ChatModel wrapped = config.stripThinkingBlocks()
                ? new SanitizingChatModel(rawModel, true)
                : rawModel;

        return Optional.of(wrapped);
    }

    private static void requireApiKey(LlmProfileConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalArgumentException(
                    "apiKey is required when provider is " + config.provider()
            );
        }
    }
}
