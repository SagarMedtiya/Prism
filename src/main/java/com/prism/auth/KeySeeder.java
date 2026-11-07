package com.prism.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.SeedKey;
import com.prism.model.VirtualKey;
import com.prism.repository.VirtualKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KeySeeder implements ApplicationRunner {

    private final VirtualKeyRepository keyRepository;
    private final com.prism.config.ConfigLoader configLoader;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<SeedKey> seeds = configLoader.getSeedKeys();

        for (SeedKey seed : seeds) {
            String hash = AuthService.hashKey(seed.getKey());
            keyRepository.findByKeyHash(hash)
                    .switchIfEmpty(
                            keyRepository.save(VirtualKey.builder()
                                    .keyHash(hash)
                                    .name(seed.getName())
                                    .team(seed.getTeam())
                                    .modelAllowlist(toJson(seed.getModelAllowlist()))
                                    .rpmLimit(seed.getRpmLimit())
                                    .monthlyBudgetMicroUsd(
                                            Math.round(seed.getMonthlyBudgetUsd() * 1_000_000))
                                    .status("active")
                                    .build()))
                    .doOnNext(k -> log.info("Seed key '{}' ready (id={})", seed.getName(), k.getId()))
                    .block(); // runs once at startup
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}