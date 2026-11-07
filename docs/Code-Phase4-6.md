# Prism: Code — Phase 4 to 6

Continues from [Code-Phase1-3.md](Code-Phase1-3.md). Add these files to the same project.

---

## Routing — Difficulty Classifier (Phase 4)

### `src/main/java/com/prism/routing/DifficultyClassifier.java`

```java
package com.prism.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies prompt difficulty as "easy" or "hard".
 * Must beat a length-only baseline on the eval set.
 */
@Slf4j
@Component
public class DifficultyClassifier {

    // Keywords and patterns that signal a hard prompt
    private static final Set<String> HARD_KEYWORDS = Set.of(
            "analyze", "compare", "contrast", "evaluate", "synthesize",
            "design", "architect", "implement", "debug", "optimize",
            "prove", "derive", "calculate", "solve", "algorithm",
            "trade-off", "tradeoff", "pros and cons", "explain why",
            "step by step", "reasoning", "multi-step", "complex",
            "security", "vulnerability", "code review",
            "mathematical", "equation", "theorem", "proof",
            "distributed", "concurrent", "race condition",
            "refactor", "migration", "scale"
    );

    private static final Set<String> EASY_KEYWORDS = Set.of(
            "what is", "define", "who is", "when was", "where is",
            "translate", "convert", "list", "name", "count",
            "hello", "hi", "thanks", "thank you",
            "yes or no", "true or false",
            "capital of", "color of", "meaning of"
    );

    private static final Pattern CODE_PATTERN =
            Pattern.compile("```|def |function |class |import |public |private ");

    private static final Pattern MULTI_QUESTION =
            Pattern.compile("\\?.*\\?", Pattern.DOTALL);

    /**
     * Returns "easy" or "hard".
     */
    public String classify(List<com.prism.dto.ChatCompletionRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "easy";
        }

        String lastMessage = messages.get(messages.size() - 1).getContent();
        if (lastMessage == null || lastMessage.isBlank()) {
            return "easy";
        }

        String lower = lastMessage.toLowerCase();
        int score = 0;

        // Check hard keywords
        for (String keyword : HARD_KEYWORDS) {
            if (lower.contains(keyword)) {
                score += 3;
            }
        }

        // Check easy keywords
        for (String keyword : EASY_KEYWORDS) {
            if (lower.contains(keyword)) {
                score -= 2;
            }
        }

        // Code presence signals harder task
        if (CODE_PATTERN.matcher(lastMessage).find()) {
            score += 4;
        }

        // Multiple questions signal complexity
        if (MULTI_QUESTION.matcher(lastMessage).find()) {
            score += 2;
        }

        // System prompt complexity
        if (messages.size() > 1) {
            String systemPrompt = messages.stream()
                    .filter(m -> "system".equals(m.getRole()))
                    .map(m -> m.getContent())
                    .findFirst().orElse("");
            if (systemPrompt.length() > 200) {
                score += 2; // complex system prompt
            }
        }

        // Multi-turn conversation
        long userMessages = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .count();
        if (userMessages > 3) {
            score += 2;
        }

        // Sentence count in the last message
        long sentenceCount = lastMessage.chars()
                .filter(c -> c == '.' || c == '?' || c == '!').count();
        if (sentenceCount > 3) {
            score += 1;
        }

        log.debug("Difficulty score for prompt: {} → {}",
                lower.substring(0, Math.min(50, lower.length())), score);

        return score >= 4 ? "hard" : "easy";
    }

    /**
     * Length-only baseline for comparison.
     */
    public String classifyByLength(List<com.prism.dto.ChatCompletionRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) return "easy";
        String last = messages.get(messages.size() - 1).getContent();
        if (last == null) return "easy";
        return last.length() > 200 ? "hard" : "easy";
    }
}
```

### `src/main/java/com/prism/routing/AutoRouter.java`

```java
package com.prism.routing;

import com.prism.config.GatewayConfig;
import com.prism.dto.ChatCompletionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AutoRouter {

    private final DifficultyClassifier classifier;
    private final AliasResolver aliasResolver;

    private static final long CLASSIFIER_TIMEOUT_MS = 500;

    /**
     * Runs the classifier with a timeout. Defaults to "smart" on timeout.
     */
    public RouteResult route(ChatCompletionRequest request) {
        GatewayConfig.AliasConfig autoConfig = aliasResolver.getAutoConfig();
        if (autoConfig == null) {
            // Fallback if auto not configured
            return aliasResolver.resolve("smart");
        }

        String difficulty;
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(
                    () -> classifier.classify(request.getMessages()));
            difficulty = future.get(CLASSIFIER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            executor.shutdown();
        } catch (TimeoutException e) {
            log.warn("Classifier timeout — defaulting to smart");
            difficulty = "hard"; // safe default
        } catch (Exception e) {
            log.warn("Classifier error — defaulting to smart: {}", e.getMessage());
            difficulty = "hard";
        }

        // Map difficulty to alias
        String targetAlias = autoConfig.getRoutes().getOrDefault(difficulty, "smart");
        log.info("Auto-router: difficulty='{}' → alias='{}'", difficulty, targetAlias);

        return aliasResolver.resolve(targetAlias);
    }
}
```

Update the controller to use the auto-router. Add this to the existing `ChatCompletionController`:

### `ChatCompletionController` — updated `processRequest` (replace the auto section)

```java
// In ChatCompletionController, add AutoRouter as a dependency:
// private final AutoRouter autoRouter;

// Replace the route resolution block in processRequest():

// 3. Resolve route
RouteResult route;
if (aliasResolver.isAutoAlias(requestedModel)) {
    route = autoRouter.route(request);
} else {
    route = aliasResolver.resolve(requestedModel);
}

// Check allowlist — check both the original alias and the resolved alias
if (!limitService.isModelAllowed(key, requestedModel)) {
    String resolvedAlias = route.getResolvedAlias();
    if (resolvedAlias == null || !limitService.isModelAllowed(key, resolvedAlias)) {
        return rejectResponse("model_not_allowed",
                "Model '" + requestedModel + "' is not in your allowlist", 403,
                key.getId(), requestedModel, startTime);
    }
}
```

---

## Eval Set (Phase 4)

### `eval/routing_eval_set.json`

```json
[
  {
    "id": "easy-1",
    "prompt": "What is the capital of France?",
    "expected": "easy",
    "category": "factual",
    "trap": null
  },
  {
    "id": "easy-2",
    "prompt": "Translate 'hello' to Spanish",
    "expected": "easy",
    "category": "translation",
    "trap": null
  },
  {
    "id": "easy-3",
    "prompt": "What color is the sky?",
    "expected": "easy",
    "category": "factual",
    "trap": null
  },
  {
    "id": "easy-4",
    "prompt": "List the days of the week",
    "expected": "easy",
    "category": "listing",
    "trap": null
  },
  {
    "id": "easy-5",
    "prompt": "Who is Albert Einstein?",
    "expected": "easy",
    "category": "factual",
    "trap": null
  },
  {
    "id": "hard-1",
    "prompt": "Design a distributed cache system that handles cache invalidation across multiple data centers. Compare eventual consistency vs strong consistency approaches. What are the trade-offs?",
    "expected": "hard",
    "category": "system-design",
    "trap": null
  },
  {
    "id": "hard-2",
    "prompt": "Analyze this code for race conditions and suggest fixes:\n```python\nclass Counter:\n    def __init__(self):\n        self.count = 0\n    def increment(self):\n        temp = self.count\n        self.count = temp + 1\n```\nExplain step by step why this fails under concurrent access.",
    "expected": "hard",
    "category": "debugging",
    "trap": null
  },
  {
    "id": "hard-3",
    "prompt": "Prove that the halting problem is undecidable using a diagonalization argument. Then explain why this matters for static analysis tools.",
    "expected": "hard",
    "category": "theory",
    "trap": null
  },
  {
    "id": "hard-4",
    "prompt": "Implement a B+ tree insertion algorithm that handles node splitting correctly. Compare its performance characteristics with a red-black tree for database indexing.",
    "expected": "hard",
    "category": "algorithm",
    "trap": null
  },
  {
    "id": "hard-5",
    "prompt": "Evaluate the security implications of using JWT tokens for session management versus server-side sessions with Redis. Consider token revocation, XSS, CSRF, and scalability trade-offs.",
    "expected": "hard",
    "category": "security",
    "trap": null
  },
  {
    "id": "trap-short-hard-1",
    "prompt": "Prove P ≠ NP.",
    "expected": "hard",
    "category": "theory",
    "trap": "short-but-hard"
  },
  {
    "id": "trap-short-hard-2",
    "prompt": "Debug this race condition.",
    "expected": "hard",
    "category": "debugging",
    "trap": "short-but-hard"
  },
  {
    "id": "trap-short-hard-3",
    "prompt": "Derive the backpropagation algorithm.",
    "expected": "hard",
    "category": "math",
    "trap": "short-but-hard"
  },
  {
    "id": "trap-short-hard-4",
    "prompt": "Optimize this O(n³) algorithm to O(n log n).",
    "expected": "hard",
    "category": "algorithm",
    "trap": "short-but-hard"
  },
  {
    "id": "trap-long-easy-1",
    "prompt": "I have a very long document here that I need you to read through carefully. It is a recipe for chocolate chip cookies. The ingredients are: 2 and 1/4 cups all-purpose flour, 1 teaspoon baking soda, 1 teaspoon salt, 1 cup (2 sticks) butter softened, 3/4 cup granulated sugar, 3/4 cup packed brown sugar, 1 teaspoon vanilla extract, 2 large eggs, 2 cups chocolate chips. What is the first ingredient listed?",
    "expected": "easy",
    "category": "reading",
    "trap": "long-but-easy"
  },
  {
    "id": "trap-long-easy-2",
    "prompt": "Here is a long passage about the history of computing: Charles Babbage conceived the first mechanical computer in the early 19th century. Ada Lovelace wrote the first algorithm. Alan Turing formalized computation. John von Neumann designed stored-program architecture. Grace Hopper developed the first compiler. Dennis Ritchie created the C programming language. Tim Berners-Lee invented the World Wide Web. Linus Torvalds created Linux. Larry Page and Sergey Brin founded Google. Steve Jobs co-founded Apple. What year did Tim Berners-Lee invent the World Wide Web? Just give me the year.",
    "expected": "easy",
    "category": "reading",
    "trap": "long-but-easy"
  },
  {
    "id": "trap-long-easy-3",
    "prompt": "I'm going to give you a really detailed description of my cat. She is orange and white, about 10 pounds, has green eyes, loves to sleep on the windowsill, enjoys tuna treats, is afraid of the vacuum cleaner, was adopted from a shelter three years ago, her name is Whiskers, she is an indoor cat, she has been spayed, she is up to date on vaccinations, and she recently went to the vet for a checkup. What is my cat's name?",
    "expected": "easy",
    "category": "reading",
    "trap": "long-but-easy"
  },
  {
    "id": "trap-long-easy-4",
    "prompt": "The following is a detailed list of countries in Europe: France, Germany, Italy, Spain, Portugal, Netherlands, Belgium, Luxembourg, Austria, Switzerland, Greece, Sweden, Norway, Denmark, Finland, Iceland, Ireland, United Kingdom, Poland, Czech Republic, Slovakia, Hungary, Romania, Bulgaria, Croatia, Slovenia, Estonia, Latvia, Lithuania, Malta, Cyprus. How many countries did I list? Just count them.",
    "expected": "easy",
    "category": "counting",
    "trap": "long-but-easy"
  }
]
```

---

## Routing Eval Script (Phase 4)

### `scripts/routing_eval.py`

```python
#!/usr/bin/env python3
"""
Routing eval: tests the auto-classifier against the labeled eval set.
Reports per-case and overall accuracy for both the classifier and the
length-only baseline.

Usage:
    python scripts/routing_eval.py [--gateway http://localhost:8080]
"""

import json
import sys
import argparse
import requests
from pathlib import Path

def load_eval_set():
    path = Path(__file__).parent.parent / "eval" / "routing_eval_set.json"
    with open(path) as f:
        return json.load(f)

def classify_via_gateway(gateway_url, prompt, api_key):
    """Send a request with model=auto and check what model was used."""
    resp = requests.post(
        f"{gateway_url}/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        },
        json={
            "model": "auto",
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "temperature": 0
        },
        timeout=30
    )

    if resp.status_code != 200:
        return None, resp.status_code

    provider = resp.headers.get("x-prism-provider", "")
    # Determine if it routed to fast or smart based on the model used
    body = resp.json()
    model = body.get("model", "")

    if "fast" in model.lower():
        return "easy", resp.status_code
    elif "smart" in model.lower():
        return "hard", resp.status_code
    else:
        return "unknown", resp.status_code

def length_baseline(prompt):
    """Length-only baseline: >200 chars = hard."""
    return "hard" if len(prompt) > 200 else "easy"

def run_eval(gateway_url, api_key):
    eval_set = load_eval_set()

    classifier_correct = 0
    baseline_correct = 0
    total = len(eval_set)
    results = []

    print(f"\n{'='*80}")
    print(f"Prism Routing Eval — {total} cases")
    print(f"{'='*80}\n")
    print(f"{'ID':<25} {'Expected':<8} {'Classifier':<12} {'Baseline':<12} {'Trap':<15}")
    print(f"{'-'*25} {'-'*8} {'-'*12} {'-'*12} {'-'*15}")

    for case in eval_set:
        expected = case["expected"]
        prompt = case["prompt"]
        trap = case.get("trap") or ""

        # Classifier via gateway
        classified, status = classify_via_gateway(gateway_url, prompt, api_key)
        classifier_ok = classified == expected

        # Baseline
        baseline = length_baseline(prompt)
        baseline_ok = baseline == expected

        if classifier_ok:
            classifier_correct += 1
        if baseline_ok:
            baseline_correct += 1

        status_icon = "✓" if classifier_ok else "✗"
        baseline_icon = "✓" if baseline_ok else "✗"

        print(f"{case['id']:<25} {expected:<8} "
              f"{status_icon} {classified or 'err':<10} "
              f"{baseline_icon} {baseline:<10} {trap}")

        results.append({
            "id": case["id"],
            "expected": expected,
            "classified": classified,
            "baseline": baseline,
            "classifier_correct": classifier_ok,
            "baseline_correct": baseline_ok,
            "trap": trap
        })

    classifier_accuracy = classifier_correct / total * 100
    baseline_accuracy = baseline_correct / total * 100
    beats_baseline = classifier_accuracy > baseline_accuracy

    print(f"\n{'='*80}")
    print(f"Results:")
    print(f"  Classifier: {classifier_correct}/{total} = {classifier_accuracy:.1f}%")
    print(f"  Baseline:   {baseline_correct}/{total} = {baseline_accuracy:.1f}%")
    print(f"  Beats baseline: {'YES ✓' if beats_baseline else 'NO ✗'}")
    print(f"{'='*80}\n")

    # Per-category breakdown
    categories = {}
    for r in results:
        cat = [c for c in eval_set if c["id"] == r["id"]][0].get("category", "other")
        if cat not in categories:
            categories[cat] = {"total": 0, "classifier_correct": 0, "baseline_correct": 0}
        categories[cat]["total"] += 1
        if r["classifier_correct"]:
            categories[cat]["classifier_correct"] += 1
        if r["baseline_correct"]:
            categories[cat]["baseline_correct"] += 1

    print("Per-category breakdown:")
    for cat, stats in sorted(categories.items()):
        c_acc = stats["classifier_correct"] / stats["total"] * 100
        b_acc = stats["baseline_correct"] / stats["total"] * 100
        print(f"  {cat:<15} classifier={c_acc:.0f}%  baseline={b_acc:.0f}%")

    return beats_baseline

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    parser.add_argument("--key", default="sk-prism-demo-alpha-001")
    args = parser.parse_args()

    success = run_eval(args.gateway, args.key)
    sys.exit(0 if success else 1)
```

---

## Semantic Cache (Phase 5)

### `src/main/java/com/prism/cache/EmbeddingService.java`

```java
package com.prism.cache;

import com.prism.dto.ChatCompletionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Computes embeddings for semantic cache matching.
 *
 * This is a local embedding implementation using a simple hash-based approach.
 * Replace with OpenAI/HuggingFace embeddings for production-quality matching.
 */
@Slf4j
@Service
public class EmbeddingService {

    private static final int EMBEDDING_DIM = 128;

    /**
     * Normalizes a request to the text used for cache matching.
     */
    public String normalizeRequest(ChatCompletionRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("model:").append(request.getModel()).append("\n");
        for (ChatCompletionRequest.Message msg : request.getMessages()) {
            sb.append(msg.getRole()).append(":").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Computes a prompt hash for exact-match lookup.
     */
    public String computePromptHash(String normalizedText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedText.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Computes an embedding vector. Returns as byte array for storage.
     *
     * This uses a simple token-frequency approach for the mock implementation.
     * Replace with a real embedding API for production semantic matching.
     */
    public float[] computeEmbedding(String text) {
        float[] embedding = new float[EMBEDDING_DIM];

        // Simple character n-gram frequency approach
        String lower = text.toLowerCase();
        for (int i = 0; i < lower.length() - 2; i++) {
            int hash = lower.substring(i, i + 3).hashCode();
            int idx = Math.abs(hash) % EMBEDDING_DIM;
            embedding[idx] += 1.0f;
        }

        // Normalize to unit vector
        float norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    /**
     * Cosine similarity between two embedding vectors.
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;

        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Serialize float array to bytes for DB storage.
     */
    public byte[] toBytes(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4);
        for (float v : embedding) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /**
     * Deserialize bytes back to float array.
     */
    public float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] embedding = new float[bytes.length / 4];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }
}
```

### `src/main/java/com/prism/cache/SemanticCacheService.java`

```java
package com.prism.cache;

import com.prism.config.PrismProperties;
import com.prism.dto.ChatCompletionRequest;
import com.prism.dto.ChatCompletionResponse;
import com.prism.model.CacheEntry;
import com.prism.repository.CacheEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final CacheEntryRepository cacheRepository;
    private final EmbeddingService embeddingService;
    private final PrismProperties properties;
    private final ObjectMapper objectMapper;

    public record CacheHit(
            ChatCompletionResponse response,
            int promptTokens,
            int completionTokens
    ) {}

    /**
     * Check if a request is cacheable:
     * - Non-streaming
     * - Temperature 0
     */
    public boolean isCacheable(ChatCompletionRequest request) {
        return !request.isStream() && request.getTemperature() == 0.0;
    }

    /**
     * Look up a cached response for this key. Key-scoped: only searches
     * entries belonging to this key ID.
     */
    public Mono<Optional<CacheHit>> lookup(Long keyId, ChatCompletionRequest request) {
        String normalized = embeddingService.normalizeRequest(request);
        float[] queryEmbedding = embeddingService.computeEmbedding(normalized);

        return cacheRepository.findByKeyIdOrderByCreatedAtDesc(keyId)
                .filter(entry -> entry.getExpiresAt().isAfter(LocalDateTime.now()))
                .collectList()
                .map(entries -> {
                    double bestSimilarity = 0;
                    CacheEntry bestEntry = null;

                    for (CacheEntry entry : entries) {
                        if (entry.getEmbedding() == null) continue;
                        float[] entryEmbedding = embeddingService.fromBytes(entry.getEmbedding());
                        double similarity = embeddingService.cosineSimilarity(
                                queryEmbedding, entryEmbedding);

                        if (similarity > bestSimilarity) {
                            bestSimilarity = similarity;
                            bestEntry = entry;
                        }
                    }

                    if (bestEntry != null &&
                        bestSimilarity >= properties.getCache().getSimilarityThreshold()) {
                        log.info("Cache HIT for key={} similarity={:.4f}",
                                keyId, bestSimilarity);
                        try {
                            ChatCompletionResponse response = objectMapper.readValue(
                                    bestEntry.getResponseBody(), ChatCompletionResponse.class);
                            return Optional.of(new CacheHit(
                                    response,
                                    bestEntry.getPromptTokens(),
                                    bestEntry.getCompletionTokens()));
                        } catch (Exception e) {
                            log.warn("Failed to deserialize cached response", e);
                            return Optional.<CacheHit>empty();
                        }
                    }

                    log.debug("Cache MISS for key={} bestSimilarity={:.4f}",
                            keyId, bestSimilarity);
                    return Optional.<CacheHit>empty();
                });
    }

    /**
     * Store a response in the cache, scoped to the key.
     */
    public Mono<Void> store(Long keyId, ChatCompletionRequest request,
                            ChatCompletionResponse response,
                            int promptTokens, int completionTokens) {
        String normalized = embeddingService.normalizeRequest(request);
        float[] embedding = embeddingService.computeEmbedding(normalized);

        return enforceEntryLimit(keyId)
                .then(Mono.defer(() -> {
                    try {
                        String responseJson = objectMapper.writeValueAsString(response);
                        CacheEntry entry = CacheEntry.builder()
                                .keyId(keyId)
                                .promptHash(embeddingService.computePromptHash(normalized))
                                .embedding(embeddingService.toBytes(embedding))
                                .model(request.getModel())
                                .responseBody(responseJson)
                                .promptTokens(promptTokens)
                                .completionTokens(completionTokens)
                                .expiresAt(LocalDateTime.now().plusMinutes(
                                        properties.getCache().getTtlMinutes()))
                                .build();

                        return cacheRepository.save(entry).then();
                    } catch (Exception e) {
                        log.warn("Failed to store cache entry", e);
                        return Mono.empty();
                    }
                }));
    }

    private Mono<Void> enforceEntryLimit(Long keyId) {
        int maxEntries = properties.getCache().getMaxEntriesPerKey();
        return cacheRepository.countByKeyId(keyId)
                .flatMap(count -> {
                    if (count >= maxEntries) {
                        int toEvict = (int) (count - maxEntries + 1);
                        log.info("Evicting {} oldest cache entries for key={}", toEvict, keyId);
                        return cacheRepository.evictOldest(keyId, toEvict);
                    }
                    return Mono.empty();
                });
    }
}
```

### Update `ChatCompletionController` — add cache integration

Add to the controller's dependencies and update the non-streaming flow:

```java
// Add to controller constructor:
// private final SemanticCacheService cacheService;

// In handleNonStreaming(), before calling the provider, add cache lookup:

private Mono<ResponseEntity<?>> handleNonStreaming(VirtualKey key,
                                                    ChatCompletionRequest request,
                                                    RouteResult route,
                                                    long estimatedCost,
                                                    long startTime) {
    // Cache lookup (Phase 5)
    if (cacheService.isCacheable(request)) {
        return cacheService.lookup(key.getId(), request)
                .flatMap(optHit -> {
                    if (optHit.isPresent()) {
                        // Cache HIT
                        SemanticCacheService.CacheHit hit = optHit.get();
                        long latency = System.currentTimeMillis() - startTime;

                        // Release full reservation (cache hit costs nothing)
                        Mono<Void> release = limitService.releaseBudget(key, estimatedCost);

                        RequestLog logEntry = RequestLog.builder()
                                .keyId(key.getId())
                                .requestModel(request.getModel())
                                .resolvedModel(route.getPrimaryModel())
                                .status("success")
                                .cacheResult("hit")
                                .latencyMs(latency)
                                .build();

                        UsageRecord usage = UsageRecord.builder()
                                .keyId(key.getId())
                                .model(request.getModel())
                                .cacheResult("hit")
                                .tokensSaved(hit.promptTokens() + hit.completionTokens())
                                .latencyMs(latency)
                                .status("success")
                                .build();

                        return release
                                .then(requestLogger.logAndRecord(logEntry, usage))
                                .thenReturn(ResponseEntity.ok()
                                        .header("x-prism-provider", "cache")
                                        .header("x-prism-cache", "hit")
                                        .header("x-prism-fallback", "false")
                                        .header("x-prism-cost-usd", "0")
                                        .body(hit.response()));
                    }

                    // Cache MISS — proceed to provider
                    return callProviderAndRespond(key, request, route,
                            estimatedCost, startTime);
                });
    }

    // Not cacheable — go directly to provider
    return callProviderAndRespond(key, request, route, estimatedCost, startTime);
}

// Extract existing provider-call logic into:
private Mono<ResponseEntity<?>> callProviderAndRespond(VirtualKey key,
                                                        ChatCompletionRequest request,
                                                        RouteResult route,
                                                        long estimatedCost,
                                                        long startTime) {
    return providerCaller.complete(request, route.getPrimaryModel(), route.getFallbacks())
            .flatMap(result -> {
                long latency = System.currentTimeMillis() - startTime;
                ChatCompletionResponse response = result.response();

                int promptTokens = response.getUsage().getPromptTokens();
                int completionTokens = response.getUsage().getCompletionTokens();
                long actualCost = costCalculator.computeCost(
                        result.modelId(), promptTokens, completionTokens);

                long excess = estimatedCost - actualCost;
                Mono<Void> release = limitService.releaseBudget(key, excess);

                // Store in cache if cacheable (Phase 5)
                Mono<Void> cacheWrite = cacheService.isCacheable(request) ?
                        cacheService.store(key.getId(), request, response,
                                promptTokens, completionTokens) :
                        Mono.empty();

                RequestLog logEntry = RequestLog.builder()
                        .keyId(key.getId())
                        .requestModel(request.getModel())
                        .resolvedModel(route.getPrimaryModel())
                        .servingModel(result.modelId())
                        .provider(result.providerId())
                        .status("success")
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .costMicroUsd(actualCost)
                        .cacheResult("miss")
                        .fallback(result.fallback())
                        .fallbackReason(result.fallbackReason())
                        .latencyMs(latency)
                        .build();

                UsageRecord usage = UsageRecord.builder()
                        .keyId(key.getId())
                        .model(result.modelId())
                        .provider(result.providerId())
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .costMicroUsd(actualCost)
                        .cacheResult("miss")
                        .fallback(result.fallback())
                        .latencyMs(latency)
                        .status("success")
                        .build();

                return release
                        .then(cacheWrite)
                        .then(requestLogger.logAndRecord(logEntry, usage))
                        .thenReturn(ResponseEntity.ok()
                                .header("x-prism-provider", result.providerId())
                                .header("x-prism-cache", "miss")
                                .header("x-prism-fallback", String.valueOf(result.fallback()))
                                .header("x-prism-cost-usd",
                                        String.format("%.6f", actualCost / 1_000_000.0))
                                .body(response));
            })
            // error handling same as before
            ;
}
```

---

## Usage API (Phase 6)

### `src/main/java/com/prism/api/UsageController.java`

```java
package com.prism.api;

import com.prism.model.RequestLog;
import com.prism.model.UsageRecord;
import com.prism.repository.RequestLogRepository;
import com.prism.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageRecordRepository usageRepository;
    private final RequestLogRepository logRepository;

    /**
     * Usage summary for a key in a given month.
     */
    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary(
            @RequestParam Long keyId,
            @RequestParam(required = false) String month) {

        YearMonth ym = month != null ? YearMonth.parse(month) : YearMonth.now();
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();

        Mono<Long> totalCost = usageRepository.sumCostByKeyIdAndPeriod(keyId, start, end);
        Mono<Long> cacheHits = usageRepository.countByCacheResult(keyId, "hit", start, end);
        Mono<Long> cacheMisses = usageRepository.countByCacheResult(keyId, "miss", start, end);

        Flux<UsageRecord> records =
                usageRepository.findByKeyIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        keyId, start, end);

        return Mono.zip(totalCost, cacheHits, cacheMisses, records.collectList())
                .map(tuple -> {
                    long cost = tuple.getT1();
                    long hits = tuple.getT2();
                    long misses = tuple.getT3();
                    var recs = tuple.getT4();

                    int totalRequests = recs.size();
                    int totalPromptTokens = recs.stream()
                            .mapToInt(UsageRecord::getPromptTokens).sum();
                    int totalCompletionTokens = recs.stream()
                            .mapToInt(UsageRecord::getCompletionTokens).sum();
                    long fallbackCount = recs.stream()
                            .filter(UsageRecord::isFallback).count();

                    double hitRate = (hits + misses) > 0 ?
                            (double) hits / (hits + misses) * 100 : 0;

                    Map<String, Object> result = new HashMap<>();
                    result.put("keyId", keyId);
                    result.put("month", ym.toString());
                    result.put("totalRequests", totalRequests);
                    result.put("totalPromptTokens", totalPromptTokens);
                    result.put("totalCompletionTokens", totalCompletionTokens);
                    result.put("totalCostMicroUsd", cost);
                    result.put("totalCostUsd",
                            String.format("%.6f", cost / 1_000_000.0));
                    result.put("cacheHits", hits);
                    result.put("cacheMisses", misses);
                    result.put("cacheHitRate", String.format("%.1f%%", hitRate));
                    result.put("fallbackCount", fallbackCount);
                    return result;
                });
    }

    /**
     * Recent requests with pagination.
     */
    @GetMapping("/requests")
    public Flux<RequestLog> recentRequests(
            @RequestParam(required = false) Long keyId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));

        if (keyId != null) {
            return logRepository.findByKeyIdOrderByCreatedAtDesc(keyId, pageable);
        }
        if (status != null) {
            return logRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        return logRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
```

---

## Reconciliation Sweep (Phase 2/6)

### `src/main/java/com/prism/reconcile/ReservationSweep.java`

```java
package com.prism.reconcile;

import com.prism.repository.CacheEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationSweep {

    private final CacheEntryRepository cacheRepository;

    /**
     * Clean up expired cache entries every 10 minutes.
     */
    @Scheduled(fixedRate = 600_000)
    public void cleanExpiredCacheEntries() {
        cacheRepository.deleteExpired()
                .doOnSuccess(v -> log.debug("Expired cache entries cleaned"))
                .subscribe();
    }
}
```

---

## Smoke Test Script (Phase 6)

### `scripts/smoke_test.py`

```python
#!/usr/bin/env python3
"""
Smoke test for Prism gateway.
Verifies all major features work end-to-end.

Usage:
    python scripts/smoke_test.py [--gateway http://localhost:8080]
"""

import json
import sys
import time
import argparse
import requests

ALPHA_KEY = "sk-prism-demo-alpha-001"
BETA_KEY = "sk-prism-demo-beta-002"
GAMMA_KEY = "sk-prism-demo-gamma-003"

def test(name, func, gateway):
    try:
        func(gateway)
        print(f"  ✓ {name}")
        return True
    except AssertionError as e:
        print(f"  ✗ {name}: {e}")
        return False
    except Exception as e:
        print(f"  ✗ {name}: EXCEPTION — {e}")
        return False

def api(gateway, key, model="fast", stream=False, messages=None, temperature=1.0):
    if messages is None:
        messages = [{"role": "user", "content": "Hello, how are you?"}]
    return requests.post(
        f"{gateway}/v1/chat/completions",
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json={"model": model, "messages": messages, "stream": stream,
              "temperature": temperature},
        timeout=30,
        stream=stream
    )

# --- Health ---

def test_health(gw):
    r = requests.get(f"{gw}/health", timeout=5)
    assert r.status_code == 200, f"status={r.status_code}"
    body = r.json()
    assert body.get("mysql") == "up", "MySQL not up"
    assert body.get("redis") == "up", "Redis not up"

# --- Auth ---

def test_valid_key(gw):
    r = api(gw, ALPHA_KEY)
    assert r.status_code == 200, f"status={r.status_code}"

def test_invalid_key(gw):
    r = api(gw, "sk-invalid-key-xyz")
    assert r.status_code == 401, f"expected 401, got {r.status_code}"
    body = r.json()
    assert body["error"]["code"] == "invalid_key"

def test_missing_key(gw):
    r = requests.post(f"{gw}/v1/chat/completions",
                      json={"model": "fast", "messages": [{"role": "user", "content": "hi"}]},
                      timeout=10)
    assert r.status_code == 401

# --- Headers ---

def test_header_contract(gw):
    r = api(gw, ALPHA_KEY)
    assert r.status_code == 200
    for h in ["x-prism-provider", "x-prism-cache", "x-prism-fallback", "x-prism-cost-usd"]:
        assert h in r.headers, f"Missing header: {h}"

# --- OpenAI shape ---

def test_response_shape(gw):
    r = api(gw, ALPHA_KEY)
    body = r.json()
    assert "id" in body
    assert body.get("object") == "chat.completion"
    assert "choices" in body and len(body["choices"]) > 0
    assert "message" in body["choices"][0]
    assert "usage" in body

# --- Model allowlist ---

def test_model_not_allowed(gw):
    # Beta key only allows "fast"
    r = api(gw, BETA_KEY, model="smart")
    assert r.status_code == 403, f"expected 403, got {r.status_code}"
    body = r.json()
    assert body["error"]["code"] == "model_not_allowed"

# --- Streaming ---

def test_streaming(gw):
    r = api(gw, ALPHA_KEY, stream=True)
    assert r.status_code == 200
    chunks = []
    for line in r.iter_lines(decode_unicode=True):
        if line.startswith("data: "):
            data = line[6:]
            chunks.append(data)
            if data == "[DONE]":
                break
    assert len(chunks) > 1, "Expected multiple chunks"
    assert chunks[-1] == "[DONE]", "Stream must end with [DONE]"

# --- Semantic cache ---

def test_cache_hit(gw):
    msg = [{"role": "user", "content": "What is the capital of France?"}]

    # First request — cache miss
    r1 = api(gw, ALPHA_KEY, messages=msg, temperature=0)
    assert r1.status_code == 200
    assert r1.headers.get("x-prism-cache") == "miss"

    # Second request — same prompt, should be cache hit
    r2 = api(gw, ALPHA_KEY, messages=msg, temperature=0)
    assert r2.status_code == 200
    assert r2.headers.get("x-prism-cache") == "hit", \
        f"Expected cache hit, got {r2.headers.get('x-prism-cache')}"

def test_cache_isolation(gw):
    msg = [{"role": "user", "content": "Unique prompt for cache isolation test " + str(time.time())}]

    # Store in alpha's cache
    r1 = api(gw, ALPHA_KEY, messages=msg, temperature=0)
    assert r1.status_code == 200

    # Beta should NOT see alpha's cache
    r2 = api(gw, BETA_KEY, messages=msg, temperature=0)
    # Beta can only use "fast", so if model is fast it should be a miss
    if r2.status_code == 200:
        assert r2.headers.get("x-prism-cache") == "miss", \
            "Cross-tenant cache leak detected!"

# --- Auto routing ---

def test_auto_routing(gw):
    r = api(gw, ALPHA_KEY, model="auto",
            messages=[{"role": "user", "content": "What is 2+2?"}])
    assert r.status_code == 200
    assert "x-prism-provider" in r.headers

# --- Cost ---

def test_cost_header(gw):
    r = api(gw, ALPHA_KEY)
    cost = r.headers.get("x-prism-cost-usd", "0")
    assert float(cost) >= 0, f"Invalid cost: {cost}"

# --- Usage API ---

def test_usage_summary(gw):
    r = requests.get(f"{gw}/v1/usage/summary?keyId=1", timeout=5)
    assert r.status_code == 200
    body = r.json()
    assert "totalRequests" in body
    assert "cacheHitRate" in body

def test_recent_requests(gw):
    r = requests.get(f"{gw}/v1/usage/requests?page=0&size=5", timeout=5)
    assert r.status_code == 200

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    args = parser.parse_args()
    gw = args.gateway

    print(f"\nPrism Smoke Test — {gw}")
    print("=" * 50)

    tests = [
        ("Health check", test_health),
        ("Valid key authenticates", test_valid_key),
        ("Invalid key rejected", test_invalid_key),
        ("Missing key rejected", test_missing_key),
        ("Header contract present", test_header_contract),
        ("Response is OpenAI-shaped", test_response_shape),
        ("Model not allowed rejected", test_model_not_allowed),
        ("Streaming works", test_streaming),
        ("Semantic cache hit", test_cache_hit),
        ("Cache isolation (no cross-key leak)", test_cache_isolation),
        ("Auto routing works", test_auto_routing),
        ("Cost header present", test_cost_header),
        ("Usage summary API", test_usage_summary),
        ("Recent requests API", test_recent_requests),
    ]

    passed = sum(1 for name, func in tests if test(name, func, gw))
    total = len(tests)

    print(f"\n{'=' * 50}")
    print(f"Results: {passed}/{total} passed")
    print(f"{'=' * 50}\n")

    sys.exit(0 if passed == total else 1)

if __name__ == "__main__":
    main()
```

---

## Load Test Script (Phase 6)

### `scripts/load_test.py`

```python
#!/usr/bin/env python3
"""
Load test for Prism gateway.
Tests concurrent requests against RPM and budget limits.

Usage:
    python scripts/load_test.py [--gateway http://localhost:8080]
"""

import json
import sys
import time
import argparse
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter

ALPHA_KEY = "sk-prism-demo-alpha-001"
BETA_KEY = "sk-prism-demo-beta-002"

def send_request(gateway, key, model="fast", idx=0):
    start = time.time()
    try:
        r = requests.post(
            f"{gateway}/v1/chat/completions",
            headers={"Authorization": f"Bearer {key}",
                     "Content-Type": "application/json"},
            json={"model": model,
                  "messages": [{"role": "user",
                                "content": f"Load test request {idx}"}],
                  "stream": False},
            timeout=30
        )
        latency = (time.time() - start) * 1000
        cost = float(r.headers.get("x-prism-cost-usd", "0"))
        return {
            "status": r.status_code,
            "latency_ms": latency,
            "cost_usd": cost,
            "error_code": r.json().get("error", {}).get("code") if r.status_code != 200 else None
        }
    except Exception as e:
        return {
            "status": 0,
            "latency_ms": (time.time() - start) * 1000,
            "cost_usd": 0,
            "error_code": str(e)
        }

def run_rpm_test(gateway, key, rpm_limit, num_requests):
    """Send num_requests concurrently and check exactly rpm_limit are admitted."""
    print(f"\n--- RPM Test: {num_requests} concurrent requests, limit={rpm_limit} ---")

    results = []
    with ThreadPoolExecutor(max_workers=num_requests) as pool:
        futures = [pool.submit(send_request, gateway, key, "fast", i)
                   for i in range(num_requests)]
        for f in as_completed(futures):
            results.append(f.result())

    status_counts = Counter(r["status"] for r in results)
    admitted = status_counts.get(200, 0)
    rejected = status_counts.get(429, 0)
    other = sum(v for k, v in status_counts.items() if k not in (200, 429))

    print(f"  Admitted: {admitted}")
    print(f"  Rate-limited (429): {rejected}")
    print(f"  Other: {other}")

    over_admission = max(0, admitted - rpm_limit)
    print(f"  Over-admission: {over_admission}")

    return {
        "admitted": admitted,
        "rejected": rejected,
        "over_admission": over_admission,
        "results": results
    }

def run_latency_test(gateway, key, num_requests=50):
    """Send requests and measure latency percentiles."""
    print(f"\n--- Latency Test: {num_requests} sequential requests ---")

    latencies = []
    for i in range(num_requests):
        result = send_request(gateway, key, "fast", i)
        if result["status"] == 200:
            latencies.append(result["latency_ms"])

    if not latencies:
        print("  No successful requests!")
        return {}

    latencies.sort()
    n = len(latencies)

    stats = {
        "count": n,
        "p50": latencies[int(n * 0.5)],
        "p95": latencies[int(n * 0.95)],
        "p99": latencies[int(n * 0.99)],
        "min": latencies[0],
        "max": latencies[-1],
        "avg": sum(latencies) / n
    }

    print(f"  Count: {stats['count']}")
    print(f"  p50:   {stats['p50']:.1f} ms")
    print(f"  p95:   {stats['p95']:.1f} ms")
    print(f"  p99:   {stats['p99']:.1f} ms")
    print(f"  min:   {stats['min']:.1f} ms")
    print(f"  max:   {stats['max']:.1f} ms")
    print(f"  avg:   {stats['avg']:.1f} ms")

    return stats

def check_accounting(gateway, key_id):
    """Verify accounting totals match usage records."""
    print(f"\n--- Accounting Check: keyId={key_id} ---")

    r = requests.get(f"{gateway}/v1/usage/summary?keyId={key_id}", timeout=5)
    if r.status_code != 200:
        print(f"  Failed to get usage summary: {r.status_code}")
        return False

    summary = r.json()
    print(f"  Total requests: {summary.get('totalRequests', 'N/A')}")
    print(f"  Total cost: {summary.get('totalCostUsd', 'N/A')} USD")
    print(f"  Cache hit rate: {summary.get('cacheHitRate', 'N/A')}")
    return True

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    args = parser.parse_args()
    gw = args.gateway

    print(f"\nPrism Load Test — {gw}")
    print("=" * 60)

    # RPM test with beta key (limit = 10)
    rpm_result = run_rpm_test(gw, BETA_KEY, rpm_limit=10, num_requests=20)

    # Wait for RPM window to reset
    print("\n  Waiting 65s for RPM window reset...")
    time.sleep(65)

    # Latency test with alpha key
    latency_stats = run_latency_test(gw, ALPHA_KEY, num_requests=30)

    # Accounting
    check_accounting(gw, key_id=1)
    check_accounting(gw, key_id=2)

    # Summary
    print(f"\n{'=' * 60}")
    print("Load Test Summary:")
    print(f"  Over-admission: {rpm_result['over_admission']}")
    print(f"  p50 latency: {latency_stats.get('p50', 'N/A')} ms")
    print(f"  p99 latency: {latency_stats.get('p99', 'N/A')} ms")

    success = rpm_result["over_admission"] == 0
    print(f"  PASS: {'YES' if success else 'NO'}")
    print(f"{'=' * 60}\n")

    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
```

---

## Ops Console (Phase 6)

### `console/package.json`

```json
{
  "name": "prism-console",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^4.3.4",
    "autoprefixer": "^10.4.20",
    "postcss": "^8.4.49",
    "tailwindcss": "^3.4.15",
    "typescript": "^5.6.3",
    "vite": "^6.0.1"
  }
}
```

### `console/index.html`

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Prism Ops Console</title>
</head>
<body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
</body>
</html>
```

### `console/src/main.tsx`

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
```

### `console/src/index.css`

```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

### `console/src/App.tsx`

```tsx
import React, { useState } from 'react';
import UsageView from './views/UsageView';
import RequestsView from './views/RequestsView';
import CacheView from './views/CacheView';

type Tab = 'usage' | 'requests' | 'cache';

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('usage');

  const tabs: { id: Tab; label: string }[] = [
    { id: 'usage', label: 'Usage by Key' },
    { id: 'requests', label: 'Recent Requests' },
    { id: 'cache', label: 'Cache Hit Rate' },
  ];

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-indigo-600 text-white px-6 py-4">
        <h1 className="text-2xl font-bold">Prism Ops Console</h1>
        <p className="text-indigo-200 text-sm">LLM Gateway & Semantic Cache</p>
      </header>

      <nav className="bg-white border-b px-6">
        <div className="flex space-x-4">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`py-3 px-4 text-sm font-medium border-b-2 transition-colors ${
                activeTab === tab.id
                  ? 'border-indigo-600 text-indigo-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </nav>

      <main className="p-6 max-w-7xl mx-auto">
        {activeTab === 'usage' && <UsageView />}
        {activeTab === 'requests' && <RequestsView />}
        {activeTab === 'cache' && <CacheView />}
      </main>
    </div>
  );
}
```

### `console/src/views/UsageView.tsx`

```tsx
import React, { useEffect, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

interface UsageSummary {
  keyId: number;
  month: string;
  totalRequests: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalCostUsd: string;
  cacheHits: number;
  cacheMisses: number;
  cacheHitRate: string;
  fallbackCount: number;
}

export default function UsageView() {
  const [keyIds] = useState([1, 2, 3]);
  const [summaries, setSummaries] = useState<UsageSummary[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all(
      keyIds.map((id) =>
        fetch(`${API_BASE}/v1/usage/summary?keyId=${id}`)
          .then((r) => r.json())
          .catch(() => null)
      )
    ).then((results) => {
      setSummaries(results.filter(Boolean));
      setLoading(false);
    });
  }, []);

  if (loading) return <p className="text-gray-500">Loading...</p>;

  return (
    <div>
      <h2 className="text-xl font-semibold mb-4">Usage by Key</h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {summaries.map((s) => (
          <div key={s.keyId} className="bg-white rounded-lg shadow p-6">
            <h3 className="text-lg font-medium text-gray-900 mb-3">
              Key #{s.keyId}
            </h3>
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-gray-500">Requests</dt>
                <dd className="font-medium">{s.totalRequests}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Prompt Tokens</dt>
                <dd className="font-medium">{s.totalPromptTokens?.toLocaleString()}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Completion Tokens</dt>
                <dd className="font-medium">{s.totalCompletionTokens?.toLocaleString()}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Total Cost</dt>
                <dd className="font-medium text-green-600">${s.totalCostUsd}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Cache Hit Rate</dt>
                <dd className="font-medium">{s.cacheHitRate}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Fallbacks</dt>
                <dd className="font-medium">{s.fallbackCount}</dd>
              </div>
            </dl>
          </div>
        ))}
      </div>
    </div>
  );
}
```

### `console/src/views/RequestsView.tsx`

```tsx
import React, { useEffect, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

interface RequestEntry {
  id: number;
  keyId: number;
  requestModel: string;
  servingModel: string;
  provider: string;
  status: string;
  errorCode: string | null;
  promptTokens: number;
  completionTokens: number;
  costMicroUsd: number;
  cacheResult: string;
  fallback: boolean;
  latencyMs: number;
  createdAt: string;
}

export default function RequestsView() {
  const [requests, setRequests] = useState<RequestEntry[]>([]);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetch(`${API_BASE}/v1/usage/requests?page=${page}&size=20`)
      .then((r) => r.json())
      .then((data) => {
        setRequests(data);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, [page]);

  return (
    <div>
      <h2 className="text-xl font-semibold mb-4">Recent Requests</h2>
      {loading ? (
        <p className="text-gray-500">Loading...</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="min-w-full bg-white rounded-lg shadow text-sm">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left">Time</th>
                  <th className="px-4 py-3 text-left">Key</th>
                  <th className="px-4 py-3 text-left">Model</th>
                  <th className="px-4 py-3 text-left">Provider</th>
                  <th className="px-4 py-3 text-left">Status</th>
                  <th className="px-4 py-3 text-left">Cache</th>
                  <th className="px-4 py-3 text-right">Tokens</th>
                  <th className="px-4 py-3 text-right">Cost</th>
                  <th className="px-4 py-3 text-right">Latency</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {requests.map((req) => (
                  <tr key={req.id} className="hover:bg-gray-50">
                    <td className="px-4 py-2 text-gray-500">
                      {new Date(req.createdAt).toLocaleTimeString()}
                    </td>
                    <td className="px-4 py-2">#{req.keyId}</td>
                    <td className="px-4 py-2">{req.servingModel || req.requestModel}</td>
                    <td className="px-4 py-2">{req.provider || '—'}</td>
                    <td className="px-4 py-2">
                      <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                        req.status === 'success'
                          ? 'bg-green-100 text-green-700'
                          : 'bg-red-100 text-red-700'
                      }`}>
                        {req.errorCode || req.status}
                      </span>
                    </td>
                    <td className="px-4 py-2">
                      {req.cacheResult === 'hit' && (
                        <span className="text-blue-600 font-medium">HIT</span>
                      )}
                      {req.cacheResult === 'miss' && (
                        <span className="text-gray-400">miss</span>
                      )}
                    </td>
                    <td className="px-4 py-2 text-right">
                      {req.promptTokens + req.completionTokens}
                    </td>
                    <td className="px-4 py-2 text-right">
                      ${(req.costMicroUsd / 1_000_000).toFixed(4)}
                    </td>
                    <td className="px-4 py-2 text-right">{req.latencyMs}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex justify-between mt-4">
            <button
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0}
              className="px-4 py-2 bg-gray-200 rounded disabled:opacity-50"
            >
              Previous
            </button>
            <span className="py-2 text-gray-500">Page {page + 1}</span>
            <button
              onClick={() => setPage(page + 1)}
              className="px-4 py-2 bg-gray-200 rounded"
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
```

### `console/src/views/CacheView.tsx`

```tsx
import React, { useEffect, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

interface CacheStats {
  keyId: number;
  cacheHits: number;
  cacheMisses: number;
  cacheHitRate: string;
}

export default function CacheView() {
  const [keyIds] = useState([1, 2, 3]);
  const [stats, setStats] = useState<CacheStats[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all(
      keyIds.map((id) =>
        fetch(`${API_BASE}/v1/usage/summary?keyId=${id}`)
          .then((r) => r.json())
          .then((data) => ({
            keyId: id,
            cacheHits: data.cacheHits || 0,
            cacheMisses: data.cacheMisses || 0,
            cacheHitRate: data.cacheHitRate || '0%',
          }))
          .catch(() => ({
            keyId: id,
            cacheHits: 0,
            cacheMisses: 0,
            cacheHitRate: '0%',
          }))
      )
    ).then((results) => {
      setStats(results);
      setLoading(false);
    });
  }, []);

  if (loading) return <p className="text-gray-500">Loading...</p>;

  return (
    <div>
      <h2 className="text-xl font-semibold mb-4">Cache Hit Rate</h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {stats.map((s) => {
          const total = s.cacheHits + s.cacheMisses;
          const hitPercent = total > 0 ? (s.cacheHits / total) * 100 : 0;

          return (
            <div key={s.keyId} className="bg-white rounded-lg shadow p-6">
              <h3 className="text-lg font-medium mb-4">Key #{s.keyId}</h3>

              {/* Simple bar */}
              <div className="w-full bg-gray-200 rounded-full h-4 mb-3">
                <div
                  className="bg-blue-500 h-4 rounded-full transition-all"
                  style={{ width: `${hitPercent}%` }}
                />
              </div>

              <p className="text-2xl font-bold text-center mb-2">
                {s.cacheHitRate}
              </p>

              <div className="flex justify-between text-sm text-gray-500">
                <span>{s.cacheHits} hits</span>
                <span>{s.cacheMisses} misses</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
```

### `console/tailwind.config.js`

```js
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {},
  },
  plugins: [],
};
```

### `console/postcss.config.js`

```js
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
};
```

### `console/tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassInitialization": true,
    "module": "ESNext",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true
  },
  "include": ["src"]
}
```

### `console/vite.config.ts`

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/v1': 'http://localhost:8080',
    },
  },
});
```

---

## CORS Config for Console

### `src/main/java/com/prism/config/WebConfig.java`

```java
package com.prism.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class WebConfig {

    @Bean
    public CorsWebFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://localhost:3000");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
```

---

## Classifier and Cache Tests

### `src/test/java/com/prism/routing/DifficultyClassifierTest.java`

```java
package com.prism.routing;

import com.prism.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DifficultyClassifierTest {

    private final DifficultyClassifier classifier = new DifficultyClassifier();

    private List<ChatCompletionRequest.Message> msg(String content) {
        return List.of(ChatCompletionRequest.Message.builder()
                .role("user").content(content).build());
    }

    @Test
    void simpleQuestionIsEasy() {
        assertEquals("easy", classifier.classify(msg("What is the capital of France?")));
    }

    @Test
    void complexQuestionIsHard() {
        assertEquals("hard", classifier.classify(
                msg("Design a distributed cache with trade-offs between consistency models")));
    }

    @Test
    void shortButHardIsHard() {
        assertEquals("hard", classifier.classify(msg("Prove P ≠ NP.")));
    }

    @Test
    void longButTrivialIsEasy() {
        String longEasy = "Here is a long list of items: apple, banana, cherry, date, " +
                "elderberry, fig, grape, honeydew, kiwi, lemon, mango, nectarine, " +
                "orange, papaya, quince, raspberry, strawberry, tangerine, ugli fruit, " +
                "vanilla bean, watermelon, xigua, yam, zucchini. " +
                "What is the first item listed?";
        assertEquals("easy", classifier.classify(msg(longEasy)));
    }

    @ParameterizedTest
    @CsvSource({
        "'Hello', easy",
        "'What is 2+2?', easy",
        "'Analyze this algorithm for complexity', hard",
        "'Debug this race condition in concurrent code', hard"
    })
    void classifiesCorrectly(String prompt, String expected) {
        assertEquals(expected, classifier.classify(msg(prompt)));
    }

    @Test
    void beatsLengthBaseline() {
        // Short-but-hard: length says easy, classifier should say hard
        String shortHard = "Derive the backpropagation algorithm.";
        assertEquals("easy", classifier.classifyByLength(msg(shortHard)));
        assertEquals("hard", classifier.classify(msg(shortHard)));

        // Long-but-easy: length says hard, classifier should say easy
        String longEasy = "I have a really long question here about something very simple. " +
                "It goes on and on with lots of detail but ultimately just asks: " +
                "What color is the sky? Please tell me. " +
                "I really want to know. It's for a school project. " +
                "My teacher wants me to find out. What is it?";
        assertEquals("hard", classifier.classifyByLength(msg(longEasy)));
        assertEquals("easy", classifier.classify(msg(longEasy)));
    }
}
```

### `src/test/java/com/prism/cache/EmbeddingServiceTest.java`

```java
package com.prism.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingServiceTest {

    private final EmbeddingService service = new EmbeddingService();

    @Test
    void identicalTextHasSimilarityOne() {
        float[] a = service.computeEmbedding("What is the capital of France?");
        float[] b = service.computeEmbedding("What is the capital of France?");
        double sim = service.cosineSimilarity(a, b);
        assertEquals(1.0, sim, 0.001);
    }

    @Test
    void similarTextHasHighSimilarity() {
        float[] a = service.computeEmbedding("What is the capital of France?");
        float[] b = service.computeEmbedding("Tell me the capital city of France");
        double sim = service.cosineSimilarity(a, b);
        assertTrue(sim > 0.7, "Expected high similarity, got " + sim);
    }

    @Test
    void differentTextHasLowSimilarity() {
        float[] a = service.computeEmbedding("What is the capital of France?");
        float[] b = service.computeEmbedding("How do neural networks learn?");
        double sim = service.cosineSimilarity(a, b);
        assertTrue(sim < 0.7, "Expected low similarity, got " + sim);
    }

    @Test
    void serializationRoundtrips() {
        float[] original = service.computeEmbedding("test text");
        byte[] bytes = service.toBytes(original);
        float[] restored = service.fromBytes(bytes);
        assertArrayEquals(original, restored);
    }

    @Test
    void promptHashIsDeterministic() {
        String text = "model:fast\nuser:hello\n";
        String h1 = service.computePromptHash(text);
        String h2 = service.computePromptHash(text);
        assertEquals(h1, h2);
    }
}
```

---

This completes the code for all six phases. To build and run:

```bash
# 1. Start MySQL and Redis
docker compose up -d mysql redis

# 2. Build and run the gateway
./mvnw spring-boot:run

# 3. Start the ops console
cd console && npm install && npm run dev

# 4. Run verification
python scripts/smoke_test.py
python scripts/load_test.py
python scripts/routing_eval.py
```
