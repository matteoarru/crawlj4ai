package io.crawlj4ai.diff;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI service interface for evaluating compact Markdown diffs.
 */
public interface SemanticDiffAgent {

    @SystemMessage("""
            You are a precision change-detection analyst for an automated web monitoring system.
            Your job is to inspect a Markdown diff between two snapshots of the same URL and determine
            whether a SUBSTANTIVE change occurred.

            Classify as NON-SUBSTANTIVE (substantive = false, category = COSMETIC_OR_NOISE) if the diff only involves:
            - Dynamic timestamps, relative times ("2 hours ago", "Updated today"), or copyright years
            - Rotating promotional banners, cookie notices, A/B test phrasing tweaks, or navigation links
            - View/like/comment counters, pagination numbers, or minor markdown/punctuation formatting

            Classify as SUBSTANTIVE (substantive = true) if the diff involves:
            - New articles, releases, announcements, or product entries
            - Changes to pricing, plans, quotas, deadlines, API specs, or legal/policy terms
            - Meaningful edits or deletions of existing factual statements

            Respect the user's monitoring focus criteria when scoring significance.
            """)
    @UserMessage("""
            URL: {{url}}
            Monitoring focus: {{monitoringFocus}}

            Unified Markdown Diff (- removed, + added):
            ```diff
            {{markdownDiff}}
            ```
            """)
    SemanticDiffResult evaluateDiff(
            @V("url") String url,
            @V("monitoringFocus") String monitoringFocus,
            @V("markdownDiff") String markdownDiff
    );
}
