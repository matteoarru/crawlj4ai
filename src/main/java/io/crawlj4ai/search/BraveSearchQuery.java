package io.crawlj4ai.search;

import java.util.Optional;

/**
 * Query parameters for Brave Web Search including freshness filters, localization, and Goggles.
 */
public record BraveSearchQuery(
        String query,
        int count,
        int offset,
        Optional<String> freshness,
        Optional<String> country,
        Optional<String> searchLang,
        Optional<String> safeSearch,
        Optional<String> goggles,
        boolean extraSnippets
) {
    public enum Freshness {
        PAST_DAY("pd"),
        PAST_WEEK("pw"),
        PAST_MONTH("pm"),
        PAST_YEAR("py");

        private final String code;
        Freshness(String code) { this.code = code; }
        public String code() { return code; }
    }

    public BraveSearchQuery {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        if (count < 1 || count > 20) {
            throw new IllegalArgumentException("count must be between 1 and 20");
        }
        if (offset < 0 || offset > 9) {
            throw new IllegalArgumentException("offset must be between 0 and 9");
        }
    }

    public static BraveSearchQuery of(String query) {
        return builder(query).build();
    }

    public static BraveSearchQuery of(String query, int count) {
        return builder(query).count(count).build();
    }

    public static Builder builder(String query) {
        return new Builder(query);
    }

    public static final class Builder {
        private final String query;
        private int count = 10;
        private int offset = 0;
        private String freshness = null;
        private String country = null;
        private String searchLang = null;
        private String safeSearch = "moderate";
        private String goggles = null;
        private boolean extraSnippets = true;

        private Builder(String query) {
            this.query = query;
        }

        public Builder count(int count) { this.count = count; return this; }
        public Builder offset(int offset) { this.offset = offset; return this; }
        public Builder freshness(Freshness preset) { this.freshness = preset.code(); return this; }
        public Builder freshnessRange(String startDateIso, String endDateIso) {
            this.freshness = startDateIso + "to" + endDateIso;
            return this;
        }
        public Builder country(String countryCode) { this.country = countryCode; return this; }
        public Builder searchLang(String langCode) { this.searchLang = langCode; return this; }
        public Builder safeSearch(String safeSearch) { this.safeSearch = safeSearch; return this; }
        public Builder goggles(String goggleUrlOrDef) { this.goggles = goggleUrlOrDef; return this; }
        public Builder extraSnippets(boolean extraSnippets) { this.extraSnippets = extraSnippets; return this; }

        public BraveSearchQuery build() {
            return new BraveSearchQuery(
                    query,
                    count,
                    offset,
                    Optional.ofNullable(freshness),
                    Optional.ofNullable(country),
                    Optional.ofNullable(searchLang),
                    Optional.ofNullable(safeSearch),
                    Optional.ofNullable(goggles),
                    extraSnippets
            );
        }
    }
}
