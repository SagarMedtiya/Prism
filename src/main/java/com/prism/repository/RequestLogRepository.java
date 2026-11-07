package com.prism.repository;

import com.prism.model.RequestLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface RequestLogRepository extends ReactiveCrudRepository<RequestLog, Long> {

    Flux<RequestLog> findByKeyIdOrderByCreatedAtDesc(Long keyId, Pageable pageable);

    Flux<RequestLog> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Flux<RequestLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
