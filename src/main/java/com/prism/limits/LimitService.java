package com.prism.limits;

import com.prism.config.RedisKeys;
import com.prism.model.VirtualKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitService {

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * Check if the model is in the key's allowlist.
     */
    public boolean isModelAllowed(VirtualKey key, String model) {
        List<String> allowed = key.getAllowedModels();
        return allowed.contains(model);
    }

    /**
     * Atomically check and increment RPM. Returns true if admitted.
     */
    public Mono<Boolean> checkAndIncrementRpm(VirtualKey key) {
        String script = """
                local key = KEYS[1]
                local limit = tonumber(ARGV[1])
                local window = tonumber(ARGV[2])
                local now = tonumber(ARGV[3])
                local window_start = now - window
                redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)
                local count = redis.call('ZCARD', key)
                if count < limit then
                    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
                    redis.call('EXPIRE', key, window + 1)
                    return 1
                end
                return 0
                """;

        String rpmKey = RedisKeys.rpm(key.getId());
        long now = System.currentTimeMillis();
        long window = 60_000; // 1 minute in milliseconds

        return redisTemplate.execute(
                RedisScript.of(script, Long.class),
                List.of(rpmKey),
                List.of(String.valueOf(key.getRpmLimit()),
                        String.valueOf(window),
                        String.valueOf(now))
        ).next().map(result -> result == 1L);
    }

    /**
     * Atomically reserve budget. Returns true if reservation succeeded.
     */
    public Mono<Boolean> reserveBudget(VirtualKey key, long estimatedCostMicroUsd) {
        String script = """
                local key = KEYS[1]
                local budget = tonumber(ARGV[1])
                local cost = tonumber(ARGV[2])
                local ttl = tonumber(ARGV[3])
                local current = tonumber(redis.call('GET', key) or '0')
                if current + cost <= budget then
                    redis.call('INCRBY', key, cost)
                    redis.call('EXPIRE', key, ttl)
                    return 1
                end
                return 0
                """;

        String budgetKey = RedisKeys.budget(key.getId());
        long ttl = 86400 * 32; // keep for the month + margin

        return redisTemplate.execute(
                RedisScript.of(script, Long.class),
                List.of(budgetKey),
                List.of(String.valueOf(key.getMonthlyBudgetMicroUsd()),
                        String.valueOf(estimatedCostMicroUsd),
                        String.valueOf(ttl))
        ).next().map(result -> result == 1L);
    }

    /**
     * Release unused reservation (difference between estimated and actual cost).
     */
    public Mono<Void> releaseBudget(VirtualKey key, long amountToReleaseMicroUsd) {
        if (amountToReleaseMicroUsd <= 0) {
            return Mono.empty();
        }

        String budgetKey = RedisKeys.budget(key.getId());
        return redisTemplate.opsForValue()
                .decrement(budgetKey, amountToReleaseMicroUsd)
                .then();
    }
}