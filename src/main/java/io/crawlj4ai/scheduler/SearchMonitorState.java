package io.crawlj4ai.scheduler;

import java.util.List;

/**
 * Persisted discovery cadence and deduplicated {@code knownUrls} list for a search monitor.
 */
public record SearchMonitorState(
        String monitorId,
        String queryText,
        String llmProfile,
        long nextSearchAtEpochMs,
        long lastSearchedAtEpochMs,
        long currentIntervalMs,
        double estimatedDiscoveriesPerHour,
        long totalSearches,
        long totalUrlsDiscovered,
        List<String> knownUrls
) {
    public SearchMonitorState(
            String monitorId,
            String queryText,
            long nextSearchAtEpochMs,
            long lastSearchedAtEpochMs,
            long currentIntervalMs,
            double estimatedDiscoveriesPerHour,
            long totalSearches,
            long totalUrlsDiscovered,
            List<String> knownUrls
    ) {
        this(
                monitorId,
                queryText,
                "default",
                nextSearchAtEpochMs,
                lastSearchedAtEpochMs,
                currentIntervalMs,
                estimatedDiscoveriesPerHour,
                totalSearches,
                totalUrlsDiscovered,
                knownUrls
        );
    }

    public static SearchMonitorState initial(
            String monitorId,
            String queryText,
            String llmProfile,
            long nowEpochMs,
            SchedulerPolicy policy
    ) {
        long initialMs = policy.initialInterval().toMillis();
        double seedRatePerHour = -Math.log(1.0 - policy.targetChangeProbability())
                / Math.max(initialMs / 3_600_000.0, 1e-4);

        return new SearchMonitorState(
                monitorId,
                queryText,
                (llmProfile == null || llmProfile.isBlank()) ? "default" : llmProfile.trim(),
                nowEpochMs,
                0L,
                initialMs,
                seedRatePerHour,
                0L,
                0L,
                List.of()
        );
    }
}
