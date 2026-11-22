package io.crawlj4ai.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists {@code ETag} and {@code Last-Modified} validators to disk so Scout skips Chromium across restarts.
 */
public class FileHttpValidatorStore implements HttpValidatorStore {

    private final Path storagePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, HttpValidatorMetadata> store = new ConcurrentHashMap<>();

    public FileHttpValidatorStore(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    @Override
    public Optional<HttpValidatorMetadata> get(String url) {
        return Optional.ofNullable(store.get(url));
    }

    @Override
    public synchronized void put(HttpValidatorMetadata metadata) {
        store.put(metadata.url(), metadata);
        saveToDisk();
    }

    @Override
    public synchronized void remove(String url) {
        if (store.remove(url) != null) {
            saveToDisk();
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) return;
        try {
            Map<String, HttpValidatorMetadata> loaded = mapper.readValue(
                    storagePath.toFile(),
                    new TypeReference<>() {}
            );
            store.putAll(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load HTTP validator store from " + storagePath, e);
        }
    }

    private void saveToDisk() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), store);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist HTTP validator store to " + storagePath, e);
        }
    }
}
