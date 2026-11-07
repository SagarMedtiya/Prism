package com.prism.api;

import com.prism.provider.ProviderRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DatabaseClient databaseClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ProviderRegistry providerRegistry;

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        Mono<String> mysqlStatus = databaseClient.sql("SELECT 1")
                .fetch().first()
                .map(r -> "up")
                .onErrorReturn("down");

        Mono<String> redisStatus = redisTemplate.execute(connection ->
                        connection.ping())
                .next()
                .map(r -> "up")
                .onErrorReturn("down");

        return Mono.zip(mysqlStatus, redisStatus)
                .map(tuple -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "up");
                    result.put("mysql", tuple.getT1());
                    result.put("redis", tuple.getT2());

                    Map<String, String> providers = new HashMap<>();
                    providerRegistry.all().forEach(p ->
                            providers.put(p.getProviderId(), "up"));
                    result.put("providers", providers);

                    return result;
                });
    }
}
