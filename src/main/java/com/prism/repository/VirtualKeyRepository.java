package com.prism.repository;

import com.prism.model.VirtualKey;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface VirtualKeyRepository extends ReactiveCrudRepository<VirtualKey, Long> {
    Mono<VirtualKey> findByKeyHash(String keyHash);
}