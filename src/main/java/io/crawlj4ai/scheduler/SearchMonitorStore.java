package io.crawlj4ai.scheduler;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence store for {@link SearchMonitorState} records.
 */
public interface SearchMonitorStore {
    Optional<SearchMonitorState> get(String monitorId);
    void put(SearchMonitorState state);
    void remove(String monitorId);
    Collection<SearchMonitorState> findAll();

    static SearchMonitorStore inMemory() {
        return new SearchMonitorStore() {
            private final ConcurrentHashMap<String, SearchMonitorState> map = new ConcurrentHashMap<>();

            @Override
            public Optional<SearchMonitorState> get(String monitorId) {
                return Optional.ofNullable(map.get(monitorId));
            }

            @Override
            public void put(SearchMonitorState state) {
                map.put(state.monitorId(), state);
            }

            @Override
            public void remove(String monitorId) {
                map.remove(monitorId);
            }

            @Override
            public Collection<SearchMonitorState> findAll() {
                return List.copyOf(map.values());
            }
        };
    }
}
