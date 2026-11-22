package io.crawlj4ai.scheduler;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence store for {@link UrlScheduleState} records.
 */
public interface ScheduleStore {
    Optional<UrlScheduleState> get(String url);
    void put(UrlScheduleState state);
    void remove(String url);
    Collection<UrlScheduleState> findAll();

    static ScheduleStore inMemory() {
        return new ScheduleStore() {
            private final ConcurrentHashMap<String, UrlScheduleState> map = new ConcurrentHashMap<>();

            @Override
            public Optional<UrlScheduleState> get(String url) {
                return Optional.ofNullable(map.get(url));
            }

            @Override
            public void put(UrlScheduleState state) {
                map.put(state.url(), state);
            }

            @Override
            public void remove(String url) {
                map.remove(url);
            }

            @Override
            public Collection<UrlScheduleState> findAll() {
                return List.copyOf(map.values());
            }
        };
    }
}
