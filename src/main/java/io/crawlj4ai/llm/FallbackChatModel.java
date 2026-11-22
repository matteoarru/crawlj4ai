package io.crawlj4ai.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.crawlj4ai.llm.FailoverEvent.FailureReason;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Resilient LangChain4j {@link ChatModel} decorator that executes an ordered chain of model profiles
 * (for example, local Ollama Gemma 4 / Qwen followed by cloud OpenAI / DashScope).
 *
 * <p>Performs eager Jackson JSON validation inside {@link #chat(ChatRequest)} whenever
 * {@code ResponseFormatType.JSON} is requested so truncated or malformed JSON from small local models
 * triggers immediate failover to the next backup profile.</p>
 */
public class FallbackChatModel implements ChatModel {

    public record NamedChatModel(String profileName, ChatModel model) {
        public NamedChatModel {
            if (profileName == null || profileName.isBlank()) {
                throw new IllegalArgumentException("profileName must not be blank");
            }
            if (model == null) {
                throw new IllegalArgumentException("model must not be null");
            }
        }
    }

    private static final int MAX_STORED_EVENTS = 100;

    private final List<NamedChatModel> chain;
    private final Duration cooldownDuration;
    private final Consumer<FailoverEvent> failoverListener;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, Long> cooldownUntilEpochMs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<FailoverEvent> recentFailovers = new ConcurrentLinkedDeque<>();
    private final AtomicLong primarySuccessCount = new AtomicLong(0);
    private final AtomicLong failoverSuccessCount = new AtomicLong(0);

    public FallbackChatModel(
            String primaryProfile,
            ChatModel primaryModel,
            String backupProfile,
            ChatModel backupModel
    ) {
        this(
                List.of(
                        new NamedChatModel(primaryProfile, primaryModel),
                        new NamedChatModel(backupProfile, backupModel)
                ),
                Duration.ofSeconds(30),
                null
        );
    }

    public FallbackChatModel(
            List<NamedChatModel> chain,
            Duration cooldownDuration,
            Consumer<FailoverEvent> failoverListener
    ) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("FallbackChatModel requires at least one NamedChatModel");
        }
        this.chain = List.copyOf(chain);
        this.cooldownDuration = (cooldownDuration == null || cooldownDuration.isNegative())
                ? Duration.ZERO
                : cooldownDuration;
        this.failoverListener = failoverListener != null ? failoverListener : event -> {};
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        boolean expectsJson = requiresJsonValidation(chatRequest);
        RuntimeException lastException = null;

        for (int i = 0; i < chain.size(); i++) {
            NamedChatModel candidate = chain.get(i);
            boolean hasBackup = (i + 1) < chain.size();
            String nextProfileName = hasBackup ? chain.get(i + 1).profileName() : "NONE";

            if (hasBackup && isInCooldown(candidate.profileName())) {
                recordFailover(FailoverEvent.of(
                        candidate.profileName(),
                        nextProfileName,
                        FailureReason.COOLDOWN_SKIPPED,
                        "Skipped profile during active failure cooldown",
                        0L
                ));
                continue;
            }

            long startMs = System.currentTimeMillis();
            try {
                ChatResponse response = candidate.model().chat(chatRequest);
                validateResponse(response, expectsJson);

                cooldownUntilEpochMs.remove(candidate.profileName());
                if (i == 0) {
                    primarySuccessCount.incrementAndGet();
                } else {
                    failoverSuccessCount.incrementAndGet();
                }
                return response;
            } catch (Exception ex) {
                long elapsedMs = System.currentTimeMillis() - startMs;
                FailureReason reason = classifyFailure(ex);

                if (hasBackup && (reason == FailureReason.TIMEOUT || reason == FailureReason.CONNECTION_OR_HTTP_ERROR)) {
                    activateCooldown(candidate.profileName());
                }

                if (hasBackup) {
                    recordFailover(FailoverEvent.of(
                            candidate.profileName(),
                            nextProfileName,
                            reason,
                            summarizeException(ex),
                            elapsedMs
                    ));
                }

                lastException = (ex instanceof RuntimeException re)
                        ? re
                        : new RuntimeException("LLM profile '" + candidate.profileName() + "' failed", ex);
            }
        }

        throw lastException != null
                ? lastException
                : new IllegalStateException("All models in FallbackChatModel chain failed");
    }

    private void validateResponse(ChatResponse response, boolean expectsJson) throws JsonProcessingException {
        if (response == null || response.aiMessage() == null) {
            throw new IllegalStateException("LLM returned a null response message");
        }
        if (response.aiMessage().hasToolExecutionRequests()) {
            return;
        }

        String text = response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("LLM returned empty text content");
        }

        if (expectsJson) {
            JsonNode root = objectMapper.readTree(text);
            if (root == null || (!root.isObject() && !root.isArray())) {
                throw new IllegalStateException("Expected JSON object or array but received scalar value");
            }
        }
    }

    private static boolean requiresJsonValidation(ChatRequest request) {
        if (request == null) return false;
        ResponseFormat format = request.responseFormat();
        return format != null && format.type() == ResponseFormatType.JSON;
    }

    private static FailureReason classifyFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof JsonProcessingException) {
                return FailureReason.JSON_PARSE_FAILURE;
            }
            if (cursor instanceof HttpTimeoutException
                    || cursor instanceof SocketTimeoutException
                    || cursor instanceof TimeoutException) {
                return FailureReason.TIMEOUT;
            }
            if (cursor instanceof ConnectException) {
                return FailureReason.CONNECTION_OR_HTTP_ERROR;
            }
            String msg = cursor.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timed out") || lower.contains("timeout")) {
                    return FailureReason.TIMEOUT;
                }
                if (lower.contains("json") && (lower.contains("parse") || lower.contains("unexpected character")
                        || lower.contains("unrecognized token") || lower.contains("end-of-input"))) {
                    return FailureReason.JSON_PARSE_FAILURE;
                }
                if (lower.contains("empty text") || lower.contains("null response")) {
                    return FailureReason.EMPTY_RESPONSE;
                }
                if (lower.contains("connection refused") || lower.contains("429")
                        || lower.contains("502") || lower.contains("503") || lower.contains("504")) {
                    return FailureReason.CONNECTION_OR_HTTP_ERROR;
                }
            }
            cursor = cursor.getCause();
        }
        return FailureReason.OTHER_ERROR;
    }

    private boolean isInCooldown(String profileName) {
        if (cooldownDuration.isZero()) return false;
        Long until = cooldownUntilEpochMs.get(profileName);
        if (until == null) return false;
        if (Instant.now().toEpochMilli() >= until) {
            cooldownUntilEpochMs.remove(profileName, until);
            return false;
        }
        return true;
    }

    private void activateCooldown(String profileName) {
        if (!cooldownDuration.isZero()) {
            cooldownUntilEpochMs.put(
                    profileName,
                    Instant.now().toEpochMilli() + cooldownDuration.toMillis()
            );
        }
    }

    private void recordFailover(FailoverEvent event) {
        recentFailovers.addLast(event);
        while (recentFailovers.size() > MAX_STORED_EVENTS) {
            recentFailovers.pollFirst();
        }
        failoverListener.accept(event);
    }

    private static String summarizeException(Throwable ex) {
        String type = ex.getClass().getSimpleName();
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) return type;
        return msg.length() > 220 ? type + ": " + msg.substring(0, 220) + "..." : type + ": " + msg;
    }

    public List<String> profileChainNames() {
        return chain.stream().map(NamedChatModel::profileName).toList();
    }

    public long primarySuccessCount() {
        return primarySuccessCount.get();
    }

    public long failoverSuccessCount() {
        return failoverSuccessCount.get();
    }

    public List<FailoverEvent> recentFailoverEvents() {
        return List.copyOf(recentFailovers);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return chain.getFirst().model().defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return chain.getFirst().model().listeners();
    }

    @Override
    public ModelProvider provider() {
        return chain.getFirst().model().provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        Set<Capability> caps = new LinkedHashSet<>(chain.getFirst().model().supportedCapabilities());
        return Collections.unmodifiableSet(caps);
    }
}
