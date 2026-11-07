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

    @Scheduled(fixedRate = 600_000)
    public void cleanExpiredCacheEntries() {
        cacheRepository.deleteExpired()
                .doOnSuccess(v -> log.debug("Expired cache entries cleaned"))
                .subscribe();
    }
}
