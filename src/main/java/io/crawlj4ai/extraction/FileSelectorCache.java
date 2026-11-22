package io.crawlj4ai.extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists repaired CSS selectors to a local JSON file so Scout retains repairs across restarts.
 */
public class FileSelectorCache implements SelectorCache {

    private final Path storagePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CssSelectorSchema> cache = new ConcurrentHashMap<>();

    public FileSelectorCache(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    @Override
    public Optional<CssSelectorSchema> get(String schemaKey) {
        return Optional.ofNullable(cache.get(schemaKey));
    }

    @Override
    public synchronized void put(String schemaKey, CssSelectorSchema schema) {
        cache.put(schemaKey, schema);
        saveToDisk();
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) return;
        try {
            Map<String, CssSelectorSchema> loaded = mapper.readValue(
                    storagePath.toFile(),
                    new TypeReference<>() {}
            );
            cache.putAll(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load selector cache from " + storagePath, e);
        }
    }

    private void saveToDisk() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), cache);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist selector cache to " + storagePath, e);
        }
    }
}
