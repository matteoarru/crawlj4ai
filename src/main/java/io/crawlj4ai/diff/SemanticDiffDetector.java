package io.crawlj4ai.diff;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.crawlj4ai.diff.SemanticDiffResult.ChangeCategory;
import io.crawlj4ai.llm.ChatModelRegistry;
import io.crawlj4ai.model.MarkdownResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three-stage semantic change detector that separates genuine factual updates from cosmetic HTML noise.
 *
 * <p>Stage 1 short-circuits immediately when the page's SHA-256 {@code fitMarkdown} hash matches the
 * stored {@link PageSnapshot}. Stage 2 computes a compact LCS line diff and suppresses whitespace-only
 * shifts. Stage 3 sends only the modified hunks to a profile-routed {@link SemanticDiffAgent} to
 * classify significance and suppress timestamps, banners, and view counters.</p>
 */
public class SemanticDiffDetector {

    private static final String DEFAULT_FOCUS =
            "Any meaningful factual, product, pricing, documentation, or news updates.";

    private final SnapshotStore snapshotStore;
    private final ChatModelRegistry chatModelRegistry;
    private final ChatModel directChatModel;
    private final String defaultMonitoringFocus;
    private final boolean updateBaselineOnNoise;
    private final ConcurrentHashMap<ChatModel, SemanticDiffAgent> agentCache = new ConcurrentHashMap<>();

    public SemanticDiffDetector(
            SnapshotStore snapshotStore,
            ChatModelRegistry chatModelRegistry,
            String defaultMonitoringFocus,
            boolean updateBaselineOnNoise
    ) {
        this.snapshotStore = snapshotStore;
        this.chatModelRegistry = chatModelRegistry;
        this.directChatModel = null;
        this.defaultMonitoringFocus = normalizeFocus(defaultMonitoringFocus);
        this.updateBaselineOnNoise = updateBaselineOnNoise;
    }

    public SemanticDiffDetector(
            SnapshotStore snapshotStore,
            ChatModelRegistry chatModelRegistry,
            String defaultMonitoringFocus
    ) {
        this(snapshotStore, chatModelRegistry, defaultMonitoringFocus, true);
    }

    public SemanticDiffDetector(
            SnapshotStore snapshotStore,
            ChatModel chatModel,
            String defaultMonitoringFocus,
            boolean updateBaselineOnNoise
    ) {
        this.snapshotStore = snapshotStore;
        this.chatModelRegistry = null;
        this.directChatModel = chatModel;
        this.defaultMonitoringFocus = normalizeFocus(defaultMonitoringFocus);
        this.updateBaselineOnNoise = updateBaselineOnNoise;
    }

    public SemanticDiffDetector(
            SnapshotStore snapshotStore,
            ChatModel chatModel,
            String defaultMonitoringFocus
    ) {
        this(snapshotStore, chatModel, defaultMonitoringFocus, true);
    }

    public SemanticDiffResult detect(String url, MarkdownResult currentMarkdown) {
        return detect(url, currentMarkdown, null, null);
    }

    public SemanticDiffResult detect(String url, MarkdownResult currentMarkdown, String llmProfile) {
        return detect(url, currentMarkdown, llmProfile, null);
    }

    public SemanticDiffResult detect(
            String url,
            MarkdownResult currentMarkdown,
            String llmProfile,
            String monitoringFocusOverride
    ) {
        Optional<PageSnapshot> previousOpt = snapshotStore.get(url);

        if (previousOpt.isEmpty()) {
            snapshotStore.put(PageSnapshot.of(url, currentMarkdown.sha256Hash(), currentMarkdown.fitMarkdown()));
            return SemanticDiffResult.initialCapture();
        }

        PageSnapshot previous = previousOpt.get();

        if (previous.sha256Hash().equals(currentMarkdown.sha256Hash())) {
            return SemanticDiffResult.unchanged();
        }

        String compactDiff = MarkdownDiffComputer.computeCompactDiff(
                previous.fitMarkdown(),
                currentMarkdown.fitMarkdown()
        );
        if (compactDiff.isEmpty()) {
            if (updateBaselineOnNoise) {
                snapshotStore.put(PageSnapshot.of(url, currentMarkdown.sha256Hash(), currentMarkdown.fitMarkdown()));
            }
            return SemanticDiffResult.whitespaceOnlyNoise();
        }

        Optional<SemanticDiffAgent> agentOpt = resolveAgent(llmProfile);
        if (agentOpt.isEmpty()) {
            snapshotStore.put(PageSnapshot.of(url, currentMarkdown.sha256Hash(), currentMarkdown.fitMarkdown()));
            return new SemanticDiffResult(
                    true,
                    ChangeCategory.CONTENT_MODIFIED,
                    0.75,
                    "Content changed (deterministic diff; LLM profile disabled).",
                    List.of()
            );
        }

        String effectiveFocus = (monitoringFocusOverride != null && !monitoringFocusOverride.isBlank())
                ? monitoringFocusOverride.trim()
                : defaultMonitoringFocus;

        SemanticDiffResult evaluation = agentOpt.get().evaluateDiff(url, effectiveFocus, compactDiff);

        if (evaluation.substantive() || updateBaselineOnNoise) {
            snapshotStore.put(PageSnapshot.of(url, currentMarkdown.sha256Hash(), currentMarkdown.fitMarkdown()));
        }

        return evaluation;
    }

    private Optional<SemanticDiffAgent> resolveAgent(String llmProfile) {
        Optional<ChatModel> modelOpt;
        if (chatModelRegistry != null) {
            modelOpt = (llmProfile == null || llmProfile.isBlank())
                    ? chatModelRegistry.getDefault()
                    : chatModelRegistry.get(llmProfile.trim());
        } else {
            modelOpt = Optional.ofNullable(directChatModel);
        }

        return modelOpt.map(model -> agentCache.computeIfAbsent(
                model,
                m -> AiServices.builder(SemanticDiffAgent.class).chatModel(m).build()
        ));
    }

    private static String normalizeFocus(String focus) {
        return (focus == null || focus.isBlank()) ? DEFAULT_FOCUS : focus.trim();
    }
}
