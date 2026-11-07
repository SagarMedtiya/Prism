# Prism: LLM Gateway and Semantic Cache

**Author:** Airtribe
**Build plan:** Sagar Medtiya
**Program:** AI-first Software Engineering Program — Case Study
**Repository:** https://github.com/airtribe-projects/prism-capstone

---

## Background

Every AI product used today sits behind the same invisible layer: a gateway between the application and the model providers. It is the infrastructure layer every AI company now runs — one place that handles keys, budgets, failover, caching, and cost accounting, so that many teams don't each wire up providers their own way. Products like LiteLLM, OpenRouter, and Portkey exist because this is now core infrastructure, the way API gateways and load balancers were a decade ago.

Prism is an AI-first product where the gateway itself makes AI decisions in the hot path:

- It **judges how hard each prompt is**, so it can route the request to the right model tier.
- It **matches meaning, not strings**, so it can serve repeated questions from cache.

There is no chatbot to build here. The challenge is streaming, metering, reliability, and multi-tenancy done properly, with machine judgment running inside the request path.

## Objective

Build a production-grade LLM gateway that can:

1. Route requests across multiple model providers through one unified, OpenAI-compatible API.
2. Judge prompt difficulty and route automatically — cheap models for simple prompts, strong models for hard ones — proven against a labeled eval set.
3. Fall back automatically when a provider goes down, rate-limits, or degrades.
4. Stream responses token by token, end to end, without buffering.
5. Meter usage per tenant — every request, every token, every dollar.
6. Enforce cost budgets and rate limits per team.
7. Answer repeated questions from a semantic cache instead of paying for the same tokens twice.
8. Keep a request log complete enough to debug any AI traffic issue.

## Key Features

### 1. Load the Provided Data

- Load the model price table, seed tenants, and gateway config: `model_pricing.json`, `seed_keys.json`, `gateway_config.sample.json`.
- Preserve seed key values and alias names — the demo flow and scripts reference them.

### 2. Unified Chat Completions API

- One OpenAI-compatible `POST /v1/chat/completions` endpoint in front of at least 2 providers (mocks count).
- Callers use a model alias or name; the gateway resolves it and never exposes provider credentials.
- Every response carries the header contract:
  - `x-prism-provider`
  - `x-prism-cache`
  - `x-prism-fallback`
  - `x-prism-cost-usd`

### 3. Streaming Pass-Through

- `"stream": true` flows tokens from provider → gateway → client as they arrive, with no buffering.
- The stream terminates correctly with `data: [DONE]`, and usage and cost are still recorded.

### 4. Virtual Keys, Rate Limits & Budgets

- Authenticate every request with a virtual key.
- Enforce per-key:
  - model allowlist
  - requests-per-minute limit
  - monthly cost budget
- Return a distinct, documented error for each rejection type: invalid key, model not allowed, rate limited, budget exhausted.

### 5. Routing, Retries & Failover

- Resolve aliases (`fast`, `smart`) to a primary model with an ordered fallback chain.
- Retry transient errors with exponential backoff; fail over when retries are exhausted and record the failover.

### 6. Smart Routing — the `auto` Alias

- `"model": "auto"` lets the gateway classify prompt difficulty and route to `fast` or `smart`.
- Must beat a length-only baseline. The eval set deliberately includes **short-but-hard** and **long-but-trivial** traps.
- One-command routing eval reporting accuracy per case.

### 7. Semantic Response Cache

- Serve cached responses for semantically similar prompts, not just exact matches, scoped per key.
- Report hits via the header contract and track hit rates.

### 8. Metering, Request Logs & Usage API

- Compute cost per request from the price table using provider-reported token usage.
- Log every request with: key, model, provider, status, tokens, cost, cache result, fallback flag, and latency.
- Expose a usage summary API.

### 9. Simple Ops Console & Verification

- A simple frontend showing usage by key, recent requests, and cache hit rate (vibe-coding is fine).
- Must pass `scripts/smoke_test.py`.
- Run `scripts/load_test.py` and the routing eval, and report results.

## Technical Requirements

- **HTTP API:** Expose core functionality as an HTTP API. The data plane must remain OpenAI-compatible.
- **Persistent store:** Use a reliable persistent store for virtual keys, usage records, request logs, and cache entries.
- **Concurrency:** Usage accounting and rate limiting must work correctly under concurrent requests. Use atomic Redis operations, not read-modify-write, and show no over-admission under the load test.
- **Timeouts:** Every upstream provider call must have a timeout so the gateway does not hang when a provider is slow or dead.
- **Adapters:** Keep provider integration behind an adapter so mock providers or real providers can be used without changing the core system.
- **Automated tests:** Cover critical logic such as cost computation, rate limiting, cache decisions, allowlists, and routing behavior.

## Proposed Stack

| Layer | Choice |
| --- | --- |
| API / runtime | Spring Boot 3 + WebFlux (reactive streaming) |
| Persistence | MySQL — virtual keys, usage, request logs, cache entries |
| Cache / limits | Redis — semantic cache index, RPM counters, budgets |
| Embeddings | OpenAI or HuggingFace embeddings → cosine similarity match |
| Providers | Adapter interface; 2 mock providers plus pluggable real providers |
| Observability | Micrometer latency and cost metrics |
| Frontend | React + TypeScript + Tailwind ops console |
| Tests | JUnit — cost, limits, cache, allowlist, routing |

## Architecture Flow

1. Request → **auth** (virtual key) → **limits** (allowlist / RPM / budget) → **semantic cache** lookup.
2. Cache miss → **router** (difficulty classifier for `auto`) → **provider adapter** with timeout.
3. Failure → **retry + fallback chain**. Success → **stream** to the client, then **meter + log + cache write**.

Every upstream call is bounded by a timeout, so a dead provider never hangs the gateway.

## Build Plan

The build runs in six phases:

1. **Skeleton.** Load pricing, keys, and config; expose the unified endpoint over 2 mock providers; implement the header contract.
2. **Keys & limits.** Virtual-key auth, model allowlist, RPM limit, and budget, correct under concurrency.
3. **Streaming.** Token pass-through, clean `[DONE]`, usage recorded.
4. **Routing & failover.** Aliases, backoff, fallback chain; `auto` classifier and eval harness.
5. **Semantic cache.** Embedding match, per-key scoping, hit-rate tracking.
6. **Metering, console, verify.** Usage API, ops console, smoke, load, and routing eval reports.

## Watch-outs (Where Prism Quietly Fails)

- **Concurrency.** Usage accounting and rate limits must show no over-admission under the load test.
- **Routing eval.** `auto` must beat a length-only baseline. The eval set includes short-but-hard and long-but-trivial traps.
- **Cache isolation.** Scope the cache strictly per key, and prove no cross-tenant leak on a paraphrased prompt.
- **Streaming.** Tokens flow provider → gateway → client with no buffering. Usage is still recorded at `[DONE]`.
- **Retries & failover.** Use an ordered fallback chain with exponential backoff, and record every failover.
- **Metering.** Cost per request comes from the price table. Every request is logged with its key, model, provider, tokens, cost, cache result, fallback flag, and latency.

## Assessment Criteria

- **Functionality:** Does the gateway load the provided data, serve OpenAI-compatible completions over two providers, stream responses correctly, enforce keys, rate limits and budgets, perform failover, route `auto` traffic, cache semantically, meter usage, and show platform activity through a simple console?
- **Gateway Correctness Under Pressure:** Does usage accounting remain accurate during concurrent requests? Does the load test show no over-admission? Does provider failover work when a provider is taken down? Does the cache hit paraphrased prompts without leaking responses across tenants?
- **Code Quality:** Is the code clean, organized, maintainable, and easy to understand?
- **Design and Structure:** Is the gateway well-designed? Does it show a strong understanding of backend architecture, streaming, provider adapters, rate limiting, cost metering, retries, failover, and multi-tenant systems?
- **Documentation and Demo:** Is the README clear? Are setup steps, APIs, design decisions, cache scoping, routing logic, failover behavior, and known limitations documented? Does the demo clearly show the full Prism workflow?

## Deliverables

1. The final, functional product: LLM gateway and simple ops console.
2. README covering setup instructions, API documentation, architecture, routing/failover design, cache scoping, and design decisions.
3. Public link of the GitHub repository.
4. Seeded demo keys and instructions to reproduce the demo with mock providers.
5. Verification report including:
   - smoke test output
   - load test report
   - routing eval accuracy
   - over-admission results
   - accounting totals
   - latency summary
6. Explainer video demonstrating the project, including:
   - streaming
   - smart routing
   - budget rejection
   - rate limiting
   - semantic cache hit on a paraphrased prompt
   - live provider failover

## Resources

- GitHub Repository: https://github.com/airtribe-projects/prism-capstone
