# Prism: Implementation Plan

This plan turns the requirements in [ProblemStatement.md](ProblemStatement.md) into an ordered build, using the design in [Architecture.md](Architecture.md). The build runs in six phases. Each phase ends with an exit criterion that must pass before the next phase starts.

---

## 1. Build Principles

- **Build the data plane first.** Every later phase plugs into the request lifecycle (Architecture §4), so the lifecycle is in place from Phase 1.
- **Make admission correct before adding features.** Keys, limits, and budgets come before streaming, routing, and caching, so later features cannot break accounting.
- **Test with mock providers.** Every phase is testable without real provider keys. Real adapters are added last and do not change the core.
- **Measure before tuning.** The routing classifier and cache threshold are tuned only against the labeled eval sets, and the length-only baseline is reported next to every result.

---

## 2. Target Structure

```
prism/
├── src/main/java/.../prism/
│   ├── api/            # chat completions, usage, requests, health
│   ├── auth/           # virtual-key lookup and hashing
│   ├── limits/         # allowlist, RPM (Redis Lua), budget reservations
│   ├── cache/          # embedding, key-scoped match, store, eviction
│   ├── routing/        # alias resolution, auto classifier, fallback chain
│   ├── provider/       # ProviderAdapter, retry, failover, timeouts
│   │   └── mock/       # mock providers with latency, error, and outage controls
│   ├── streaming/      # SSE pass-through, usage capture, disconnect handling
│   ├── metering/       # price table, cost computation, usage records
│   ├── logging/        # request log writer, error codes
│   ├── reconcile/      # expired-reservation sweep
│   └── config/         # startup loading of the three config files
├── src/main/resources/
│   ├── db/migration/   # Flyway migrations
│   ├── model_pricing.json
│   ├── seed_keys.json
│   └── gateway_config.sample.json
├── console/            # React + TypeScript + Tailwind ops console
├── scripts/
│   ├── smoke_test.py
│   ├── load_test.py
│   └── routing_eval.py
├── eval/               # labeled routing set (includes both traps)
└── docs/
```

The packages match the components in Architecture §3, so each component has one home in the code.

---

## 3. Phases

### Phase 1: Skeleton

**Goal:** Serve one OpenAI-compatible endpoint over two mock providers, with the header contract on every response.

**Tasks**

1. Scaffold the Spring Boot 3 project with WebFlux, MySQL, Redis, Flyway, and JUnit. Add a local compose file that runs the gateway, MySQL, and Redis together.
2. Load `model_pricing.json`, `seed_keys.json`, and `gateway_config.sample.json` at startup. Keep seed key values and alias names exactly as provided.
3. Create the Flyway schema for `virtual_keys`, `usage_records`, `request_logs`, and `cache_entries` (Architecture §10). Keep the Redis key formats in one class so they are defined in one place.
4. Define the `ProviderAdapter` interface: `complete`, `stream`, and `capabilities`. Keep vendor types out of it.
5. Build two mock adapters. Each one can be configured for latency, error rate, and forced outage, so failover can be tested in Phase 4.
6. Implement `POST /v1/chat/completions`. Resolve the alias or model name, call the adapter with connect, first-byte, and total timeouts, and return an OpenAI-shaped body. Provider credentials never appear in the response.
7. Set all four headers (`x-prism-provider`, `x-prism-cache`, `x-prism-fallback`, `x-prism-cost-usd`) on every response. In this phase, `x-prism-cache` is `miss`, `x-prism-fallback` is `false`, and cost is computed but not yet metered.
8. Add the health endpoint, which reports MySQL, Redis, and each mock provider.

**Exit criterion:** A non-streaming request with a seed key returns an OpenAI-shaped body and all four headers. Config-loading tests pass. The health endpoint reports all components as up.

### Phase 2: Keys & Limits

**Goal:** Authenticate every request with a virtual key and enforce allowlist, RPM, and monthly budget, with no over-admission under concurrency.

**Tasks**

1. Authenticate with `Authorization: Bearer <key>`. Store and compare only the key hash. Return `invalid_key` (401) for an unknown or disabled key.
2. Enforce the model allowlist. Return `model_not_allowed` (403) when the key's allowlist does not include the requested model or alias.
3. Enforce RPM with one atomic Redis Lua script that checks the sliding window and increments it. Return `rate_limited` (429) with `Retry-After`.
4. Enforce the monthly budget with reservations (Architecture §5). Use one Lua script to reserve the estimated maximum cost against `budget:{keyId}:{YYYY-MM}`, and one to release the difference after the call. Return `budget_exhausted` (402) when the reservation fails.
5. Give every reservation a TTL. If a request crashes between reserve and release, the reservation expires and the sweep in task 6 returns the amount to the budget. This removes the need for a separate reconciliation pass during normal operation.
6. Add the reservation sweep in `reconcile/`. It runs on a schedule, releases expired reservations, and logs each release. It also compares Redis spend with the sum of `usage_records` for the month and logs any difference.
7. Write the request log entry for every rejection, with the error code, so rejected traffic can be audited.

**Exit criterion:** Unit tests cover allowlist, RPM window, and reservation/release arithmetic. A concurrency test sends N parallel requests against a limit of K and admits exactly K. A test that kills a request between reserve and release shows the sweep restores the budget.

### Phase 3: Streaming

**Goal:** Pass tokens from provider to client as they arrive, end every stream with `data: [DONE]`, and record usage.

**Tasks**

1. Implement `"stream": true` as a `Flux<ServerSentEvent>` with `text/event-stream`. Forward each chunk as the adapter emits it. Do not collect chunks.
2. Have each adapter normalize chunks to the OpenAI delta format. Request usage in the final chunk where the provider supports it.
3. End every stream with `data: [DONE]`. On a provider error after the first byte, send an error event, then `[DONE]`, and log the request as failed.
4. Capture usage at the end of the stream and compute cost. When the provider sends no usage, estimate the token counts and set `usage_estimated` on the usage record.
5. Cancel the upstream subscription on client disconnect. Write the request record with status `client_closed`.
6. Restrict failover to the period before the first byte (Architecture §6). Enforce this in the provider layer so no caller can bypass it.
7. Do not write streaming responses to the cache, and do not serve streaming requests from it. Document this in the README.

**Exit criterion:** A streamed request shows tokens arriving incrementally, ends with `[DONE]`, and writes one usage record with cost. Tests cover a client disconnect and a provider error mid-stream.

### Phase 4: Routing & Failover

**Goal:** Resolve aliases to a primary model with an ordered fallback chain, handle transient errors with backoff, and route `auto` traffic with a classifier that beats the length-only baseline.

**Tasks**

1. Implement alias resolution. `fast` and `smart` each map to a primary model and an ordered fallback chain, from `gateway_config.sample.json`.
2. Classify errors as transient, permanent for the model, or permanent for the request (Architecture §8). Retry only transient errors, with exponential backoff, jitter, and a fixed attempt limit.
3. Fail over to the next model in the chain when retries are exhausted or the error is permanent for the model. Set `x-prism-fallback: true` and record the primary model, the serving model, and the reason.
4. Bound the total time for retries and fallbacks by the request's overall timeout.
5. Implement the `auto` classifier. It runs before the provider call, within a fixed time budget. If it runs out of time, route to `smart` and log `classifier_timeout`.
6. Build the labeled eval set in `eval/`. Include short-but-hard and long-but-trivial cases. Include the length-only baseline in the same set.
7. Write `scripts/routing_eval.py`. It prints accuracy per case and overall, for both the classifier and the baseline.
8. Tune the classifier only against this set. Commit the eval output with the code.

**Exit criterion:** Taking one mock provider down routes the request to the fallback, sets the header, and records the failover. The routing eval reports classifier accuracy above the length-only baseline.

### Phase 5: Semantic Cache

**Goal:** Serve cached responses for paraphrased prompts from the same key, never from another key, and report hit rates.

**Tasks**

1. Implement the eligibility rule (Architecture §11). Only non-streaming requests with temperature `0` are cacheable. Log every bypass with its reason.
2. Normalize the request (model, system prompt, message history, final user message) into one text. Compute its embedding with the provider chosen in §4.
3. Store the embedding, response, model, key id, and timestamps in `cache_entries`.
4. On lookup, load only the current key's entries, then compare cosine similarity against the threshold. A match above the threshold is a hit.
5. Add a TTL and a per-key entry cap. When the cap is reached, evict the oldest entries.
6. On a hit, release the reservation, log the request with the tokens saved, and set `x-prism-cache: hit` and `x-prism-cost-usd: 0`.
7. Count hits and misses per key for the usage API.
8. Write the isolation test. A prompt sent with key A is never answered from an entry stored under key B, including for paraphrases.

**Exit criterion:** A paraphrased prompt from the same key is a hit. The same prompt from a different key is a miss. Cache-decision tests pass, and the isolation test passes.

### Phase 6: Metering, Console & Verification

**Goal:** Expose usage through an API and a console, then run and report the full verification suite.

**Tasks**

1. Build the usage summary API. It returns totals per key and per time window, covering requests, tokens, cost, cache hit rate, and fallback count. Compute totals from `usage_records` only.
2. Build the recent-requests API with pagination and filters for key, status, model, and error code.
3. Add Micrometer metrics for request count, latency percentiles, cost per key and provider, cache hit rate, fallback count, and rejections by code.
4. Build the ops console in React, TypeScript, and Tailwind. It has three views: usage by key, recent requests, and cache hit rate. It reads only from the usage and request APIs.
5. Write the seed-key demo instructions for the mock providers.
6. Run `scripts/smoke_test.py` and make it pass from a fresh start.
7. Run `scripts/load_test.py`. Record the over-admission count, the accounting totals reconciled against `usage_records`, and the latency percentiles (p50, p95, p99).
8. Run `scripts/routing_eval.py` and record accuracy per case.
9. Assemble the verification report from these outputs.

**Exit criterion:** The smoke test passes. The load test shows zero over-admission. The usage totals match the request log. The verification report includes every item under Deliverables in the problem statement.

---

## 4. Decisions to Settle Before the Phase Needs Them

| Decision | Needed by | Options | Default if undecided |
| --- | --- | --- | --- |
| Live provider adapter | Phase 1 (interface), Phase 6 (live demo) | OpenAI, HuggingFace, or another | Mock providers only until chosen |
| Embedding provider | Phase 5 | OpenAI or HuggingFace | Mock embeddings for tests |
| Redis unavailable | Phase 2 | Fail closed (reject all) or fail open (allow and log) | Fail closed, so limits cannot be bypassed |
| Cache similarity threshold | Phase 5 | Tuned on a labeled set | Start high, lower only with evidence |
| Console access | Phase 6 | None (local demo) or a console key | None, documented as a limitation |

---

## 5. Testing Strategy

| Area | Test type | What it proves |
| --- | --- | --- |
| Cost computation | Unit | Price × reported tokens gives the expected cost, including the estimated path |
| RPM limiter | Unit and concurrency | Window logic is correct, and parallel requests admit exactly the limit |
| Budget reservations | Unit, concurrency, and crash | Reserve and release balance, the budget is never overspent, and the sweep restores abandoned reservations |
| Allowlist | Unit | Each model is allowed or rejected correctly per key |
| Error codes | Unit | Each rejection returns the documented code and status |
| Streaming | Integration | Incremental tokens, `[DONE]`, usage capture, disconnect, and mid-stream error |
| Routing | Unit and eval | Alias resolution, fallback order, and classifier accuracy against the baseline |
| Failover | Integration | A downed mock provider triggers the fallback and records it |
| Cache decisions | Unit | Eligibility, threshold, TTL, eviction, and bypass logging |
| Cache isolation | Integration | No cross-key hits, including for paraphrases |
| End to end | `smoke_test.py` | The demo flow works from a fresh start |

CI runs only against mock providers, so no real provider key is needed.

---

## 6. Risks

| Risk | Mitigation |
| --- | --- |
| Over-admission under concurrency | Atomic Lua scripts for admission and reservation, with a concurrency test in CI |
| Budget leaks from crashed requests | Reservation TTL and the sweep in Phase 2, with a crash test |
| Budget overspend from estimate error | Reserve the maximum estimate, then release the difference; log every release |
| Stream hangs on a slow provider | Connect, first-byte, and total timeouts on every upstream call |
| Cross-tenant cache leak | Key filter applied before similarity, with an isolation test |
| Classifier no better than length | Eval set with traps; tune only on the labeled set; report the baseline |
| Scope creep in the console | Three views only; the console reads only from the APIs |

---

## 7. Definition of Done

- Every item under Key Features in [ProblemStatement.md](ProblemStatement.md) works end to end.
- `scripts/smoke_test.py` passes from a fresh start with the seeded demo keys.
- The load test shows no over-admission, and the accounting totals reconcile with `usage_records` and the request log.
- The routing eval beats the length-only baseline, and per-case accuracy is recorded.
- The README covers setup, the API, the error codes, routing and failover design, cache scoping, design decisions, and the known limitations in Architecture §16.
- The explainer video shows streaming, smart routing, budget rejection, rate limiting, a semantic cache hit on a paraphrased prompt, and live provider failover.
