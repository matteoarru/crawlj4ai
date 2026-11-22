package io.crawlj4ai.scheduler;

import io.crawlj4ai.search.BraveWebHit;

/**
 * Event emitted when a {@link SearchMonitorSpec} discovers a previously unseen URL.
 */
public record DiscoveredUrlEvent(
        String monitorId,
        String queryText,
        BraveWebHit hit,
        long discoveredAtEpochMs
) {}
