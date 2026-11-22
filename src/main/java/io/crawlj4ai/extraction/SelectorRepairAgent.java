package io.crawlj4ai.extraction;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI service interface that synthesizes repaired Jsoup CSS selectors from compacted HTML.
 */
public interface SelectorRepairAgent {

    @SystemMessage("""
            You are an expert web scraping engineer specializing in resilient Jsoup CSS selectors.
            Given a webpage's HTML structure, broken CSS selectors, and semantic descriptions of target fields,
            generate updated, minimal, resilient CSS selectors that match the repeating items on the page.

            Rules:
            1. Prefer semantic tags, stable attributes (data-*, aria-*, role), or structural classes over fragile hashed utility classes.
            2. `baseSelector` must match each repeating item container on the page.
            3. Each entry in `fieldSelectors` must be a valid Jsoup CSS selector relative to `baseSelector` (use "." or self-reference if the text is directly on the base element).
            4. Keep every field key identical to the requested field names.
            """)
    @UserMessage("""
            Target URL: {{url}}

            Previous broken baseSelector: {{brokenBaseSelector}}
            Previous broken fieldSelectors: {{brokenFieldSelectors}}

            Target fields and semantic descriptions:
            {{fieldDescriptions}}

            Compacted HTML snippet:
            ```html
            {{htmlSnippet}}
            ```
            """)
    CssSelectorSchema repairSelectors(
            @V("url") String url,
            @V("brokenBaseSelector") String brokenBaseSelector,
            @V("brokenFieldSelectors") String brokenFieldSelectors,
            @V("fieldDescriptions") String fieldDescriptions,
            @V("htmlSnippet") String htmlSnippet
    );
}
