# crawlJ4ai

**crawlJ4ai** is a Java 21 web discovery, headless browser rendering, self-healing structured extraction, and adaptive monitoring engine inspired by Crawl4AI and powered by **Playwright for Java**, **Jsoup**, **Flexmark**, **LangChain4j**, **Brave Search API**, and **Spring Boot 4**.

## Architecture Highlights

1. **Brave Search Discovery (`io.crawlj4ai.search`)**: Queries Brave Web Search (`/res/v1/web/search`) and LLM Context (`/res/v1/llm/context`) with freshness filters, gzip decoding, and `429` rate-limit backoff.
2. **Conditional HTTP `HEAD` Pre-Filtering (`io.crawlj4ai.http`)**: Checks cached `ETag` and `Last-Modified` validators before initializing Playwright, skipping Chromium launches on `304 Not Modified`.
3. **DOM Pruning & `fitMarkdown` (`io.crawlj4ai.markdown`)**: Strips navigation, scripts, and low-word-count boilerplate blocks, producing clean Markdown and a SHA-256 content fingerprint.
4. **Self-Healing CSS Extraction (`io.crawlj4ai.extraction`)**: Executes fast Jsoup CSS selectors at runtime and invokes an LLM via `ChatModelRegistry` only when selectors break, validating and persisting repaired schemas.
5. **Semantic Diffing (`io.crawlj4ai.diff`)**: Compares compact LCS Markdown diffs via a profile-routed LangChain4j agent to filter out timestamps, banners, and counters from genuine factual updates.
6. **Adaptive Poisson Scheduler (`io.crawlj4ai.scheduler`)**: Models both webpage updates and search query discoveries as continuous-time Poisson processes (`T = -ln(1 - p*) / lambda`), tightening polling intervals on volatile targets and backing off on static pages.
7. **Parametric LLM Routing & Failover (`io.crawlj4ai.llm`)**: Supports local **Ollama** (`gemma4:e4b`, `qwen3.8:27b`), **OpenAI-compatible** runtimes (vLLM, LM Studio, DashScope), **OpenAI**, and `NONE`, with automatic `FallbackChatModel` failover on timeouts or malformed JSON.

## Quick Start

```bash
# Build, run unit + E2E tests, and verify 98% JaCoCo coverage
mvn clean verify

# Generate Javadoc API documentation (target/reports/apidocs/index.html)
mvn javadoc:javadoc

# Run the Spring Boot Scout service
export BRAVE_API=your_brave_api_key
export OPENAI_API_KEY=your_openai_key   # optional backup for local Ollama models
mvn spring-boot:run
```

## Documentation & Specifications

- **Agent & LLM Integration Guide**: [`SCOUT_LLM_GUIDE.md`](SCOUT_LLM_GUIDE.md)
- **OpenAPI 3.1 Specification**: [`docs/openapi.yaml`](docs/openapi.yaml)
- **Kubernetes Deployment Manifest**: [`docs/k8s-scout-service.yaml`](docs/k8s-scout-service.yaml)
