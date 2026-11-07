package com.prism.repository;

import com.prism.model.UsageRecord;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface UsageRecordRepository extends ReactiveCrudRepository<UsageRecord, Long> {

    Flux<UsageRecord> findByKeyIdOrderByCreatedAtDesc(Long keyId);

    @Query("SELECT COALESCE(SUM(cost_micro_usd), 0) FROM usage_records " +
            "WHERE key_id = :keyId AND created_at >= :start AND created_at < :end")
    Mono<Long> sumCostByKeyIdAndPeriod(Long keyId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT COUNT(*) FROM usage_records " +
            "WHERE key_id = :keyId AND cache_result = :cacheResult " +
            "AND created_at >= :start AND created_at < :end")
    Mono<Long> countByCacheResult(Long keyId, String cacheResult, LocalDateTime start, LocalDateTime end);

    Flux<UsageRecord> findByKeyIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long keyId, LocalDateTime start, LocalDateTime end);
}