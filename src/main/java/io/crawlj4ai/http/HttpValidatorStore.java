package io.crawlj4ai.http;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence store for {@link HttpValidatorMetadata}.
 */
public interface HttpValidatorStore {
    Optional<HttpValidatorMetadata> get(String url);
    void put(HttpValidatorMetadata metadata);
    void remove(String url);

    static HttpValidatorStore inMemory() {
        return new HttpValidatorStore() {
            private final ConcurrentHashMap<String, HttpValidatorMetadata> store = new ConcurrentHashMap<>();

            @Override
            public Optional<HttpValidatorMetadata> get(String url) {
                return Optional.ofNullable(store.get(url));
            }

            @Override
            public void put(HttpValidatorMetadata metadata) {
                store.put(metadata.url(), metadata);
            }

            @Override
            public void remove(String url) {
                store.remove(url);
            }
        };
    }
}
