package io.crawlj4ai.diff;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists {@code fitMarkdown} snapshots to disk so Scout retains baseline states across restarts.
 */
public class FileSnapshotStore implements SnapshotStore {

    private final Path storagePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, PageSnapshot> store = new ConcurrentHashMap<>();

    public FileSnapshotStore(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    @Override
    public Optional<PageSnapshot> get(String url) {
        return Optional.ofNullable(store.get(url));
    }

    @Override
    public synchronized void put(PageSnapshot snapshot) {
        store.put(snapshot.url(), snapshot);
        saveToDisk();
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) return;
        try {
            Map<String, PageSnapshot> loaded = mapper.readValue(
                    storagePath.toFile(),
                    new TypeReference<>() {}
            );
            store.putAll(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load snapshot store from " + storagePath, e);
        }
    }

    private void saveToDisk() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), store);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist snapshot store to " + storagePath, e);
        }
    }
}
