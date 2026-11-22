package io.crawlj4ai.example;

import io.crawlj4ai.config.BrowserConfig;
import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.core.CrawlJ4Ai;
import io.crawlj4ai.extraction.JsonCssExtractionStrategy;
import io.crawlj4ai.foraging.AdaptiveForager;
import io.crawlj4ai.model.CrawlResult;

import java.util.List;
import java.util.Map;

public class ScoutExample {
    public static void main(String[] args) {
        BrowserConfig browserConfig = BrowserConfig.builder()
                .headless(true)
                .timeoutMs(20_000)
                .build();

        var cssExtractor = new JsonCssExtractionStrategy(
                "article",
                Map.of("title", "h2, h3", "summary", "p")
        );

        CrawlerRunConfig runConfig = CrawlerRunConfig.builder()
                .wordCountThreshold(6)
                .extractionStrategy(cssExtractor)
                .build();

        try (CrawlJ4Ai crawler = new CrawlJ4Ai(browserConfig, 4)) {
            CrawlResult firstRun = crawler.crawl("https://news.ycombinator.com", runConfig);
            System.out.println("Changed on first run: " + firstRun.changedFromPrevious());

            AdaptiveForager forager = new AdaptiveForager(crawler, 5, 0.25);
            List<CrawlResult> relevantPages = forager.forage(
                    "https://playwright.dev/java/docs/intro",
                    "browser context isolation parallel",
                    CrawlerRunConfig.builder().build()
            );
            System.out.println("Foraged relevant pages: " + relevantPages.size());
        }
    }
}
