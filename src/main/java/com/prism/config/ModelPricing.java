package com.prism.config;

import lombok.Data;

@Data
public class ModelPricing {
    private String provider;
    private double inputPricePerMillionTokens;
    private double outputPricePerMillionTokens;

    /** Returns cost in micro-USD for the given token counts. */
    public long computeCostMicroUsd(int promptTokens, int completionTokens) {
        double inputCost = (promptTokens / 1_000_000.0) * inputPricePerMillionTokens;
        double outputCost = (completionTokens / 1_000_000.0) * outputPricePerMillionTokens;
        return Math.round((inputCost + outputCost) * 1_000_000);
    }
}
