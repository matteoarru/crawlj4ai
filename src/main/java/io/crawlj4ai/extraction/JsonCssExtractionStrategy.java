package io.crawlj4ai.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, zero-LLM CSS selector extraction strategy using Jsoup.
 */
public class JsonCssExtractionStrategy implements ExtractionStrategy {

    private final String baseSelector;
    private final Map<String, String> fieldSelectors;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonCssExtractionStrategy(String baseSelector, Map<String, String> fieldSelectors) {
        this.baseSelector = baseSelector;
        this.fieldSelectors = fieldSelectors;
    }

    @Override
    public String extract(String url, String cleanedHtml, String fitMarkdown) throws Exception {
        Document doc = Jsoup.parse(cleanedHtml, url);
        List<Map<String, String>> items = new ArrayList<>();

        for (Element container : doc.select(baseSelector)) {
            Map<String, String> row = new LinkedHashMap<>();
            for (var entry : fieldSelectors.entrySet()) {
                Element el = container.selectFirst(entry.getValue());
                row.put(entry.getKey(), el != null ? el.text().trim() : null);
            }
            items.add(row);
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(items);
    }
}
