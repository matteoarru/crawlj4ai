package io.crawlj4ai.diff;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence abstraction for {@link PageSnapshot} records.
 */
public interface SnapshotStore {
    Optional<PageSnapshot> get(String url);
    void put(PageSnapshot snapshot);

    static SnapshotStore inMemory() {
        return new SnapshotStore() {
            private final ConcurrentHashMap<String, PageSnapshot> store = new ConcurrentHashMap<>();

            @Override
            public Optional<PageSnapshot> get(String url) {
                return Optional.ofNullable(store.get(url));
            }

            @Override
            public void put(PageSnapshot snapshot) {
                store.put(snapshot.url(), snapshot);
            }
        };
    }
}
