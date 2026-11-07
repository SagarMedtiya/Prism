package com.prism.logging;

import com.prism.model.RequestLog;
import com.prism.model.UsageRecord;
import com.prism.repository.RequestLogRepository;
import com.prism.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestLogger {

    private final RequestLogRepository logRepository;
    private final UsageRecordRepository usageRepository;

    public Mono<Void> logRequest(RequestLog requestLog) {
        return logRepository.save(requestLog)
                .doOnNext(saved -> log.debug("Request logged: id={} status={} model={}",
                        saved.getId(), saved.getStatus(), saved.getServingModel()))
                .then();
    }

    public Mono<Void> recordUsage(UsageRecord usageRecord) {
        return usageRepository.save(usageRecord)
                .doOnNext(saved -> log.debug("Usage recorded: id={} cost={}µ¢",
                        saved.getId(), saved.getCostMicroUsd()))
                .then();
    }

    public Mono<Void> logAndRecord(RequestLog requestLog, UsageRecord usageRecord) {
        return Mono.when(logRequest(requestLog), recordUsage(usageRecord));
    }
}