package io.crawlj4ai.markdown;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.model.MarkdownResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strips boilerplate DOM nodes, prunes low-density blocks, extracts normalized links,
 * converts clean HTML into LLM-ready Markdown, and computes a SHA-256 fingerprint.
 */
public class DefaultMarkdownGenerator {

    private final FlexmarkHtmlConverter converter = FlexmarkHtmlConverter.builder().build();

    public record ProcessedPage(
            String cleanedHtml,
            MarkdownResult markdown,
            List<String> internalLinks,
            List<String> externalLinks
    ) {}

    public ProcessedPage process(String url, String rawHtml, CrawlerRunConfig config) {
        Document doc = Jsoup.parse(rawHtml, url);

        URI baseUri = URI.create(url);
        Set<String> internal = new LinkedHashSet<>();
        Set<String> external = new LinkedHashSet<>();
        for (Element anchor : doc.select("a[href]")) {
            String absUrl = anchor.absUrl("href").split("#")[0];
            if (absUrl.startsWith("http://") || absUrl.startsWith("https://")) {
                if (absUrl.contains(baseUri.getHost())) {
                    internal.add(absUrl);
                } else {
                    external.add(absUrl);
                }
            }
        }

        for (String tag : config.excludedTags()) {
            doc.select(tag).remove();
        }

        Element scope = doc.selectFirst(config.cssScopeSelector());
        if (scope == null) {
            scope = doc.body();
        }

        String rawMarkdown = converter.convert(scope.html()).trim();

        for (Element block : scope.select("div, p, li, section")) {
            String text = block.text().trim();
            if (!text.isEmpty() && text.split("\\s+").length < config.wordCountThreshold() && block.select("a, img").isEmpty()) {
                block.remove();
            }
        }

        String cleanedHtml = scope.html();
        String fitMarkdown = converter.convert(cleanedHtml).trim();
        String hash = sha256(fitMarkdown);

        return new ProcessedPage(
                cleanedHtml,
                new MarkdownResult(rawMarkdown, fitMarkdown, hash),
                new ArrayList<>(internal),
                new ArrayList<>(external)
        );
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
