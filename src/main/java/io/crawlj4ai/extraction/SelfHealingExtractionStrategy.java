package io.crawlj4ai.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.crawlj4ai.llm.ChatModelRegistry;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hybrid deterministic/LLM extraction strategy that automatically repairs broken CSS selectors.
 *
 * <p>Execution flow:</p>
 * <ol>
 *   <li>Runs active Jsoup CSS selectors from {@link SelectorCache} (or {@code initialSchema}) at zero LLM cost.</li>
 *   <li>If extracted rows fall below {@code minExpectedItems} or lack {@code requiredFields}, acquires a
 *       per-host lock to prevent thundering-herd LLM calls across concurrent workers.</li>
 *   <li>Compacts the HTML DOM, invokes {@link SelectorRepairAgent} via {@link ChatModelRegistry},
 *       verifies that the candidate selectors pass health checks on the live page, and persists them.</li>
 * </ol>
 */
public class SelfHealingExtractionStrategy implements ExtractionStrategy {

    private static final int MAX_HTML_CHARS_FOR_LLM = 18_000;

    private final String schemaName;
    private final CssSelectorSchema initialSchema;
    private final Map<String, String> fieldDescriptions;
    private final Set<String> requiredFields;
    private final int minExpectedItems;
    private final SelectorCache cache;

    private final ChatModelRegistry chatModelRegistry;
    private final String llmProfile;
    private final ChatModel directChatModel;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ReentrantLock> repairLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChatModel, SelectorRepairAgent> agentCache = new ConcurrentHashMap<>();

    public SelfHealingExtractionStrategy(
            String schemaName,
            CssSelectorSchema initialSchema,
            Map<String, String> fieldDescriptions,
            Set<String> requiredFields,
            int minExpectedItems,
            SelectorCache cache,
            ChatModelRegistry chatModelRegistry,
            String llmProfile
    ) {
        if (chatModelRegistry == null) {
            throw new IllegalArgumentException("chatModelRegistry must not be null");
        }
        this.schemaName = schemaName;
        this.initialSchema = initialSchema;
        this.fieldDescriptions = Map.copyOf(fieldDescriptions);
        this.requiredFields = Set.copyOf(requiredFields);
        this.minExpectedItems = Math.max(1, minExpectedItems);
        this.cache = cache != null ? cache : SelectorCache.inMemory();
        this.chatModelRegistry = chatModelRegistry;
        this.llmProfile = (llmProfile == null || llmProfile.isBlank()) ? null : llmProfile.trim();
        this.directChatModel = null;
    }

    public SelfHealingExtractionStrategy(
            String schemaName,
            CssSelectorSchema initialSchema,
            Map<String, String> fieldDescriptions,
            Set<String> requiredFields,
            int minExpectedItems,
            SelectorCache cache,
            ChatModelRegistry chatModelRegistry
    ) {
        this(schemaName, initialSchema, fieldDescriptions, requiredFields, minExpectedItems,
                cache, chatModelRegistry, null);
    }

    public SelfHealingExtractionStrategy(
            String schemaName,
            CssSelectorSchema initialSchema,
            Map<String, String> fieldDescriptions,
            Set<String> requiredFields,
            int minExpectedItems,
            SelectorCache cache,
            ChatModel chatModel
    ) {
        if (chatModel == null) {
            throw new IllegalArgumentException("chatModel must not be null");
        }
        this.schemaName = schemaName;
        this.initialSchema = initialSchema;
        this.fieldDescriptions = Map.copyOf(fieldDescriptions);
        this.requiredFields = Set.copyOf(requiredFields);
        this.minExpectedItems = Math.max(1, minExpectedItems);
        this.cache = cache != null ? cache : SelectorCache.inMemory();
        this.chatModelRegistry = null;
        this.llmProfile = null;
        this.directChatModel = chatModel;
    }

    @Override
    public String extract(String url, String cleanedHtml, String fitMarkdown) throws Exception {
        return extract(url, cleanedHtml, fitMarkdown, null);
    }

    @Override
    public String extract(
            String url,
            String cleanedHtml,
            String fitMarkdown,
            String llmProfileOverride
    ) throws Exception {
        String cacheKey = buildCacheKey(url);
        CssSelectorSchema activeSchema = cache.get(cacheKey).orElse(initialSchema);

        List<Map<String, String>> extracted = runJsoupExtraction(url, cleanedHtml, activeSchema);
        if (isHealthy(extracted)) {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(extracted);
        }

        ReentrantLock lock = repairLocks.computeIfAbsent(cacheKey, k -> new ReentrantLock());
        lock.lock();
        try {
            CssSelectorSchema latestCached = cache.get(cacheKey).orElse(initialSchema);
            if (!latestCached.equals(activeSchema)) {
                List<Map<String, String>> retried = runJsoupExtraction(url, cleanedHtml, latestCached);
                if (isHealthy(retried)) {
                    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(retried);
                }
            }

            SelectorRepairAgent repairAgent = resolveRepairAgent(llmProfileOverride);
            String compactedHtml = compactHtmlForLlm(cleanedHtml);

            CssSelectorSchema candidateSchema = repairAgent.repairSelectors(
                    url,
                    activeSchema.baseSelector(),
                    mapper.writeValueAsString(activeSchema.fieldSelectors()),
                    mapper.writeValueAsString(fieldDescriptions),
                    compactedHtml
            );

            if (candidateSchema == null || !candidateSchema.isValidStructure()) {
                throw new IllegalStateException("LLM returned invalid selector schema for " + url);
            }

            List<Map<String, String>> repairedItems = runJsoupExtraction(url, cleanedHtml, candidateSchema);
            if (!isHealthy(repairedItems)) {
                throw new IllegalStateException(
                        "Repaired selectors failed validation on " + url + ": " + candidateSchema
                );
            }

            cache.put(cacheKey, candidateSchema);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(repairedItems);
        } finally {
            lock.unlock();
        }
    }

    private SelectorRepairAgent resolveRepairAgent(String llmProfileOverride) {
        if (chatModelRegistry == null) {
            return agentCache.computeIfAbsent(
                    directChatModel,
                    m -> AiServices.builder(SelectorRepairAgent.class).chatModel(m).build()
            );
        }

        String effectiveProfile = (llmProfile != null)
                ? llmProfile
                : (llmProfileOverride != null && !llmProfileOverride.isBlank()
                        ? llmProfileOverride.trim()
                        : null);

        Optional<ChatModel> modelOpt = (effectiveProfile == null)
                ? chatModelRegistry.getDefault()
                : chatModelRegistry.get(effectiveProfile);

        ChatModel resolvedModel = modelOpt.orElseThrow(() -> new IllegalStateException(
                "Cannot repair CSS selectors for schema '" + schemaName + "': LLM profile '"
                        + (effectiveProfile != null ? effectiveProfile : "default")
                        + "' is disabled (provider=NONE)."
        ));

        return agentCache.computeIfAbsent(
                resolvedModel,
                m -> AiServices.builder(SelectorRepairAgent.class).chatModel(m).build()
        );
    }

    public Optional<String> configuredLlmProfile() {
        return Optional.ofNullable(llmProfile);
    }

    private List<Map<String, String>> runJsoupExtraction(
            String url,
            String cleanedHtml,
            CssSelectorSchema schema
    ) {
        Document doc = Jsoup.parse(cleanedHtml, url);
        List<Map<String, String>> items = new ArrayList<>();

        for (Element container : doc.select(schema.baseSelector())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (var entry : schema.fieldSelectors().entrySet()) {
                String fieldName = entry.getKey();
                String selector = entry.getValue();

                Element target = ".".equals(selector) ? container : container.selectFirst(selector);
                String value = extractElementValue(target, selector);
                row.put(fieldName, value);
            }
            items.add(row);
        }
        return items;
    }

    private String extractElementValue(Element target, String selector) {
        if (target == null) return null;
        if ("a".equalsIgnoreCase(target.tagName()) && selector.contains("href")) {
            String absHref = target.absUrl("href");
            return absHref.isBlank() ? target.attr("href") : absHref;
        }
        String text = target.text().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isHealthy(List<Map<String, String>> items) {
        if (items.size() < minExpectedItems) {
            return false;
        }
        long validRows = items.stream()
                .filter(row -> requiredFields.stream().allMatch(req -> {
                    String val = row.get(req);
                    return val != null && !val.isBlank();
                }))
                .count();

        return validRows >= Math.max(1, (long) Math.ceil(items.size() * 0.7));
    }

    private String compactHtmlForLlm(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select("svg, path, script, style, noscript, iframe").remove();

        for (Element el : doc.getAllElements()) {
            List<String> toRemove = new ArrayList<>();
            for (Attribute attr : el.attributes()) {
                String key = attr.getKey().toLowerCase();
                String val = attr.getValue();
                if ("style".equals(key) || val.startsWith("data:") || val.length() > 160) {
                    toRemove.add(attr.getKey());
                }
            }
            toRemove.forEach(el::removeAttr);
        }

        String compacted = doc.body().html();
        return compacted.length() > MAX_HTML_CHARS_FOR_LLM
                ? compacted.substring(0, MAX_HTML_CHARS_FOR_LLM) + "\n<!-- truncated -->"
                : compacted;
    }

    private String buildCacheKey(String url) {
        String host = URI.create(url).getHost();
        return (host != null ? host.toLowerCase() : "unknown") + ":" + schemaName;
    }

    public static Builder builder(String schemaName, CssSelectorSchema initialSchema) {
        return new Builder(schemaName, initialSchema);
    }

    public static final class Builder {
        private final String schemaName;
        private final CssSelectorSchema initialSchema;
        private Map<String, String> fieldDescriptions = Map.of();
        private Set<String> requiredFields = Set.of();
        private int minExpectedItems = 1;
        private SelectorCache cache = SelectorCache.inMemory();
        private ChatModelRegistry chatModelRegistry;
        private String llmProfile;

        private Builder(String schemaName, CssSelectorSchema initialSchema) {
            this.schemaName = schemaName;
            this.initialSchema = initialSchema;
        }

        public Builder fieldDescriptions(Map<String, String> descriptions) {
            this.fieldDescriptions = descriptions;
            return this;
        }

        public Builder requiredFields(Set<String> requiredFields) {
            this.requiredFields = requiredFields;
            return this;
        }

        public Builder minExpectedItems(int minExpectedItems) {
            this.minExpectedItems = minExpectedItems;
            return this;
        }

        public Builder cache(SelectorCache cache) {
            this.cache = cache;
            return this;
        }

        public Builder chatModelRegistry(ChatModelRegistry registry) {
            this.chatModelRegistry = registry;
            return this;
        }

        public Builder llmProfile(String llmProfile) {
            this.llmProfile = llmProfile;
            return this;
        }

        public SelfHealingExtractionStrategy build() {
            return new SelfHealingExtractionStrategy(
                    schemaName,
                    initialSchema,
                    fieldDescriptions,
                    requiredFields,
                    minExpectedItems,
                    cache,
                    chatModelRegistry,
                    llmProfile
            );
        }
    }
}
