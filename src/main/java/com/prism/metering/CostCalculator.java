package com.prism.metering;

import com.prism.config.ConfigLoader;
import com.prism.config.ModelPricing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CostCalculator {

    private final ConfigLoader configLoader;

    /**
     * Returns cost in micro-USD.
     */
    public long computeCost(String modelId, int promptTokens, int completionTokens) {
        ModelPricing pricing = configLoader.getPricing(modelId);
        if (pricing == null) {
            log.warn("No pricing found for model '{}', cost set to 0", modelId);
            return 0;
        }
        return pricing.computeCostMicroUsd(promptTokens, completionTokens);
    }

    /**
     * Estimate the maximum cost for budget reservation (before the call).
     * Uses a generous estimate of completion tokens.
     */
    public long estimateMaxCost(String modelId, int promptTokens, int maxCompletionTokens) {
        int estimated = maxCompletionTokens > 0 ? maxCompletionTokens : 4096;
        return computeCost(modelId, promptTokens, estimated);
    }
}