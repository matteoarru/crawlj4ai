package io.crawlj4ai.llm;

import java.time.Instant;

/**
 * Diagnostic record emitted whenever {@link FallbackChatModel} fails over to a backup profile.
 */
public record FailoverEvent(
        String failedProfile,
        String nextProfile,
        FailureReason reason,
        String detail,
        long elapsedMs,
        long timestampEpochMs
) {
    public enum FailureReason {
        TIMEOUT,
        CONNECTION_OR_HTTP_ERROR,
        JSON_PARSE_FAILURE,
        EMPTY_RESPONSE,
        COOLDOWN_SKIPPED,
        OTHER_ERROR
    }

    public static FailoverEvent of(
            String failedProfile,
            String nextProfile,
            FailureReason reason,
            String detail,
            long elapsedMs
    ) {
        return new FailoverEvent(
                failedProfile,
                nextProfile,
                reason,
                detail,
                elapsedMs,
                Instant.now().toEpochMilli()
        );
    }
}
