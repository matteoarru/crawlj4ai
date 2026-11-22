package io.crawlj4ai.extraction;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache store for learned and self-healed {@link CssSelectorSchema} definitions.
 */
public interface SelectorCache {
    Optional<CssSelectorSchema> get(String schemaKey);
    void put(String schemaKey, CssSelectorSchema schema);

    static SelectorCache inMemory() {
        return new SelectorCache() {
            private final ConcurrentHashMap<String, CssSelectorSchema> store = new ConcurrentHashMap<>();

            @Override
            public Optional<CssSelectorSchema> get(String schemaKey) {
                return Optional.ofNullable(store.get(schemaKey));
            }

            @Override
            public void put(String schemaKey, CssSelectorSchema schema) {
                store.put(schemaKey, schema);
            }
        };
    }
}
