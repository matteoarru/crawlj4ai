package io.crawlj4ai.scheduler;

import io.crawlj4ai.diff.SemanticDiffResult.ChangeCategory;

/**
 * Per-URL adaptive schedule state and Poisson substantive change-rate metrics.
 */
public record UrlScheduleState(
        String url,
        long nextCrawlAtEpochMs,
        long lastCrawledAtEpochMs,
        long currentIntervalMs,
        double estimatedChangesPerHour,
        double emaSubstantiveScore,
        long totalChecks,
        long substantiveHits,
        int consecutiveNonSubstantive,
        ChangeCategory lastCategory
) {
    public static UrlScheduleState initial(String url, long nowEpochMs, SchedulerPolicy policy) {
        long initialMs = policy.initialInterval().toMillis();
        double seedRatePerHour = -Math.log(1.0 - policy.targetChangeProbability())
                / Math.max(initialMs / 3_600_000.0, 1e-4);

        return new UrlScheduleState(
                url,
                nowEpochMs,
                0L,
                initialMs,
                seedRatePerHour,
                0.0,
                0L,
                0L,
                0,
                null
        );
    }
}
