# Prism: Test Cases

This document lists the test cases for Prism, organized by the component they verify (Architecture §3) and the phase that introduces them (ImplementationPlan §3). Each case states what it proves, the type of test, and the expected outcome.

---

## 1. Configuration & Data Loading (Phase 1)

### TC-1.1: Load model pricing

- **Type:** Unit
- **Input:** `model_pricing.json` with known entries.
- **Verify:** Every model ID in the file is available in the price table. Input and output prices match the file exactly.
- **Failure:** Missing model or wrong price → cost computation is wrong for every request using that model.

### TC-1.2: Load seed keys

- **Type:** Unit
- **Input:** `seed_keys.json` with known key values, aliases, allowlists, RPM limits, and budgets.
- **Verify:** Each seed key can be resolved by its value. Allowlist, RPM limit, and budget match the file.
- **Failure:** A seed key is rejected or has wrong limits → the demo flow breaks.

### TC-1.3: Load gateway config

- **Type:** Unit
- **Input:** `gateway_config.sample.json` with alias definitions and fallback chains.
- **Verify:** Aliases `fast`, `smart`, and `auto` resolve to the expected primary model and fallback chain.
- **Failure:** Wrong alias resolution → requests route to the wrong provider.

### TC-1.4: Preserve seed key values exactly

- **Type:** Unit
- **Verify:** The raw key values from `seed_keys.json` are accepted as `Authorization: Bearer <key>`. No transformation (trimming, casing) alters them.
- **Failure:** Demo scripts that hard-code the seed keys stop working.

### TC-1.5: Missing config file

- **Type:** Unit
- **Input:** Remove one of the three config files.
- **Verify:** The gateway fails to start with a clear error naming the missing file.
- **Failure:** Silent startup with missing data → runtime crashes.

---

## 2. Unified Chat Completions API (Phase 1)

### TC-2.1: Non-streaming request returns OpenAI-shaped body

- **Type:** Integration
- **Input:** `POST /v1/chat/completions` with a valid seed key, `"model": "fast"`, `"stream": false`.
- **Verify:** Response has `id`, `object: "chat.completion"`, `created`, `model`, `choices[0].message.role`, `choices[0].message.content`, and `usage`.
- **Failure:** Callers using OpenAI SDKs get parse errors.

### TC-2.2: Header contract on every response

- **Type:** Integration
- **Input:** Any successful request.
- **Verify:** Response includes `x-prism-provider`, `x-prism-cache`, `x-prism-fallback`, and `x-prism-cost-usd`. Values are non-empty.
- **Failure:** Callers cannot determine provider, cache status, or cost.

### TC-2.3: Provider credentials never in response

- **Type:** Integration
- **Input:** A request that succeeds.
- **Verify:** The response body, headers, and error messages contain no provider API key or secret.
- **Failure:** Credential leak.

### TC-2.4: Unknown model name

- **Type:** Integration
- **Input:** `"model": "nonexistent-model-xyz"`.
- **Verify:** Returns an OpenAI-shaped error with a clear message. Does not crash.
- **Failure:** Unhandled exception or misleading error.

### TC-2.5: Health endpoint

- **Type:** Integration
- **Input:** `GET /health` (or `/actuator/health`).
- **Verify:** Reports status of MySQL, Redis, and each configured provider.
- **Failure:** Ops cannot tell which dependency is down.

---

## 3. Authentication (Phase 2)

### TC-3.1: Valid key authenticates

- **Type:** Unit
- **Input:** A request with `Authorization: Bearer <seed-key>`.
- **Verify:** Request proceeds past auth. The resolved key record has the correct team, allowlist, and limits.

### TC-3.2: Missing Authorization header

- **Type:** Unit
- **Input:** A request with no `Authorization` header.
- **Verify:** Returns 401 with error code `invalid_key`.

### TC-3.3: Malformed Bearer token

- **Type:** Unit
- **Input:** `Authorization: Basic abc123` or `Authorization: Bearer`.
- **Verify:** Returns 401 with error code `invalid_key`.

### TC-3.4: Unknown key

- **Type:** Unit
- **Input:** `Authorization: Bearer unknown-key-value`.
- **Verify:** Returns 401 with error code `invalid_key`.

### TC-3.5: Disabled key

- **Type:** Unit
- **Input:** A key whose status is `disabled`.
- **Verify:** Returns 401 with error code `invalid_key`.

### TC-3.6: Key hash comparison

- **Type:** Unit
- **Verify:** The database stores only the hash of the key, never the plaintext. Two keys that differ only in case are treated as different keys.

---

## 4. Model Allowlist (Phase 2)

### TC-4.1: Allowed model passes

- **Type:** Unit
- **Input:** Key with allowlist `["fast", "smart"]`, request for `"model": "fast"`.
- **Verify:** Request proceeds.

### TC-4.2: Disallowed model rejected

- **Type:** Unit
- **Input:** Key with allowlist `["fast"]`, request for `"model": "smart"`.
- **Verify:** Returns 403 with error code `model_not_allowed`.

### TC-4.3: Alias resolution checked against allowlist

- **Type:** Unit
- **Input:** Key with allowlist `["fast"]`, request for `"model": "auto"` where `auto` resolves to `smart`.
- **Verify:** Returns 403 with error code `model_not_allowed` (the resolved model is not in the allowlist).

### TC-4.4: Empty allowlist rejects everything

- **Type:** Unit
- **Input:** Key with allowlist `[]`.
- **Verify:** Every model request returns `model_not_allowed`.

---

## 5. Rate Limiting — RPM (Phase 2)

### TC-5.1: Under limit passes

- **Type:** Unit
- **Input:** Key with RPM limit 10. Send 9 requests within one minute.
- **Verify:** All 9 succeed.

### TC-5.2: At limit rejects

- **Type:** Unit
- **Input:** Key with RPM limit 10. Send the 11th request within one minute.
- **Verify:** Returns 429 with error code `rate_limited` and a `Retry-After` header.

### TC-5.3: Window slides

- **Type:** Unit
- **Input:** Key with RPM limit 5. Send 5 requests, wait for the window to expire, send 5 more.
- **Verify:** All 10 succeed (5 in each window).

### TC-5.4: Concurrent requests — exact admission

- **Type:** Concurrency
- **Input:** Key with RPM limit K. Send N > K requests simultaneously.
- **Verify:** Exactly K are admitted. N − K are rejected with `rate_limited`. No over-admission.
- **Failure:** Read-modify-write race → more than K admitted.

### TC-5.5: RPM is per-key

- **Type:** Unit
- **Input:** Two keys, each with RPM limit 5. Send 5 requests on key A.
- **Verify:** Key B still has its full 5-request allowance.

---

## 6. Budget Enforcement (Phase 2)

### TC-6.1: Request within budget passes

- **Type:** Unit
- **Input:** Key with monthly budget $10.00. Request estimated cost $0.05.
- **Verify:** Reservation succeeds. Request proceeds.

### TC-6.2: Request exceeding remaining budget rejected

- **Type:** Unit
- **Input:** Key with monthly budget $1.00, already spent $0.98. Request estimated cost $0.05.
- **Verify:** Returns 402 with error code `budget_exhausted`.

### TC-6.3: Reservation released after successful request

- **Type:** Unit
- **Input:** Reserve $0.10 maximum, actual cost is $0.03.
- **Verify:** $0.07 is released back to the budget. Subsequent requests see the correct remaining budget.

### TC-6.4: Concurrent budget — no overspend

- **Type:** Concurrency
- **Input:** Key with budget $1.00. Send 50 concurrent requests each estimated at $0.05.
- **Verify:** At most 20 are admitted ($1.00 / $0.05). Total settled cost ≤ $1.00.
- **Failure:** Concurrent reservations exceed the budget.

### TC-6.5: Reservation TTL and sweep

- **Type:** Integration
- **Input:** Start a request, kill it between reserve and release.
- **Verify:** The reservation expires after the TTL. The sweep releases the amount and logs it. Subsequent requests see the restored budget.

### TC-6.6: Budget scoped by month

- **Type:** Unit
- **Input:** Key with monthly budget $10.00, fully spent in October.
- **Verify:** The same key has a fresh $10.00 budget in November.

### TC-6.7: Cache hit does not consume budget

- **Type:** Integration
- **Input:** A request that hits the cache.
- **Verify:** The reservation is released in full. Cost recorded is $0. Budget is unchanged.

---

## 7. Error Codes (Phase 2)

### TC-7.1: Each rejection type returns a distinct code

- **Type:** Unit
- **Verify:** `invalid_key` → 401, `model_not_allowed` → 403, `rate_limited` → 429, `budget_exhausted` → 402. Each response body contains the error code in an OpenAI-shaped error envelope.

### TC-7.2: Rejections are logged

- **Type:** Integration
- **Input:** Trigger each rejection type.
- **Verify:** Each rejection creates a `request_logs` entry with the error code, key id, and timestamp.

---

## 8. Streaming (Phase 3)

### TC-8.1: Tokens arrive incrementally

- **Type:** Integration
- **Input:** `"stream": true`.
- **Verify:** The client receives multiple SSE events before the stream ends. Each event is an OpenAI-delta-formatted chunk. Content accumulates across chunks.

### TC-8.2: Stream ends with `data: [DONE]`

- **Type:** Integration
- **Input:** `"stream": true`.
- **Verify:** The last event is `data: [DONE]`.

### TC-8.3: Usage recorded after stream

- **Type:** Integration
- **Input:** `"stream": true`.
- **Verify:** A `usage_records` row is written with prompt tokens, completion tokens, and cost.

### TC-8.4: Client disconnect cancels upstream

- **Type:** Integration
- **Input:** Start a streaming request, close the client connection after 2 chunks.
- **Verify:** The upstream subscription is cancelled. A `request_logs` entry is written with status `client_closed`.

### TC-8.5: Provider error mid-stream

- **Type:** Integration
- **Input:** Mock provider configured to error after 3 chunks.
- **Verify:** The client receives an error event followed by `data: [DONE]`. The request is logged as failed. No fallback occurs (first byte already sent).

### TC-8.6: No buffering

- **Type:** Integration
- **Verify:** The time between the provider emitting a chunk and the client receiving it is bounded (< 50 ms in test conditions). The gateway does not wait for the full response.

### TC-8.7: Streaming request not cached

- **Type:** Integration
- **Input:** Send a streaming request, then the same prompt non-streaming.
- **Verify:** The streaming response is not written to `cache_entries`. The non-streaming request is a cache miss.

### TC-8.8: Headers set on streamed response

- **Type:** Integration
- **Input:** `"stream": true`.
- **Verify:** `x-prism-provider`, `x-prism-cache`, and `x-prism-fallback` are present on the response. `x-prism-cost-usd` may be `0` or absent (cost is known only after `[DONE]`).

---

## 9. Routing & Alias Resolution (Phase 4)

### TC-9.1: `fast` alias resolves to primary model

- **Type:** Unit
- **Input:** `"model": "fast"`.
- **Verify:** The request is sent to the primary model defined for `fast` in `gateway_config.sample.json`.

### TC-9.2: `smart` alias resolves to primary model

- **Type:** Unit
- **Input:** `"model": "smart"`.
- **Verify:** Same as TC-9.1 for the `smart` alias.

### TC-9.3: Direct model name routed as-is

- **Type:** Unit
- **Input:** `"model": "gpt-4o"` (a model name, not an alias).
- **Verify:** The request is sent to the provider that hosts that model, without alias resolution.

### TC-9.4: `auto` routes simple prompt to `fast`

- **Type:** Unit
- **Input:** `"model": "auto"`, prompt: `"What is 2 + 2?"`.
- **Verify:** The classifier returns `fast`. The request is routed to the `fast` primary model.

### TC-9.5: `auto` routes complex prompt to `smart`

- **Type:** Unit
- **Input:** `"model": "auto"`, prompt: a multi-step reasoning problem.
- **Verify:** The classifier returns `smart`. The request is routed to the `smart` primary model.

### TC-9.6: Short-but-hard trap

- **Type:** Eval
- **Input:** `"model": "auto"`, prompt: a short prompt that requires a strong model (from the eval set).
- **Verify:** The classifier routes to `smart`, not `fast`. A length-only baseline would route to `fast`.

### TC-9.7: Long-but-trivial trap

- **Type:** Eval
- **Input:** `"model": "auto"`, prompt: a long prompt that a cheap model handles (from the eval set).
- **Verify:** The classifier routes to `fast`, not `smart`. A length-only baseline would route to `smart`.

### TC-9.8: Classifier beats length-only baseline

- **Type:** Eval
- **Input:** The full labeled eval set.
- **Verify:** Classifier accuracy > length-only baseline accuracy. Per-case accuracy is reported.

### TC-9.9: Classifier timeout defaults to `smart`

- **Type:** Unit
- **Input:** Mock classifier that exceeds the time budget.
- **Verify:** The request routes to `smart`. The log records `classifier_timeout`.

---

## 10. Retries & Failover (Phase 4)

### TC-10.1: Transient error retried with backoff

- **Type:** Unit
- **Input:** Mock provider returns HTTP 503, then succeeds on the 2nd attempt.
- **Verify:** The request succeeds. The delay between attempts follows exponential backoff.

### TC-10.2: Retry limit exhausted triggers failover

- **Type:** Integration
- **Input:** Mock primary provider always returns 503. Fallback provider succeeds.
- **Verify:** After the configured number of retries, the request fails over to the next model. `x-prism-fallback: true`. The log records primary model, serving model, and reason.

### TC-10.3: Provider fully down — failover to chain

- **Type:** Integration
- **Input:** Take mock provider A completely down. Provider B is up.
- **Verify:** Requests using an alias with A as primary route to B. The fallback header and log are set.

### TC-10.4: All providers down — `provider_unavailable`

- **Type:** Integration
- **Input:** Take all providers in the fallback chain down.
- **Verify:** Returns 503 with error code `provider_unavailable`.

### TC-10.5: HTTP 429 retried

- **Type:** Unit
- **Input:** Mock provider returns 429 with `Retry-After`.
- **Verify:** The gateway retries after the indicated delay, up to the retry limit.

### TC-10.6: HTTP 400 not retried, not failed over

- **Type:** Unit
- **Input:** Mock provider returns 400 (permanent for this request).
- **Verify:** The error is returned to the caller immediately. No retry. No fallback.

### TC-10.7: HTTP 404 (model not found) fails over immediately

- **Type:** Unit
- **Input:** Mock provider returns 404 for the model.
- **Verify:** Fails over to the next model in the chain without retrying the current one.

### TC-10.8: Overall timeout bounds retries and fallbacks

- **Type:** Integration
- **Input:** All providers slow (> overall timeout).
- **Verify:** The request fails within the overall timeout, not after exhausting all retries on all models.

### TC-10.9: Failover does not happen after first streamed byte

- **Type:** Integration
- **Input:** Streaming request. Provider errors after sending 2 chunks.
- **Verify:** The stream ends with an error event and `[DONE]`. No fallback attempt. Log status is `failed`.

---

## 11. Semantic Cache (Phase 5)

### TC-11.1: Exact prompt cached and served

- **Type:** Integration
- **Input:** Send a cacheable request. Send the identical request again.
- **Verify:** Second request returns `x-prism-cache: hit`. The response body matches the first. Cost is $0.

### TC-11.2: Paraphrased prompt is a cache hit

- **Type:** Integration
- **Input:** Send `"What is the capital of France?"`. Then send `"Tell me the capital city of France."`.
- **Verify:** Second request returns `x-prism-cache: hit`.

### TC-11.3: Semantically different prompt is a miss

- **Type:** Integration
- **Input:** Send `"What is the capital of France?"`. Then send `"What is the population of France?"`.
- **Verify:** Second request returns `x-prism-cache: miss`.

### TC-11.4: Cache isolation — no cross-key hit

- **Type:** Integration
- **Input:** Send a prompt with key A. Send the same prompt with key B.
- **Verify:** Key B's request is a cache miss. Key B never sees key A's cached response.

### TC-11.5: Cache isolation — no cross-key hit on paraphrase

- **Type:** Integration
- **Input:** Send a prompt with key A. Send a paraphrased version with key B.
- **Verify:** Key B's request is a cache miss.

### TC-11.6: Non-streaming, temperature 0 is cacheable

- **Type:** Unit
- **Input:** `"stream": false`, `"temperature": 0`.
- **Verify:** The cache lookup runs. On a miss, the response is stored.

### TC-11.7: Streaming request bypasses cache

- **Type:** Unit
- **Input:** `"stream": true`, `"temperature": 0`.
- **Verify:** No cache lookup. No cache write. Bypass logged.

### TC-11.8: Non-zero temperature bypasses cache

- **Type:** Unit
- **Input:** `"stream": false`, `"temperature": 0.7`.
- **Verify:** No cache lookup. No cache write. Bypass logged.

### TC-11.9: Cache TTL expiry

- **Type:** Unit
- **Input:** Store a cache entry. Advance time past the TTL.
- **Verify:** The entry is not returned as a hit.

### TC-11.10: Per-key entry cap and eviction

- **Type:** Unit
- **Input:** Key with entry cap of 5. Store 6 entries.
- **Verify:** The oldest entry is evicted. Only 5 entries remain.

### TC-11.11: Cache hit releases budget reservation

- **Type:** Integration
- **Input:** A cacheable request that hits the cache.
- **Verify:** The budget reservation is released in full. The key's remaining budget is unchanged.

### TC-11.12: Hit-rate tracking

- **Type:** Integration
- **Input:** Send 5 requests: 3 hits, 2 misses.
- **Verify:** The usage API reports 60% hit rate for this key.

---

## 12. Cost Computation & Metering (Phase 1 cost, Phase 6 API)

### TC-12.1: Cost from reported usage

- **Type:** Unit
- **Input:** Provider reports 100 prompt tokens and 50 completion tokens. Price: $0.01/1K input, $0.03/1K output.
- **Verify:** Cost = (100 × 0.01 / 1000) + (50 × 0.03 / 1000) = $0.0025. Stored as 2500 micro-USD.

### TC-12.2: Cost with estimated usage

- **Type:** Unit
- **Input:** Provider reports no usage. Gateway estimates 80 prompt tokens, 40 completion tokens.
- **Verify:** Cost is computed from the estimates. `usage_estimated` flag is set on the record.

### TC-12.3: Cache hit costs zero

- **Type:** Unit
- **Input:** A cache hit.
- **Verify:** Cost is 0. Tokens saved are recorded as a separate field.

### TC-12.4: Cost stored in micro-USD

- **Type:** Unit
- **Verify:** No floating-point dollars in MySQL or Redis. Sums of micro-USD values are exact.

### TC-12.5: `x-prism-cost-usd` header matches record

- **Type:** Integration
- **Input:** A non-streaming request.
- **Verify:** The `x-prism-cost-usd` header value equals the cost stored in `usage_records`, converted to USD.

---

## 13. Request Logging (Phases 1–6)

### TC-13.1: Successful request logged

- **Type:** Integration
- **Verify:** A `request_logs` row is written with key id, model, provider, status `success`, tokens, cost, `cache: miss`, `fallback: false`, and latency.

### TC-13.2: Rejected request logged

- **Type:** Integration
- **Input:** Trigger each rejection (invalid key, model not allowed, rate limited, budget exhausted).
- **Verify:** Each creates a `request_logs` entry with the error code.

### TC-13.3: Fallback request logged

- **Type:** Integration
- **Input:** A request that fails over.
- **Verify:** The log records the primary model, the serving model, `fallback: true`, and the reason.

### TC-13.4: Cache hit logged

- **Type:** Integration
- **Verify:** The log records `cache: hit`, cost 0, and tokens saved.

### TC-13.5: Client disconnect logged

- **Type:** Integration
- **Verify:** The log records status `client_closed`.

### TC-13.6: Every field present

- **Type:** Integration
- **Verify:** Every `request_logs` row has: key id, model, provider (or null for rejections), status, error code (or null), tokens, cost, cache result, fallback flag, and latency.

---

## 14. Usage API (Phase 6)

### TC-14.1: Totals per key

- **Type:** Integration
- **Input:** Send 10 requests on key A, 5 on key B.
- **Verify:** The usage API returns correct request count, token totals, and cost for each key.

### TC-14.2: Totals per time window

- **Type:** Integration
- **Input:** Send requests across two days.
- **Verify:** The API can filter by date range and returns correct totals for each window.

### TC-14.3: Cache hit rate in usage

- **Type:** Integration
- **Verify:** The API returns the correct hit/miss ratio per key.

### TC-14.4: Fallback count in usage

- **Type:** Integration
- **Verify:** The API returns the correct number of fallback events per key.

### TC-14.5: Recent requests with pagination

- **Type:** Integration
- **Input:** 50 requests. Request page 1 (limit 20), then page 2.
- **Verify:** 20 results per page, ordered by recency, no duplicates.

### TC-14.6: Recent requests filtered by key

- **Type:** Integration
- **Verify:** Filtering by key A returns only key A's requests.

### TC-14.7: Recent requests filtered by status

- **Type:** Integration
- **Input:** Filter by `rate_limited`.
- **Verify:** Only rate-limited requests are returned.

### TC-14.8: Accounting reconciliation

- **Type:** Integration
- **Input:** Run the load test.
- **Verify:** Sum of `usage_records.cost` per key equals the total reported by the usage API. No discrepancy.

---

## 15. Ops Console (Phase 6)

### TC-15.1: Usage by key view

- **Type:** Manual / E2E
- **Verify:** The console shows a table or chart of usage per key, including request count, tokens, and cost.

### TC-15.2: Recent requests view

- **Type:** Manual / E2E
- **Verify:** The console shows recent requests with key, model, provider, status, cost, cache result, and latency.

### TC-15.3: Cache hit rate view

- **Type:** Manual / E2E
- **Verify:** The console shows hit rate per key, matching the usage API.

### TC-15.4: Console reads from API only

- **Type:** Code review
- **Verify:** The console makes no direct database queries. All data comes from the usage and request APIs.

---

## 16. Timeouts (Phases 1–4)

### TC-16.1: Connect timeout

- **Type:** Integration
- **Input:** Mock provider that never accepts connections.
- **Verify:** The request fails within the connect timeout, not the total timeout.

### TC-16.2: First-byte timeout

- **Type:** Integration
- **Input:** Mock provider that accepts the connection but sends no data.
- **Verify:** The request fails within the first-byte timeout.

### TC-16.3: Total timeout

- **Type:** Integration
- **Input:** Mock provider that sends data very slowly.
- **Verify:** The request fails within the total timeout, even if data is trickling in.

### TC-16.4: Timeout triggers retry/failover

- **Type:** Integration
- **Input:** Primary provider times out. Fallback succeeds.
- **Verify:** The request succeeds on the fallback within the overall timeout.

---

## 17. End-to-End Verification (Phase 6)

### TC-17.1: Smoke test passes

- **Type:** E2E
- **Input:** `scripts/smoke_test.py` from a fresh start.
- **Verify:** All checks pass. Exit code 0.

### TC-17.2: Load test — zero over-admission

- **Type:** E2E
- **Input:** `scripts/load_test.py` with concurrent requests against keys with RPM and budget limits.
- **Verify:** No key admits more requests than its RPM allows. No key spends more than its budget.

### TC-17.3: Load test — accounting reconciles

- **Type:** E2E
- **Input:** After the load test.
- **Verify:** Sum of `usage_records.cost` per key matches the total the usage API reports. Difference is 0.

### TC-17.4: Load test — latency within bounds

- **Type:** E2E
- **Input:** After the load test.
- **Verify:** p50, p95, and p99 latency are reported. p99 is within a documented bound.

### TC-17.5: Routing eval — accuracy reported

- **Type:** E2E
- **Input:** `scripts/routing_eval.py`.
- **Verify:** Per-case and overall accuracy printed for both the classifier and the length-only baseline. Classifier > baseline.

---

## 18. Test Matrix Summary

| Component | Unit | Concurrency | Integration | Eval | E2E |
| --- | --- | --- | --- | --- | --- |
| Config loading | TC-1.1–1.5 | | | | |
| Chat completions API | | | TC-2.1–2.5 | | |
| Auth | TC-3.1–3.6 | | | | |
| Allowlist | TC-4.1–4.4 | | | | |
| RPM limiter | TC-5.1–5.3, 5.5 | TC-5.4 | | | |
| Budget | TC-6.1–6.3, 6.6 | TC-6.4 | TC-6.5, 6.7 | | |
| Error codes | TC-7.1 | | TC-7.2 | | |
| Streaming | | | TC-8.1–8.8 | | |
| Routing | TC-9.1–9.5, 9.9 | | | TC-9.6–9.8 | |
| Retries & failover | TC-10.1, 10.5–10.7 | | TC-10.2–10.4, 10.8–10.9 | | |
| Semantic cache | TC-11.6–11.10 | | TC-11.1–11.5, 11.11–11.12 | | |
| Cost computation | TC-12.1–12.4 | | TC-12.5 | | |
| Request logging | | | TC-13.1–13.6 | | |
| Usage API | | | TC-14.1–14.8 | | |
| Ops console | | | | | TC-15.1–15.4 |
| Timeouts | | | TC-16.1–16.4 | | |
| Verification | | | | | TC-17.1–17.5 |

**Total: 85 test cases** across 17 areas.

All unit and integration tests run against mock providers, so no real API key is needed in CI.
