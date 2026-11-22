package io.crawlj4ai.api;

import io.crawlj4ai.model.CrawlResult;
import io.crawlj4ai.scheduler.DiscoveredUrlEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ScoutAlertBroadcaster {

    private static final int MAX_RECENT_EVENTS = 100;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<ScoutAlertEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService heartbeatExecutor;

    public ScoutAlertBroadcaster() {
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crawlj4ai-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 20, 20, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(boolean replayRecent) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data("Scout alert stream active"));
            if (replayRecent) {
                for (ScoutAlertEvent pastEvent : recentEvents) {
                    sendToEmitter(emitter, pastEvent);
                }
            }
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publishDiscovery(DiscoveredUrlEvent event) {
        broadcast(ScoutAlertEvent.fromDiscovery(event));
    }

    public void publishContentUpdate(CrawlResult result) {
        broadcast(ScoutAlertEvent.fromCrawlResult(result));
    }

    public List<ScoutAlertEvent> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    private void broadcast(ScoutAlertEvent alert) {
        recentEvents.addLast(alert);
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.pollFirst();
        }
        for (SseEmitter emitter : emitters) {
            sendToEmitter(emitter, alert);
        }
    }

    private void sendToEmitter(SseEmitter emitter, ScoutAlertEvent alert) {
        try {
            String eventName = alert.type() == ScoutAlertEvent.AlertType.NEW_URL_DISCOVERED
                    ? "discovery"
                    : "content-update";

            emitter.send(SseEmitter.event()
                    .id(alert.eventId())
                    .name(eventName)
                    .data(alert, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
    }

    private void sendHeartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }
}
