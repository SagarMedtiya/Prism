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
