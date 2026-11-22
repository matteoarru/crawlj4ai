package io.crawlj4ai.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decorator that strips {@code <think>...</think>} reasoning blocks and Markdown JSON code fences
 * emitted by smaller local open-weight models before downstream JSON parsing.
 */
public class SanitizingChatModel implements ChatModel {

    private static final Pattern THINK_BLOCK_PATTERN =
            Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern MARKDOWN_FENCE_PATTERN =
            Pattern.compile("(?s)^```(?:json|JSON)?\\s*(.*?)\\s*```$");

    private final ChatModel delegate;
    private final boolean stripThinkingBlocks;

    public SanitizingChatModel(ChatModel delegate, boolean stripThinkingBlocks) {
        this.delegate = delegate;
        this.stripThinkingBlocks = stripThinkingBlocks;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        ChatResponse rawResponse = delegate.chat(chatRequest);
        if (rawResponse == null || rawResponse.aiMessage() == null || rawResponse.aiMessage().text() == null) {
            return rawResponse;
        }

        String cleanedText = sanitizeText(rawResponse.aiMessage().text());
        if (cleanedText.equals(rawResponse.aiMessage().text())) {
            return rawResponse;
        }

        AiMessage cleanedMessage = rawResponse.aiMessage().hasToolExecutionRequests()
                ? AiMessage.from(cleanedText, rawResponse.aiMessage().toolExecutionRequests())
                : AiMessage.from(cleanedText);

        return ChatResponse.builder()
                .aiMessage(cleanedMessage)
                .metadata(rawResponse.metadata())
                .build();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    static String sanitizeText(String raw) {
        String text = raw.trim();
        text = THINK_BLOCK_PATTERN.matcher(text).replaceAll("").trim();

        var fenceMatcher = MARKDOWN_FENCE_PATTERN.matcher(text);
        if (fenceMatcher.matches()) {
            text = fenceMatcher.group(1).trim();
        }
        return text;
    }
}
