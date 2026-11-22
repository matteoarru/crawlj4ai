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
 * Disk-backed {@link SearchMonitorStore} persisting search monitor state across restarts.
 */
public class FileSearchMonitorStore implements SearchMonitorStore {

    private final Path storagePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, SearchMonitorState> store = new ConcurrentHashMap<>();

    public FileSearchMonitorStore(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    @Override
    public Optional<SearchMonitorState> get(String monitorId) {
        return Optional.ofNullable(store.get(monitorId));
    }

    @Override
    public synchronized void put(SearchMonitorState state) {
        store.put(state.monitorId(), state);
        saveToDisk();
    }

    @Override
    public synchronized void remove(String monitorId) {
        if (store.remove(monitorId) != null) {
            saveToDisk();
        }
    }

    @Override
    public Collection<SearchMonitorState> findAll() {
        return List.copyOf(store.values());
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) return;
        try {
            Map<String, SearchMonitorState> loaded = mapper.readValue(
                    storagePath.toFile(),
                    new TypeReference<>() {}
            );
            store.putAll(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load search monitor store from " + storagePath, e);
        }
    }

    private void saveToDisk() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), store);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist search monitor store to " + storagePath, e);
        }
    }
}
