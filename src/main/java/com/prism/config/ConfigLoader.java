package com.prism.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Getter
public class ConfigLoader {

    private GatewayConfig gatewayConfig;
    private Map<String, ModelPricing> modelPricing;
    private List<SeedKey> seedKeys;

    private final ObjectMapper objectMapper;

    public ConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() throws IOException {
        this.gatewayConfig = loadResource("gateway_config.sample.json", GatewayConfig.class);
        log.info("Loaded gateway config: {} aliases, {} providers",
                gatewayConfig.getAliases().size(), gatewayConfig.getProviders().size());

        this.modelPricing = loadResource("model_pricing.json",
                new TypeReference<Map<String, ModelPricing>>() {
                });
        log.info("Loaded model pricing for {} models", modelPricing.size());

        this.seedKeys = loadResource("seed_keys.json",
                new TypeReference<List<SeedKey>>() {
                });
        log.info("Loaded {} seed keys", seedKeys.size());
    }

    private <T> T loadResource(String filename, Class<T> type) throws IOException {
        try (InputStream is = new ClassPathResource(filename).getInputStream()) {
            return objectMapper.readValue(is, type);
        }
    }

    private <T> T loadResource(String filename, TypeReference<T> type) throws IOException {
        try (InputStream is = new ClassPathResource(filename).getInputStream()) {
            return objectMapper.readValue(is, type);
        }
    }

    public ModelPricing getPricing(String modelId) {
        return modelPricing.get(modelId);
    }
}