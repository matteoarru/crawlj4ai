package io.crawlj4ai.foraging;

import io.crawlj4ai.config.CrawlerRunConfig;
import io.crawlj4ai.core.CrawlJ4Ai;
import io.crawlj4ai.model.CrawlResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adaptive information forager that prioritizes outbound links by goal relevance and stops
 * automatically once marginal information gain saturates.
 */
public class AdaptiveForager {

    private record ScoredUrl(String url, double score) {}

    private final CrawlJ4Ai crawler;
    private final int maxPages;
    private final double minRelevanceThreshold;

    public AdaptiveForager(CrawlJ4Ai crawler, int maxPages, double minRelevanceThreshold) {
        this.crawler = crawler;
        this.maxPages = maxPages;
        this.minRelevanceThreshold = minRelevanceThreshold;
    }

    public List<CrawlResult> forage(String seedUrl, String goalQuery, CrawlerRunConfig runConfig) {
        Set<String> queryTerms = tokenize(goalQuery);
        Set<String> visited = new HashSet<>();
        PriorityQueue<ScoredUrl> frontier = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredUrl::score).reversed()
        );

        frontier.add(new ScoredUrl(seedUrl, 1.0));
        List<CrawlResult> collected = new ArrayList<>();
        int consecutiveLowGainPages = 0;

        while (!frontier.isEmpty() && collected.size() < maxPages) {
            ScoredUrl next = frontier.poll();
            if (!visited.add(next.url())) continue;

            CrawlResult result = crawler.crawl(next.url(), runConfig);
            if (!result.success()) continue;

            double contentScore = relevanceScore(result.markdown().fitMarkdown(), queryTerms);
            if (contentScore >= minRelevanceThreshold || next.url().equals(seedUrl)) {
                collected.add(result);
                consecutiveLowGainPages = 0;
            } else {
                consecutiveLowGainPages++;
            }

            if (consecutiveLowGainPages >= 3) {
                break;
            }

            for (String candidate : result.internalLinks()) {
                if (!visited.contains(candidate)) {
                    double urlScore = relevanceScore(candidate.replaceAll("[-_/]", " "), queryTerms);
                    frontier.add(new ScoredUrl(candidate, urlScore));
                }
            }
        }
        return collected;
    }

    private double relevanceScore(String text, Set<String> queryTerms) {
        if (queryTerms.isEmpty() || text == null || text.isBlank()) return 0.0;
        Set<String> docTerms = tokenize(text);
        long matches = queryTerms.stream().filter(docTerms::contains).count();
        return (double) matches / queryTerms.size();
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }
}
