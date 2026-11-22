package io.crawlj4ai.api;

import io.crawlj4ai.diff.SemanticDiffResult;
import io.crawlj4ai.model.CrawlResult;
import io.crawlj4ai.scheduler.DiscoveredUrlEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScoutAlertEvent(
        String eventId,
        AlertType type,
        String monitorId,
        String url,
        String title,
        String summary,
        String category,
        double significanceScore,
        List<String> keyChanges,
        long timestampEpochMs
) {
    public enum AlertType {
        NEW_URL_DISCOVERED,
        CONTENT_UPDATED
    }

    public static ScoutAlertEvent fromDiscovery(DiscoveredUrlEvent event) {
        return new ScoutAlertEvent(
                UUID.randomUUID().toString(),
                AlertType.NEW_URL_DISCOVERED,
                event.monitorId(),
                event.hit().url(),
                event.hit().title(),
                "Discovered via query \"" + event.queryText() + "\" at rank #" + event.hit().rank(),
                "DISCOVERY",
                1.0,
                List.of(),
                event.discoveredAtEpochMs()
        );
    }

    public static ScoutAlertEvent fromCrawlResult(CrawlResult result) {
        SemanticDiffResult diff = result.semanticDiff();
        return new ScoutAlertEvent(
                UUID.randomUUID().toString(),
                AlertType.CONTENT_UPDATED,
                null,
                result.url(),
                null,
                diff != null ? diff.summary() : "Substantive webpage content change detected.",
                diff != null && diff.category() != null ? diff.category().name() : "CONTENT_MODIFIED",
                diff != null ? diff.significanceScore() : 1.0,
                diff != null && diff.keyChanges() != null ? diff.keyChanges() : List.of(),
                Instant.now().toEpochMilli()
        );
    }
}
