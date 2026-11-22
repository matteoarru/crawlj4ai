/**
 * <h2>crawlJ4ai — LLM-Assisted Web Discovery, Self-Healing Extraction &amp; Adaptive Monitoring</h2>
 *
 * <p>{@code crawlJ4ai} is a Java 21 web harvesting and continuous monitoring engine built on
 * Playwright for Java, Jsoup, Flexmark, and LangChain4j. Rather than invoking expensive LLM
 * inference on every HTTP fetch, it places lightweight deterministic gates ahead of model calls:</p>
 *
 * <ol>
 *   <li><strong>Discovery ({@link io.crawlj4ai.search.BraveSearchClient}):</strong> Locates fresh
 *       candidate URLs via Brave Web Search and LLM Context APIs.</li>
 *   <li><strong>Conditional HTTP Pre-Filtering ({@link io.crawlj4ai.http.ConditionalHttpPreFilter}):</strong>
 *       Validates {@code ETag} and {@code Last-Modified} headers via lightweight {@code HEAD} requests,
 *       skipping headless Chromium launches on {@code 304 Not Modified}.</li>
 *   <li><strong>DOM Pruning &amp; Markdown Normalization ({@link io.crawlj4ai.markdown.DefaultMarkdownGenerator}):</strong>
 *       Strips navigation/script noise and produces token-efficient {@code fitMarkdown} alongside a
 *       SHA-256 content fingerprint.</li>
 *   <li><strong>Self-Healing CSS Extraction ({@link io.crawlj4ai.extraction.SelfHealingExtractionStrategy}):</strong>
 *       Executes zero-cost Jsoup CSS selectors at runtime and invokes an LLM only when selectors
 *       break, caching verified repairs to disk.</li>
 *   <li><strong>Semantic Diffing &amp; Poisson Scheduling ({@link io.crawlj4ai.diff.SemanticDiffDetector},
 *       {@link io.crawlj4ai.scheduler.AdaptiveRefreshScheduler}):</strong> Filters out cosmetic DOM edits
 *       and dynamically adjusts per-URL polling intervals based on historical substantive change rates.</li>
 * </ol>
 *
 * @since 0.1.0
 */
package io.crawlj4ai;
