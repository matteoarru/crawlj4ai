package io.crawlj4ai.diff;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Structured output returned by {@link SemanticDiffDetector} classifying whether a change is substantive.
 */
public record SemanticDiffResult(
        @Description("True if meaningful factual, structural, pricing, policy, or content updates occurred; false if changes are purely cosmetic, timestamps, ads, counters, or formatting noise.")
        boolean substantive,

        @Description("Primary classification of the detected change.")
        ChangeCategory category,

        @Description("Importance score from 0.0 (pure noise) to 1.0 (critical update).")
        double significanceScore,

        @Description("Concise 1-2 sentence explanation of what changed or why the edit was classified as non-substantive.")
        String summary,

        @Description("List of specific factual additions, removals, or modifications. Empty if non-substantive.")
        List<String> keyChanges
) {
    public enum ChangeCategory {
        INITIAL_CAPTURE,
        NO_CHANGE,
        COSMETIC_OR_NOISE,
        CONTENT_ADDED,
        CONTENT_MODIFIED,
        CONTENT_REMOVED,
        PRICING_OR_POLICY_UPDATE
    }

    public static SemanticDiffResult initialCapture() {
        return new SemanticDiffResult(
                true,
                ChangeCategory.INITIAL_CAPTURE,
                1.0,
                "Initial baseline snapshot captured.",
                List.of()
        );
    }

    public static SemanticDiffResult unchanged() {
        return new SemanticDiffResult(
                false,
                ChangeCategory.NO_CHANGE,
                0.0,
                "SHA-256 content hash matches previous snapshot.",
                List.of()
        );
    }

    public static SemanticDiffResult whitespaceOnlyNoise() {
        return new SemanticDiffResult(
                false,
                ChangeCategory.COSMETIC_OR_NOISE,
                0.05,
                "Only whitespace or empty line formatting changed.",
                List.of()
        );
    }
}
