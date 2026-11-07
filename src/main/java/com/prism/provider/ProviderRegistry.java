package com.prism.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ProviderRegistry {

    private final Map<String, ProviderAdapter> providerMap = new HashMap<>();
    private final Map<String, ProviderAdapter> modelToProvider = new HashMap<>();

    public ProviderRegistry(List<ProviderAdapter> adapters) {
        for (ProviderAdapter adapter : adapters) {
            providerMap.put(adapter.getProviderId(), adapter);
            for (String model : adapter.getSupportedModels()) {
                modelToProvider.put(model, adapter);
            }
            log.info("Registered provider '{}' with models: {}",
                    adapter.getProviderId(), adapter.getSupportedModels());
        }
    }

    public Optional<ProviderAdapter> getByProviderId(String providerId) {
        return Optional.ofNullable(providerMap.get(providerId));
    }

    public Optional<ProviderAdapter> getByModel(String modelId) {
        return Optional.ofNullable(modelToProvider.get(modelId));
    }

    public Collection<ProviderAdapter> all() {
        return providerMap.values();
    }
}