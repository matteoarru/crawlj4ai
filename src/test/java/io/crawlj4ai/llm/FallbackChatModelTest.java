package io.crawlj4ai.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.crawlj4ai.llm.FailoverEvent.FailureReason;
import io.crawlj4ai.llm.FallbackChatModel.NamedChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class FallbackChatModelTest {

    private static ChatModel stubModel(Function<ChatRequest, ChatResponse> handler) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                return handler.apply(chatRequest);
            }

            @Override
            public Set<Capability> supportedCapabilities() {
                return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
            }
        };
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
    }

    private static ChatRequest jsonRequest(String prompt) {
        return ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .build())
                .build();
    }

    private static ChatRequest plainTextRequest(String prompt) {
        return ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();
    }

    @Test
    @DisplayName("Uses primary model without invoking backup when primary succeeds")
    void primarySucceedsWithoutInvokingBackup() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();

        ChatModel primary = stubModel(req -> {
            primaryCalls.incrementAndGet();
            return textResponse("{\"substantive\": true}");
        });
        ChatModel backup = stubModel(req -> {
            backupCalls.incrementAndGet();
            return textResponse("{\"substantive\": false}");
        });

        FallbackChatModel fallbackModel = new FallbackChatModel(
                "gemma4-local", primary,
                "openai", backup
        );

        ChatResponse response = fallbackModel.chat(jsonRequest("Evaluate diff"));

        assertEquals("{\"substantive\": true}", response.aiMessage().text());
        assertEquals(1, primaryCalls.get());
        assertEquals(0, backupCalls.get());
        assertEquals(1L, fallbackModel.primarySuccessCount());
        assertEquals(0L, fallbackModel.failoverSuccessCount());
        assertTrue(fallbackModel.recentFailoverEvents().isEmpty());
    }

    @Test
    @DisplayName("Fails over to backup cloud profile when primary throws timeout exception")
    void timeoutFailoverRoutesToBackupAndRecordsTimeoutReason() {
        List<FailoverEvent> recordedEvents = new ArrayList<>();

        ChatModel primary = stubModel(req -> {
            throw new RuntimeException("Ollama request failed", new HttpTimeoutException("request timed out"));
        });
        ChatModel backup = stubModel(req -> textResponse("{\"substantive\": true, \"category\": \"CONTENT_ADDED\"}"));

        FallbackChatModel fallbackModel = new FallbackChatModel(
                List.of(
                        new NamedChatModel("gemma4-local", primary),
                        new NamedChatModel("openai", backup)
                ),
                Duration.ofSeconds(30),
                recordedEvents::add
        );

        ChatResponse response = fallbackModel.chat(jsonRequest("Evaluate diff"));

        assertTrue(response.aiMessage().text().contains("CONTENT_ADDED"));
        assertEquals(0L, fallbackModel.primarySuccessCount());
        assertEquals(1L, fallbackModel.failoverSuccessCount());
        assertEquals(1, recordedEvents.size());

        FailoverEvent event = recordedEvents.getFirst();
        assertEquals("gemma4-local", event.failedProfile());
        assertEquals("openai", event.nextProfile());
        assertEquals(FailureReason.TIMEOUT, event.reason());
    }

    @Test
    @DisplayName("Fails over when primary emits malformed JSON for a JSON-formatted request")
    void malformedJsonFailoverRoutesToBackupWhenJsonFormatRequested() {
        AtomicInteger backupCalls = new AtomicInteger();

        ChatModel primary = stubModel(req -> textResponse("{\"substantive\": true, \"summary\": \"Price increased"));
        ChatModel backup = stubModel(req -> {
            backupCalls.incrementAndGet();
            return textResponse("{\"substantive\": true, \"summary\": \"Price increased\"}");
        });

        FallbackChatModel fallbackModel = new FallbackChatModel(
                "qwen-local", primary,
                "qwen-cloud", backup
        );

        ChatResponse response = fallbackModel.chat(jsonRequest("Extract pricing diff"));

        assertEquals("{\"substantive\": true, \"summary\": \"Price increased\"}", response.aiMessage().text());
        assertEquals(1, backupCalls.get());
        assertEquals(1L, fallbackModel.failoverSuccessCount());

        FailoverEvent event = fallbackModel.recentFailoverEvents().getFirst();
        assertEquals("qwen-local", event.failedProfile());
        assertEquals("qwen-cloud", event.nextProfile());
        assertEquals(FailureReason.JSON_PARSE_FAILURE, event.reason());
    }

    @Test
    @DisplayName("Accepts non-JSON prose on primary when request does not require JSON format")
    void plainTextResponseAcceptedWhenJsonFormatNotRequested() {
        AtomicInteger backupCalls = new AtomicInteger();

        ChatModel primary = stubModel(req -> textResponse("Plain summary without braces."));
        ChatModel backup = stubModel(req -> {
            backupCalls.incrementAndGet();
            return textResponse("Backup response");
        });

        FallbackChatModel fallbackModel = new FallbackChatModel(
                "gemma4-local", primary,
                "openai", backup
        );

        ChatResponse response = fallbackModel.chat(plainTextRequest("Summarize page"));

        assertEquals("Plain summary without braces.", response.aiMessage().text());
        assertEquals(0, backupCalls.get());
        assertEquals(1L, fallbackModel.primarySuccessCount());
    }

    @Test
    @DisplayName("SanitizingChatModel strips <think> tags and fences so valid JSON succeeds without failover")
    void sanitizingChatModelStripsThinkBlocksAndMarkdownFencesBeforeValidation() {
        AtomicInteger backupCalls = new AtomicInteger();

        ChatModel rawLocalModel = stubModel(req -> textResponse("""
                <think>
                Comparing old pricing vs new pricing...
                </think>
                ```json
                {"substantive": true, "significanceScore": 0.9}
                ```
                """));
        ChatModel sanitizedPrimary = new SanitizingChatModel(rawLocalModel, true);
        ChatModel backup = stubModel(req -> {
            backupCalls.incrementAndGet();
            return textResponse("{\"substantive\": false}");
        });

        FallbackChatModel fallbackModel = new FallbackChatModel(
                "qwen-local", sanitizedPrimary,
                "openai", backup
        );

        ChatResponse response = fallbackModel.chat(jsonRequest("Check pricing"));

        assertEquals("{\"substantive\": true, \"significanceScore\": 0.9}", response.aiMessage().text());
        assertEquals(0, backupCalls.get());
        assertEquals(1L, fallbackModel.primarySuccessCount());
    }

    @Test
    @DisplayName("Fails over on blank/empty responses with EMPTY_RESPONSE reason")
    void emptyResponseTriggersFailover() {
        ChatModel primary = stubModel(req -> textResponse("   \n  "));
        ChatModel backup = stubModel(req -> textResponse("{\"ok\": true}"));

        FallbackChatModel fallbackModel = new FallbackChatModel(
                "gemma4-local", primary,
                "openai", backup
        );

        ChatResponse response = fallbackModel.chat(jsonRequest("Ping"));

        assertEquals("{\"ok\": true}", response.aiMessage().text());
        assertEquals(FailureReason.EMPTY_RESPONSE, fallbackModel.recentFailoverEvents().getFirst().reason());
    }

    @Test
    @DisplayName("Skips primary during transient cooldown after timeout and recovers once cooldown expires")
    void cooldownSkipsPrimaryDuringWindowAndRecoversAfterExpiration() throws InterruptedException {
        AtomicBoolean primaryHealthy = new AtomicBoolean(false);
        AtomicInteger primaryAttempts = new AtomicInteger();
        AtomicInteger backupAttempts = new AtomicInteger();

        ChatModel primary = stubModel(req -> {
            primaryAttempts.incrementAndGet();
            if (!primaryHealthy.get()) {
                throw new RuntimeException(new SocketTimeoutException("Read timed out"));
            }
            return textResponse("{\"source\": \"primary-recovered\"}");
        });

        ChatModel backup = stubModel(req -> {
            backupAttempts.incrementAndGet();
            return textResponse("{\"source\": \"backup\"}");
        });

        FallbackChatModel fallbackModel = new FallbackChatModel(
                List.of(
                        new NamedChatModel("gemma4-local", primary),
                        new NamedChatModel("openai", backup)
                ),
                Duration.ofMillis(120),
                null
        );

        ChatResponse r1 = fallbackModel.chat(jsonRequest("req-1"));
        assertEquals("{\"source\": \"backup\"}", r1.aiMessage().text());
        assertEquals(1, primaryAttempts.get());
        assertEquals(1, backupAttempts.get());
        assertEquals(FailureReason.TIMEOUT, fallbackModel.recentFailoverEvents().getLast().reason());

        ChatResponse r2 = fallbackModel.chat(jsonRequest("req-2"));
        assertEquals("{\"source\": \"backup\"}", r2.aiMessage().text());
        assertEquals(1, primaryAttempts.get());
        assertEquals(2, backupAttempts.get());
        assertEquals(FailureReason.COOLDOWN_SKIPPED, fallbackModel.recentFailoverEvents().getLast().reason());

        Thread.sleep(160);
        primaryHealthy.set(true);

        ChatResponse r3 = fallbackModel.chat(jsonRequest("req-3"));
        assertEquals("{\"source\": \"primary-recovered\"}", r3.aiMessage().text());
        assertEquals(2, primaryAttempts.get());
        assertEquals(2, backupAttempts.get());
        assertEquals(1L, fallbackModel.primarySuccessCount());
        assertEquals(2L, fallbackModel.failoverSuccessCount());
    }

    @Test
    @DisplayName("JSON parse failure fails over once without putting primary into transport cooldown")
    void jsonParseFailureDoesNotTriggerTransportCooldown() {
        AtomicInteger primaryAttempts = new AtomicInteger();

        ChatModel primary = stubModel(req -> {
            int attempt = primaryAttempts.incrementAndGet();
            if (attempt == 1) {
                return textResponse("not-valid-json");
            }
            return textResponse("{\"recovered\": true}");
        });
        ChatModel backup = stubModel(req -> textResponse("{\"recovered\": \"via-backup\"}"));

        FallbackChatModel fallbackModel = new FallbackChatModel(
                List.of(
                        new NamedChatModel("gemma4-local", primary),
                        new NamedChatModel("openai", backup)
                ),
                Duration.ofMinutes(5),
                null
        );

        ChatResponse first = fallbackModel.chat(jsonRequest("req-1"));
        assertEquals("{\"recovered\": \"via-backup\"}", first.aiMessage().text());

        ChatResponse second = fallbackModel.chat(jsonRequest("req-2"));
        assertEquals("{\"recovered\": true}", second.aiMessage().text());
        assertEquals(2, primaryAttempts.get());
    }

    @Test
    @DisplayName("Propagates final exception when every model in the fallback chain fails")
    void propagatesExceptionWhenAllModelsInChainFail() {
        ChatModel primary = stubModel(req -> {
            throw new RuntimeException(new HttpTimeoutException("Primary timed out"));
        });
        ChatModel backup = stubModel(req -> {
            throw new RuntimeException(new ConnectException("Backup unreachable"));
        });

        FallbackChatModel fallbackModel = new FallbackChatModel(
                "gemma4-local", primary,
                "openai", backup
        );

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> fallbackModel.chat(jsonRequest("req-fail"))
        );

        assertTrue(thrown.getCause() instanceof ConnectException);
        assertEquals(0L, fallbackModel.primarySuccessCount());
        assertEquals(0L, fallbackModel.failoverSuccessCount());
    }
}
