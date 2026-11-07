package com.prism.repository;

import com.prism.model.CacheEntry;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CacheEntryRepository extends ReactiveCrudRepository<CacheEntry, Long> {

    Flux<CacheEntry> findByKeyIdOrderByCreatedAtDesc(Long keyId);

    Flux<CacheEntry> findByKeyIdAndModel(Long keyId, String model);

    @Query("SELECT COUNT(*) FROM cache_entries WHERE key_id = :keyId")
    Mono<Long> countByKeyId(Long keyId);

    @Query("DELETE FROM cache_entries WHERE key_id = :keyId " +
            "ORDER BY created_at ASC LIMIT :count")
    Mono<Void> evictOldest(Long keyId, int count);

    @Query("DELETE FROM cache_entries WHERE expires_at < NOW()")
    Mono<Void> deleteExpired();
}
