package io.crawlj4ai.extraction;

import java.util.Map;

/**
 * Serializable CSS selector definition mapping repeating item containers and relative field selectors.
 */
public record CssSelectorSchema(
        String baseSelector,
        Map<String, String> fieldSelectors
) {
    public boolean isValidStructure() {
        return baseSelector != null && !baseSelector.isBlank()
                && fieldSelectors != null && !fieldSelectors.isEmpty();
    }
}
