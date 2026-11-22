package io.crawlj4ai.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-backed {@link ScheduleStore} persisting per-URL polling intervals to JSON.
 */
public class FileScheduleStore implements ScheduleStore {

    private final Path storagePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, UrlScheduleState> store = new ConcurrentHashMap<>();

    public FileScheduleStore(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    @Override
    public Optional<UrlScheduleState> get(String url) {
        return Optional.ofNullable(store.get(url));
    }

    @Override
    public synchronized void put(UrlScheduleState state) {
        store.put(state.url(), state);
        saveToDisk();
    }

    @Override
    public synchronized void remove(String url) {
        if (store.remove(url) != null) {
            saveToDisk();
        }
    }

    @Override
    public Collection<UrlScheduleState> findAll() {
        return List.copyOf(store.values());
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) return;
        try {
            Map<String, UrlScheduleState> loaded = mapper.readValue(
                    storagePath.toFile(),
                    new TypeReference<>() {}
            );
            store.putAll(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load schedule store from " + storagePath, e);
        }
    }

    private void saveToDisk() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), store);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist schedule store to " + storagePath, e);
        }
    }
}
