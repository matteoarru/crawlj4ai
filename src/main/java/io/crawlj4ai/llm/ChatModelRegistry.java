package io.crawlj4ai.llm;

import dev.langchain4j.model.chat.ChatModel;
import io.crawlj4ai.llm.FallbackChatModel.NamedChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry of named {@link LlmProfileConfig} profiles that lazily resolves single models or
 * cycle-safe {@link FallbackChatModel} chains.
 */
public class ChatModelRegistry {

    private final String defaultProfileName;
    private final Duration failoverCooldown;
    private final Consumer<FailoverEvent> failoverListener;
    private final Map<String, LlmProfileConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, Optional<ChatModel>> baseInstances = new ConcurrentHashMap<>();
    private final Map<String, Optional<ChatModel>> resolvedInstances = new ConcurrentHashMap<>();

    public ChatModelRegistry(String defaultProfileName, Map<String, LlmProfileConfig> initialProfiles) {
        this(defaultProfileName, initialProfiles, Duration.ofSeconds(30), null);
    }

    public ChatModelRegistry(
            String defaultProfileName,
            Map<String, LlmProfileConfig> initialProfiles,
            Duration failoverCooldown,
            Consumer<FailoverEvent> failoverListener
    ) {
        this.defaultProfileName = (defaultProfileName == null || defaultProfileName.isBlank())
                ? "default"
                : defaultProfileName;
        this.failoverCooldown = failoverCooldown != null ? failoverCooldown : Duration.ofSeconds(30);
        this.failoverListener = failoverListener;
        if (initialProfiles != null) {
            configs.putAll(initialProfiles);
        }
    }

    public Optional<ChatModel> getDefault() {
        return get(defaultProfileName);
    }

    public Optional<ChatModel> get(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return getDefault();
        }
        if (!configs.containsKey(profileName)) {
            throw new IllegalArgumentException(
                    "Unknown LLM profile '" + profileName + "'. Available profiles: " + configs.keySet()
            );
        }
        return resolvedInstances.computeIfAbsent(profileName, this::buildResolvedModel);
    }

    private Optional<ChatModel> buildResolvedModel(String rootProfileName) {
        LlmProfileConfig rootConfig = configs.get(rootProfileName);
        if (rootConfig == null) {
            return Optional.empty();
        }

        List<NamedChatModel> chain = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        collectChain(rootProfileName, visited, chain);

        if (chain.isEmpty()) {
            return Optional.empty();
        }
        if (chain.size() == 1) {
            return Optional.of(chain.getFirst().model());
        }
        return Optional.of(new FallbackChatModel(chain, failoverCooldown, failoverListener));
    }

    private void collectChain(String profileName, Set<String> visited, List<NamedChatModel> chain) {
        if (!visited.add(profileName)) {
            return;
        }
        LlmProfileConfig cfg = configs.get(profileName);
        if (cfg == null) {
            throw new IllegalArgumentException(
                    "Fallback references unknown LLM profile '" + profileName + "'"
            );
        }

        Optional<ChatModel> baseModel = baseInstances.computeIfAbsent(
                profileName,
                k -> ChatModelFactory.create(cfg)
        );
        baseModel.ifPresent(model -> chain.add(new NamedChatModel(profileName, model)));

        for (String fallbackName : cfg.fallbackProfiles()) {
            if (fallbackName != null && !fallbackName.isBlank()) {
                collectChain(fallbackName.trim(), visited, chain);
            }
        }
    }

    public void registerProfile(String profileName, LlmProfileConfig config) {
        configs.put(profileName, config);
        baseInstances.remove(profileName);
        resolvedInstances.clear();
    }

    public Set<String> availableProfiles() {
        return Set.copyOf(configs.keySet());
    }

    public Map<String, LlmProfileConfig> allConfigs() {
        return Map.copyOf(configs);
    }

    public Map<String, Map<String, Object>> getFailoverDiagnostics() {
        Map<String, Map<String, Object>> report = new ConcurrentHashMap<>();
        resolvedInstances.forEach((profile, modelOpt) -> {
            if (modelOpt.isPresent() && modelOpt.get() instanceof FallbackChatModel fcm) {
                report.put(profile, Map.of(
                        "chain", fcm.profileChainNames(),
                        "primarySuccesses", fcm.primarySuccessCount(),
                        "failoverSuccesses", fcm.failoverSuccessCount(),
                        "recentFailovers", fcm.recentFailoverEvents()
                ));
            }
        });
        return report;
    }
}
