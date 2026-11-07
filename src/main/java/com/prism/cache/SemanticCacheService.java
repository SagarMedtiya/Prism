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

    public boolean isCacheable(ChatCompletionRequest request) {
        return !request.isStream() && request.getTemperature() == 0.0;
    }

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
                        log.info("Cache HIT for key={} similarity={}", keyId, bestSimilarity);
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

                    log.debug("Cache MISS for key={} bestSimilarity={}", keyId, bestSimilarity);
                    return Optional.<CacheHit>empty();
                });
    }

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
