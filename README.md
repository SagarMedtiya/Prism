# Prism — LLM Gateway

A production-grade LLM gateway that sits between your application and LLM providers. Prism adds virtual API keys, per-key rate limiting, budget control, intelligent routing, semantic caching, and an ops console — all through an OpenAI-compatible API.

Built with **Spring Boot 3 WebFlux** (reactive), **MySQL** (R2DBC), **Redis**, and a **React + TypeScript** ops console.

## Architecture

```
Client  ──►  Prism Gateway  ──►  Mock Providers (built-in)
  │              │                OpenRouter (GPT, Claude, Gemini, Llama)
  │              │
  │         ┌────┴─────┐
  │         │  Redis    │   RPM sliding window, budget reservations
  │         │  MySQL    │   Virtual keys, usage records, cache entries
  │         └──────────┘
  │
  └──►  Ops Console (React)   Usage, requests, cache dashboard
```

## Features

| Phase | Feature | Description |
|-------|---------|-------------|
| 1 | **OpenAI-compatible API** | `POST /v1/chat/completions` — drop-in replacement with mock providers |
| 2 | **Virtual keys & limits** | SHA-256 key hashing, model allowlists, sliding-window RPM, atomic budget reservations via Redis Lua |
| 3 | **SSE streaming** | Server-sent events with usage capture in the final chunk, disconnect handling |
| 4 | **Smart routing** | Alias resolution (`fast`, `smart`, `auto`), keyword-based difficulty classifier, failover chain |
| 5 | **Semantic cache** | Character n-gram embeddings, cosine similarity matching (threshold 0.92), per-key isolation, LRU eviction |
| 6 | **Metering & console** | Usage summary API, paginated request logs, React ops console with 3 views |

### Response Headers

Every response includes four headers for observability:

| Header | Example | Description |
|--------|---------|-------------|
| `x-prism-provider` | `mock-provider-a` | Which provider served the request |
| `x-prism-cache` | `hit` / `miss` | Semantic cache result |
| `x-prism-fallback` | `true` / `false` | Whether a fallback provider was used |
| `x-prism-cost-usd` | `0.000450` | Request cost in USD |

## Quick Start

### Prerequisites

- Java 21+
- Docker & Docker Compose
- Node.js 18+ (for ops console)
- Python 3.10+ (for test scripts)

### 1. Start infrastructure

```bash
docker compose up -d mysql redis
```

### 2. Run the gateway

```bash
# With mock providers only (no API key needed)
./mvnw spring-boot:run

# With OpenRouter (real LLM providers)
OPENROUTER_API_KEY=your-key-here ./mvnw spring-boot:run
```

### 3. Make a request

```bash
# Non-streaming
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-prism-demo-alpha-001" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "fast",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'

# Streaming
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-prism-demo-alpha-001" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "smart",
    "messages": [{"role": "user", "content": "Explain monads"}],
    "stream": true
  }'

# Auto-routing (classifies difficulty, routes to fast or smart)
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-prism-demo-alpha-001" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "auto",
    "messages": [{"role": "user", "content": "What is 2+2?"}]
  }'

# OpenRouter (real LLM)
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-prism-demo-alpha-001" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt",
    "messages": [{"role": "user", "content": "Hello from Prism!"}]
  }'
```

### 4. Start the ops console

```bash
cd console
npm install
npm run dev
# Open http://localhost:3000
```

## Model Aliases

| Alias | Primary Model | Fallback | Provider |
|-------|--------------|----------|----------|
| `fast` | mock-fast-v1 | mock-fast-v2 | Mock |
| `smart` | mock-smart-v1 | mock-smart-v2 | Mock |
| `auto` | Classifier routes to `fast` or `smart` | — | Mock |
| `gpt` | openai/gpt-4o-mini | openai/gpt-4o | OpenRouter |
| `claude` | anthropic/claude-sonnet-4 | — | OpenRouter |
| `gemini` | google/gemini-2.0-flash-001 | — | OpenRouter |
| `llama` | meta-llama/llama-3.3-70b-instruct | — | OpenRouter |

## Seed API Keys

| Key | Team | Allowlist | RPM | Budget |
|-----|------|-----------|-----|--------|
| `sk-prism-demo-alpha-001` | Alpha | all aliases | 30 | $10/mo |
| `sk-prism-demo-beta-002` | Beta | fast, gpt | 10 | $5/mo |
| `sk-prism-demo-gamma-003` | Gamma | all aliases | 60 | $50/mo |

## API Reference

### Chat Completions

```
POST /v1/chat/completions
Authorization: Bearer <virtual-key>
```

Request body follows the [OpenAI Chat Completions](https://platform.openai.com/docs/api-reference/chat) format.

### Usage API

```
GET /v1/usage/summary?months=1
Authorization: Bearer <virtual-key>

GET /v1/usage/requests?page=0&size=20&status=success
Authorization: Bearer <virtual-key>
```

### Health

```
GET /health
```

### Mock Provider Controls (testing)

```
POST /mock/{provider}/down      # Simulate outage
POST /mock/{provider}/up        # Restore
POST /mock/{provider}/error-rate?percent=30
POST /mock/{provider}/latency?ms=2000
```

## Error Codes

| Code | HTTP | Description |
|------|------|-------------|
| `invalid_key` | 401 | Missing or invalid API key |
| `model_not_allowed` | 403 | Model not in key's allowlist |
| `budget_exhausted` | 402 | Monthly budget exceeded |
| `rate_limited` | 429 | RPM limit exceeded |
| `provider_unavailable` | 503 | All providers failed |
| `upstream_error` | varies | Provider returned an error |

## Testing

### Unit tests (71 tests, no infrastructure needed)

```bash
./mvnw test
```

### Smoke tests (requires running gateway)

```bash
python scripts/smoke_test.py
```

14 end-to-end tests: health, auth, headers, response shape, allowlist enforcement, streaming, cache hit/isolation, auto routing, cost tracking, usage API.

### Load test

```bash
python scripts/load_test.py
```

Concurrent RPM test (20 parallel requests against limit=10), latency percentiles, accounting verification.

### Routing eval

```bash
python scripts/routing_eval.py
```

18-case eval set with short-but-hard and long-but-easy trap prompts. Compares keyword classifier vs length-only baseline.

## Project Structure

```
src/main/java/com/prism/
├── api/                    # REST controllers
│   ├── ChatCompletionController    # Main endpoint
│   ├── UsageController             # Usage & request log APIs
│   ├── HealthController            # Health check
│   └── MockControlController       # Mock provider controls
├── auth/                   # Authentication
│   ├── AuthService                 # SHA-256 key hashing & lookup
│   └── KeySeeder                   # Seeds virtual keys on startup
├── cache/                  # Semantic cache
│   ├── EmbeddingService            # N-gram embeddings & cosine similarity
│   └── SemanticCacheService        # Cache lookup, store, eviction
├── config/                 # Configuration
│   ├── ConfigLoader                # Loads gateway, pricing, seed configs
│   ├── GatewayConfig               # Alias & provider definitions
│   ├── PrismProperties             # Spring config properties
│   └── WebConfig                   # CORS for console
├── dto/                    # Request/response DTOs
├── limits/                 # Rate limiting & budget
│   └── LimitService                # Redis Lua scripts for RPM & budget
├── logging/                # Request logging
├── metering/               # Cost calculation
├── model/                  # JPA entities
├── provider/               # Provider abstraction
│   ├── ProviderAdapter             # Interface
│   ├── ProviderCaller              # Retry, timeout, failover
│   ├── ProviderRegistry            # Model-to-provider mapping
│   ├── mock/                       # Mock providers A & B
│   └── openrouter/                 # OpenRouter HTTP provider
├── reconcile/              # Background tasks
├── repository/             # R2DBC repositories
└── routing/                # Routing & classification
    ├── AliasResolver               # Alias-to-model resolution
    ├── AutoRouter                  # Difficulty-based auto routing
    └── DifficultyClassifier        # Keyword/pattern scoring

console/                    # React + TypeScript + Tailwind ops console
scripts/                    # Python test & eval scripts
eval/                       # Routing eval dataset
docs/                       # Design docs & blueprints
```

## Tech Stack

- **Backend**: Spring Boot 3.3, WebFlux, R2DBC, Flyway
- **Database**: MySQL 8.0 (R2DBC for reactive queries, JDBC for Flyway migrations)
- **Cache/Limits**: Redis 7 (reactive, Lua scripts for atomicity)
- **Frontend**: React 18, TypeScript, Tailwind CSS, Vite
- **Build**: Maven, Docker multi-stage build
- **Testing**: JUnit 5, Reactor Test, TestContainers

## License

MIT
