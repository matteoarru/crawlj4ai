package io.crawlj4ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.core.CrawlJ4Ai;
import io.crawlj4ai.diff.SemanticDiffResult;
import io.crawlj4ai.extraction.CssSelectorSchema;
import io.crawlj4ai.extraction.ExtractionStrategy;
import io.crawlj4ai.extraction.JsonCssExtractionStrategy;
import io.crawlj4ai.extraction.LlmExtractionStrategy;
import io.crawlj4ai.extraction.SelectorCache;
import io.crawlj4ai.extraction.SelfHealingExtractionStrategy;
import io.crawlj4ai.llm.ChatModelRegistry;
import io.crawlj4ai.llm.LlmProfileConfig;
import io.crawlj4ai.model.CrawlResult;
import io.crawlj4ai.scheduler.AdaptiveRefreshScheduler;
import io.crawlj4ai.scheduler.SchedulerPolicy;
import io.crawlj4ai.scheduler.SearchMonitorSpec;
import io.crawlj4ai.scheduler.SearchMonitorState;
import io.crawlj4ai.scheduler.UrlScheduleState;
import io.crawlj4ai.search.BraveSearchQuery;
import io.crawlj4ai.search.BraveSearchQuery.Freshness;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/scout")
public class ScoutRestController {

    private final CrawlJ4Ai crawler;
    private final AdaptiveRefreshScheduler scheduler;
    private final ScoutAlertBroadcaster alertBroadcaster;
    private final ChatModelRegistry chatModelRegistry;
    private final SelectorCache selectorCache;
    private final ObjectMapper jsonParser = new ObjectMapper();

    public ScoutRestController(
            CrawlJ4Ai crawler,
            AdaptiveRefreshScheduler scheduler,
            ScoutAlertBroadcaster alertBroadcaster,
            ChatModelRegistry chatModelRegistry,
            SelectorCache selectorCache
    ) {
        this.crawler = crawler;
        this.scheduler = scheduler;
        this.alertBroadcaster = alertBroadcaster;
        this.chatModelRegistry = chatModelRegistry;
        this.selectorCache = selectorCache;
    }

    public record ExtractionSpecRequest(
            String schemaName,
            String baseSelector,
            Map<String, String> fieldSelectors,
            Map<String, String> fieldDescriptions,
            Set<String> requiredFields,
            @Min(1) Integer minExpectedItems,
            Boolean selfHealing,
            String llmInstruction,
            String jsonSchemaExample
    ) {}

    public record CrawlRequest(
            @NotBlank String url,
            @Min(0) Integer wordCountThreshold,
            List<String> excludedTags,
            String cssScopeSelector,
            String waitForSelector,
            Boolean bypassCache,
            String llmProfile,
            String monitoringFocus,
            Boolean includeHtml,
            @Valid ExtractionSpecRequest extraction
    ) {}

    public record CrawlResponse(
            String url,
            boolean success,
            int statusCode,
            boolean skippedBrowserViaHttp304,
            boolean changedFromPrevious,
            String llmProfileUsed,
            String fitMarkdown,
            String rawMarkdown,
            String cleanedHtml,
            Object extractedData,
            String extractedJson,
            List<String> internalLinks,
            List<String> externalLinks,
            SemanticDiffResult semanticDiff,
            String errorMessage
    ) {}

    public record ExtractRequest(
            @NotBlank String url,
            String schemaName,
            String baseSelector,
            Map<String, String> fieldSelectors,
            Map<String, String> fieldDescriptions,
            Set<String> requiredFields,
            @Min(1) Integer minExpectedItems,
            Boolean selfHealing,
            String llmInstruction,
            String jsonSchemaExample,
            String llmProfile,
            String cssScopeSelector,
            String waitForSelector
    ) {}

    public record ExtractResponse(
            String url,
            boolean success,
            int statusCode,
            String schemaName,
            boolean selfHealingEnabled,
            String llmProfileUsed,
            CssSelectorSchema activeSelectorSchema,
            Object extractedData,
            String extractedJson,
            String errorMessage
    ) {}

    public record RegisterSearchMonitorRequest(
            @NotBlank String monitorId,
            @NotBlank String query,
            @Min(1) @Max(20) Integer count,
            Freshness freshness,
            String country,
            @Min(1) @Max(500) Integer maxTrackedUrls,
            List<String> excludeDomains,
            Boolean volatileTarget,
            Boolean triggerImmediately,
            String llmProfile,
            String monitoringFocus
    ) {}

    public record DiscoveredUrlView(
            String monitorId,
            String url,
            UrlScheduleState scheduleState
    ) {}

    public record ScheduleOverviewResponse(
            Collection<SearchMonitorState> searchMonitors,
            Collection<UrlScheduleState> monitoredUrls
    ) {}

    @PostMapping("/crawl")
    public ResponseEntity<CrawlResponse> crawlUrl(@Valid @RequestBody CrawlRequest req) {
        String normalizedUrl = validateHttpUrl(req.url());
        String resolvedProfile = validateAndNormalizeProfile(req.llmProfile());

        CrawlerRunConfig.Builder runBuilder = CrawlerRunConfig.builder();
        if (req.wordCountThreshold() != null) {
            runBuilder.wordCountThreshold(req.wordCountThreshold());
        }
        if (req.excludedTags() != null) {
            runBuilder.excludedTags(req.excludedTags());
        }
        if (req.cssScopeSelector() != null && !req.cssScopeSelector().isBlank()) {
            runBuilder.cssScopeSelector(req.cssScopeSelector().trim());
        }
        if (req.waitForSelector() != null && !req.waitForSelector().isBlank()) {
            runBuilder.waitForSelector(req.waitForSelector().trim());
        }
        if (resolvedProfile != null) {
            runBuilder.llmProfile(resolvedProfile);
        }
        if (req.monitoringFocus() != null && !req.monitoringFocus().isBlank()) {
            runBuilder.monitoringFocus(req.monitoringFocus().trim());
        }

        boolean effectiveBypassCache = req.bypassCache() != null
                ? req.bypassCache()
                : (req.extraction() != null);
        runBuilder.bypassCache(effectiveBypassCache);

        if (req.extraction() != null) {
            runBuilder.extractionStrategy(buildExtractionStrategy(req.extraction(), resolvedProfile));
        }

        CrawlResult result = crawler.crawl(normalizedUrl, runBuilder.build());
        Object parsedData = parseJsonSafely(result.extractedJson());
        boolean includeHtml = Boolean.TRUE.equals(req.includeHtml());

        CrawlResponse response = new CrawlResponse(
                result.url(),
                result.success(),
                result.statusCode(),
                result.skippedBrowserViaHttp304(),
                result.changedFromPrevious(),
                resolvedProfile != null ? resolvedProfile : "default",
                result.markdown() != null ? result.markdown().fitMarkdown() : null,
                result.markdown() != null ? result.markdown().rawMarkdown() : null,
                includeHtml ? result.cleanedHtml() : null,
                parsedData,
                result.extractedJson(),
                result.internalLinks(),
                result.externalLinks(),
                result.semanticDiff(),
                result.errorMessage()
        );

        HttpStatus httpStatus = result.success() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(httpStatus).body(response);
    }

    @PostMapping("/extract")
    public ResponseEntity<ExtractResponse> extractFromUrl(@Valid @RequestBody ExtractRequest req) {
        String normalizedUrl = validateHttpUrl(req.url());
        String resolvedProfile = validateAndNormalizeProfile(req.llmProfile());

        ExtractionSpecRequest spec = new ExtractionSpecRequest(
                req.schemaName(),
                req.baseSelector(),
                req.fieldSelectors(),
                req.fieldDescriptions(),
                req.requiredFields(),
                req.minExpectedItems(),
                req.selfHealing(),
                req.llmInstruction(),
                req.jsonSchemaExample()
        );

        ExtractionStrategy strategy = buildExtractionStrategy(spec, resolvedProfile);

        CrawlerRunConfig.Builder runBuilder = CrawlerRunConfig.builder()
                .bypassCache(true)
                .extractionStrategy(strategy);

        if (req.cssScopeSelector() != null && !req.cssScopeSelector().isBlank()) {
            runBuilder.cssScopeSelector(req.cssScopeSelector().trim());
        }
        if (req.waitForSelector() != null && !req.waitForSelector().isBlank()) {
            runBuilder.waitForSelector(req.waitForSelector().trim());
        }
        if (resolvedProfile != null) {
            runBuilder.llmProfile(resolvedProfile);
        }

        CrawlResult result = crawler.crawl(normalizedUrl, runBuilder.build());
        String effectiveSchemaName = (req.schemaName() == null || req.schemaName().isBlank())
                ? "default_schema"
                : req.schemaName().trim();
        boolean selfHealingEnabled = req.selfHealing() == null || req.selfHealing();

        CssSelectorSchema activeSchema = lookupActiveSchema(
                normalizedUrl,
                effectiveSchemaName,
                req.baseSelector(),
                req.fieldSelectors()
        );

        ExtractResponse response = new ExtractResponse(
                result.url(),
                result.success() && result.extractedJson() != null,
                result.statusCode(),
                effectiveSchemaName,
                selfHealingEnabled,
                resolvedProfile != null ? resolvedProfile : "default",
                activeSchema,
                parseJsonSafely(result.extractedJson()),
                result.extractedJson(),
                result.errorMessage()
        );

        HttpStatus httpStatus = response.success() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(httpStatus).body(response);
    }

    @PostMapping("/monitors")
    public ResponseEntity<SearchMonitorState> registerSearchMonitor(
            @Valid @RequestBody RegisterSearchMonitorRequest req
    ) {
        String resolvedProfile = validateAndNormalizeProfile(req.llmProfile());

        BraveSearchQuery.Builder queryBuilder = BraveSearchQuery.builder(req.query())
                .count(req.count() != null ? req.count() : 10);

        if (req.freshness() != null) {
            queryBuilder.freshness(req.freshness());
        }
        if (req.country() != null && !req.country().isBlank()) {
            queryBuilder.country(req.country().trim());
        }

        Set<String> blockedDomains = req.excludeDomains() == null
                ? Set.of()
                : req.excludeDomains().stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());

        SchedulerPolicy targetPolicy = Boolean.TRUE.equals(req.volatileTarget())
                ? SchedulerPolicy.volatileTarget()
                : SchedulerPolicy.defaults();

        SearchMonitorSpec spec = SearchMonitorSpec.builder(req.monitorId().trim(), queryBuilder.build())
                .targetRunConfig(CrawlerRunConfig.builder().build())
                .discoveryPolicy(SearchMonitorSpec.defaultDiscoveryPolicy())
                .targetUrlPolicy(targetPolicy)
                .maxTrackedUrls(req.maxTrackedUrls() != null ? req.maxTrackedUrls() : 100)
                .hitFilter(hit -> blockedDomains.stream().noneMatch(d -> hit.url().toLowerCase().contains(d)))
                .llmProfile(resolvedProfile)
                .monitoringFocus(req.monitoringFocus())
                .build();

        try {
            scheduler.registerSearchMonitor(spec);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        if (Boolean.TRUE.equals(req.triggerImmediately())) {
            scheduler.triggerSearchNow(spec.monitorId());
        }

        SearchMonitorState state = scheduler.getSearchMonitorState(spec.monitorId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Monitor state missing"
                ));
        return ResponseEntity.status(HttpStatus.CREATED).body(state);
    }

    @PostMapping("/monitors/{monitorId}/trigger")
    public ResponseEntity<Void> triggerSearchNow(@PathVariable String monitorId) {
        try {
            scheduler.triggerSearchNow(monitorId);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/monitors/{monitorId}")
    public ResponseEntity<Void> unregisterSearchMonitor(
            @PathVariable String monitorId,
            @RequestParam(defaultValue = "false") boolean deleteUrls
    ) {
        scheduler.unregisterSearchMonitor(monitorId, deleteUrls);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/monitors/{monitorId}/urls")
    public List<DiscoveredUrlView> listDiscoveredUrlsForMonitor(@PathVariable String monitorId) {
        SearchMonitorState monitor = scheduler.getSearchMonitorState(monitorId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown monitorId: " + monitorId
                ));

        return monitor.knownUrls().stream()
                .map(url -> new DiscoveredUrlView(
                        monitorId,
                        url,
                        scheduler.getScheduleState(url).orElse(null)
                ))
                .toList();
    }

    @GetMapping("/urls")
    public List<DiscoveredUrlView> listAllDiscoveredUrls() {
        return scheduler.getAllSearchMonitors().stream()
                .flatMap(monitor -> monitor.knownUrls().stream()
                        .map(url -> new DiscoveredUrlView(
                                monitor.monitorId(),
                                url,
                                scheduler.getScheduleState(url).orElse(null)
                        )))
                .toList();
    }

    @GetMapping("/schedules")
    public ScheduleOverviewResponse inspectAllSchedules() {
        return new ScheduleOverviewResponse(
                scheduler.getAllSearchMonitors(),
                scheduler.getAllSchedules()
        );
    }

    @GetMapping("/schedules/url")
    public UrlScheduleState inspectUrlSchedule(@RequestParam String url) {
        return scheduler.getScheduleState(url)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "URL is not currently scheduled: " + url
                ));
    }

    @PostMapping("/schedules/url/trigger")
    public ResponseEntity<Void> triggerUrlCrawlNow(@RequestParam String url) {
        try {
            scheduler.triggerNow(url);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(value = "/alerts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAlerts(@RequestParam(defaultValue = "true") boolean replay) {
        return alertBroadcaster.subscribe(replay);
    }

    @GetMapping("/alerts/recent")
    public List<ScoutAlertEvent> getRecentAlerts() {
        return alertBroadcaster.getRecentEvents();
    }

    @GetMapping("/llm/profiles")
    public Map<String, Object> listLlmProfiles() {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        chatModelRegistry.allConfigs().forEach((name, cfg) -> sanitized.put(name, Map.of(
                "provider", cfg.provider(),
                "baseUrl", cfg.baseUrl() != null ? cfg.baseUrl() : "",
                "modelName", cfg.modelName() != null ? cfg.modelName() : "",
                "numCtx", cfg.numCtx() != null ? cfg.numCtx() : 0,
                "fallbackProfiles", cfg.fallbackProfiles(),
                "enabled", cfg.isEnabled()
        )));
        return sanitized;
    }

    @PostMapping("/llm/profiles/{profileName}")
    public ResponseEntity<Void> registerLlmProfile(
            @PathVariable String profileName,
            @RequestBody LlmProfileConfig config
    ) {
        chatModelRegistry.registerProfile(profileName, config);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/llm/failovers")
    public Map<String, Map<String, Object>> inspectLlmFailovers() {
        return chatModelRegistry.getFailoverDiagnostics();
    }

    private ExtractionStrategy buildExtractionStrategy(
            ExtractionSpecRequest spec,
            String resolvedProfile
    ) {
        boolean hasCssSelectors = spec.baseSelector() != null && !spec.baseSelector().isBlank()
                && spec.fieldSelectors() != null && !spec.fieldSelectors().isEmpty();
        boolean hasLlmInstruction = spec.llmInstruction() != null && !spec.llmInstruction().isBlank();

        if (!hasCssSelectors && !hasLlmInstruction) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Extraction requires either (baseSelector + fieldSelectors) or llmInstruction."
            );
        }

        if (!hasCssSelectors) {
            ChatModel model = (resolvedProfile == null
                    ? chatModelRegistry.getDefault()
                    : chatModelRegistry.get(resolvedProfile))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Requested LLM profile is disabled (provider=NONE); cannot run LLM extraction."
                    ));
            String schemaExample = (spec.jsonSchemaExample() == null || spec.jsonSchemaExample().isBlank())
                    ? "{\"items\": []}"
                    : spec.jsonSchemaExample();
            return new LlmExtractionStrategy(model, spec.llmInstruction().trim(), schemaExample);
        }

        if (Boolean.FALSE.equals(spec.selfHealing())) {
            return new JsonCssExtractionStrategy(spec.baseSelector().trim(), spec.fieldSelectors());
        }

        String schemaName = (spec.schemaName() == null || spec.schemaName().isBlank())
                ? "default_schema"
                : spec.schemaName().trim();

        CssSelectorSchema initialSchema = new CssSelectorSchema(
                spec.baseSelector().trim(),
                spec.fieldSelectors()
        );

        Map<String, String> descriptions = (spec.fieldDescriptions() != null && !spec.fieldDescriptions().isEmpty())
                ? spec.fieldDescriptions()
                : spec.fieldSelectors().keySet().stream()
                        .collect(Collectors.toMap(k -> k, k -> "Extracted value for field '" + k + "'"));

        Set<String> required = (spec.requiredFields() != null && !spec.requiredFields().isEmpty())
                ? spec.requiredFields()
                : spec.fieldSelectors().keySet();

        return SelfHealingExtractionStrategy.builder(schemaName, initialSchema)
                .fieldDescriptions(descriptions)
                .requiredFields(required)
                .minExpectedItems(spec.minExpectedItems() != null ? spec.minExpectedItems() : 1)
                .cache(selectorCache)
                .chatModelRegistry(chatModelRegistry)
                .llmProfile(resolvedProfile)
                .build();
    }

    private CssSelectorSchema lookupActiveSchema(
            String url,
            String schemaName,
            String fallbackBaseSelector,
            Map<String, String> fallbackFieldSelectors
    ) {
        if (fallbackBaseSelector == null || fallbackFieldSelectors == null) {
            return null;
        }
        String host = URI.create(url).getHost();
        String cacheKey = (host != null ? host.toLowerCase() : "unknown") + ":" + schemaName;
        return selectorCache.get(cacheKey)
                .orElseGet(() -> new CssSelectorSchema(fallbackBaseSelector, fallbackFieldSelectors));
    }

    private String validateAndNormalizeProfile(String rawProfile) {
        if (rawProfile == null || rawProfile.isBlank()) {
            return null;
        }
        String trimmed = rawProfile.trim();
        if (!chatModelRegistry.availableProfiles().contains(trimmed)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown llmProfile '" + trimmed + "'. Available profiles: "
                            + chatModelRegistry.availableProfiles()
            );
        }
        return trimmed;
    }

    private static String validateHttpUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL must use http or https scheme.");
            }
            return uri.toString();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL: " + rawUrl, ex);
        }
    }

    private Object parseJsonSafely(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return jsonParser.readValue(rawJson, Object.class);
        } catch (Exception ignored) {
            return rawJson;
        }
    }
}
