package io.crawlj4ai.llm;

/**
 * Supported LLM backend provider types for {@link ChatModelFactory}.
 */
public enum LlmProviderType {
    NONE,
    OLLAMA,
    OPENAI,
    OPENAI_COMPATIBLE
}
