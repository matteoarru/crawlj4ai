package io.crawlj4ai.markdown;

import io.crawlj4ai.config.CrawlerRunConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMarkdownGeneratorTest {

    private final DefaultMarkdownGenerator generator = new DefaultMarkdownGenerator();

    @Test
    @DisplayName("Strips excluded tags, prunes low-word-count boilerplate, separates links, and computes deterministic SHA-256")
    void processesHtmlIntoCleanFitMarkdownAndSeparatesLinks() {
        String html = """
                <html>
                  <head><script>var tracking = true;</script></head>
                  <body>
                    <nav>
                      <a href="/docs/intro#section1">Intro</a>
                      <a href="https://external.example.org/partner">Partner</a>
                    </nav>
                    <main>
                      <h1>Release 2.0 Notes</h1>
                      <div>Ok</div>
                      <p>This release introduces adaptive Poisson refresh scheduling and HTTP 304 conditional checks.</p>
                    </main>
                    <footer>Copyright 2026</footer>
                  </body>
                </html>
                """;

        CrawlerRunConfig config = CrawlerRunConfig.builder()
                .wordCountThreshold(4)
                .excludedTags(List.of("nav", "footer", "script"))
                .cssScopeSelector("main")
                .build();

        var first = generator.process("https://scout.example.com/releases", html, config);
        var second = generator.process("https://scout.example.com/releases", html, config);

        assertEquals(List.of("https://scout.example.com/docs/intro"), first.internalLinks());
        assertEquals(List.of("https://external.example.org/partner"), first.externalLinks());

        assertTrue(first.markdown().rawMarkdown().contains("Ok"));
        assertFalse(first.markdown().fitMarkdown().contains("Ok"));
        assertTrue(first.markdown().fitMarkdown().contains("adaptive Poisson refresh scheduling"));
        assertFalse(first.markdown().fitMarkdown().contains("Copyright 2026"));

        assertEquals(64, first.markdown().sha256Hash().length());
        assertEquals(first.markdown().sha256Hash(), second.markdown().sha256Hash());
    }

    @Test
    @DisplayName("Falls back gracefully to document body when cssScopeSelector matches nothing")
    void fallsBackToBodyWhenCssScopeSelectorMissing() {
        String html = "<html><body><p>Fallback body paragraph with sufficient words.</p></body></html>";
        CrawlerRunConfig config = CrawlerRunConfig.builder()
                .cssScopeSelector("section.non-existent")
                .wordCountThreshold(3)
                .build();

        var processed = generator.process("https://scout.example.com", html, config);
        assertTrue(processed.markdown().fitMarkdown().contains("Fallback body paragraph"));
    }
}
