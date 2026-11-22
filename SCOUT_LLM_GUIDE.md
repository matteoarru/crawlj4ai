# crawlJ4ai (Scout) — LLM & Agent System Guide

> **Purpose:** `crawlJ4ai` is a Java 21 web discovery, headless browser rendering, self-healing structured extraction, and continuous change-monitoring service. Use this document to choose the right API operation, configure LLM profiles, and construct valid extraction schemas.

---

## 1. Core Architecture & Cost-Reduction Pipeline

`crawlJ4ai` minimizes headless Chromium launches and LLM token consumption by passing every target URL through five sequential gates:

```text
[1. Brave Search Discovery]  --> Finds fresh candidate URLs via /res/v1/web/search
            |
            v
[2. HTTP HEAD Pre-Filter]    --> Checks ETag / Last-Modified; exits immediately on 304 Not Modified
            |
            v
[3. Playwright + FitMarkdown]--> Renders JS SPAs, strips nav/scripts/low-word blocks, hashes SHA-256
            |
            v
[4. Self-Healing Extractor]  --> Runs cached Jsoup CSS selectors; invokes LLM ONLY if selectors break
            |
            v
[5. Semantic Diff & Poisson] --> Compares LCS Markdown diff via LLM; adjusts polling interval by change rate
```

### Persistent State Stores (`CRAWLJ4AI_DATA_DIR`)
All learned state survives container restarts on disk:
- `http-validators.json`: Cached `ETag` and `Last-Modified` headers per URL.
- `snapshots.json`: Last baseline `fitMarkdown` and SHA-256 hash per URL.
- `selectors-cache.json`: Repaired `CssSelectorSchema` rules keyed by `<host>:<schemaName>`.
- `schedules.json`: Per-URL Poisson change-rate estimates (`estimatedChangesPerHour`) and next poll timestamps.
- `search-monitors.json`: Registered `BraveSearchQuery` monitors and deduplicated `knownUrls`.

---

## 2. Use-Case Decision Matrix for Agents

Select the endpoint that matches the user's intent:

| User Intent | Endpoint | Key Parameters & Behavior |
| :--- | :--- | :--- |
| **Read a single webpage as clean Markdown** | `POST /api/v1/scout/crawl` | Pass `{"url": "..."}`. Returns `fitMarkdown` (boilerplate stripped) and `rawMarkdown`. Reuses HTTP 304 cache unless `bypassCache: true`. |
| **Extract structured JSON records from a page (resilient to layout changes)** | `POST /api/v1/scout/extract` | Pass `url`, `schemaName`, `baseSelector`, `fieldSelectors`, `fieldDescriptions`, and `requiredFields`. Always fetches live DOM and auto-repairs broken CSS selectors via `llmProfile`. |
| **Extract structured JSON when no CSS selectors are known upfront** | `POST /api/v1/scout/extract` | Omit `baseSelector` and pass `llmInstruction` + `jsonSchemaExample`. Runs `LlmExtractionStrategy` directly on `fitMarkdown`. |
| **Continuously discover new pages for a topic and monitor them for changes** | `POST /api/v1/scout/monitors` | Pass `monitorId`, `query`, `freshness` (`PAST_DAY`, `PAST_WEEK`, `PAST_MONTH`, `PAST_YEAR`), `llmProfile`, and `monitoringFocus`. Auto-registers discovered URLs. |
| **Inspect discovered URLs and next scheduled crawl times** | `GET /api/v1/scout/monitors/{monitorId}/urls` or `GET /api/v1/scout/schedules` | Returns `knownUrls`, `currentIntervalMs`, `estimatedChangesPerHour`, and `substantiveHits`. |
| **Receive real-time alerts when new URLs appear or pages change substantively** | `GET /api/v1/scout/alerts/stream?replay=true` | Subscribe via Server-Sent Events (SSE). Emits `discovery` and `content-update` events while suppressing timestamp/banner noise. |

---

## 3. Parametric LLM Profiles & Failover Routing

Every `/crawl`, `/extract`, and `/monitors` request accepts an optional `llmProfile` string:

- **`gemma4-local` (Ollama `gemma4:e4b`, 32K context):** Fast, low-VRAM local model ideal for high-volume semantic diff triage and noise filtering. Automatically fails over to `openai` on timeout or malformed JSON.
- **`qwen-local` (Ollama `qwen3.8:27b`, 32K context):** Stronger local reasoning model recommended for complex HTML DOM inspection in `SelfHealingExtractionStrategy` and strict pricing/policy diffs. Automatically fails over to `qwen-cloud` -> `openai`.
- **`qwen-cloud` / `openai`:** Direct cloud models for zero-local-GPU deployments.
- **`NONE`:** Disables LLM calls completely; Scout operates using deterministic CSS selectors, HTTP 304 checks, and SHA-256 hash diffs.

Inspect live failover counters anytime via `GET /api/v1/scout/llm/failovers`.

---

## 4. Best Practices for Constructing Tool Calls

1. **Always provide `fieldDescriptions` and `requiredFields` on `/extract`:**
   `SelfHealingExtractionStrategy` uses `requiredFields` to detect when a site redesign breaks extraction (triggering repair) and passes `fieldDescriptions` to the LLM so it maps the new DOM elements accurately.
2. **Use semantic `schemaName` identifiers:**
   Because repaired selectors are cached under `<hostname>:<schemaName>`, reuse the same `schemaName` (e.g., `"hn_frontpage"`, `"github_releases"`) across all pages sharing a template so a single repair fixes the entire site.
3. **Set `volatileTarget: true` on high-frequency monitors:**
   Pricing pages, status dashboards, and changelog feeds should set `volatileTarget: true` (1-minute floor, 12-hour ceiling) so the Poisson scheduler reacts rapidly to updates.
4. **Provide a specific `monitoringFocus`:**
   Tell `SemanticDiffDetector` explicitly what constitutes a substantive update (e.g., *"Alert only on per-token price changes, rate-limit updates, or model deprecations; ignore blog banners and dates"*).
