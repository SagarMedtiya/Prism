package com.prism.metering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.ConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CostCalculatorTest {

    private CostCalculator calculator;

    @BeforeEach
    void setUp() throws Exception {
        ConfigLoader loader = new ConfigLoader(new ObjectMapper());
        loader.load();
        calculator = new CostCalculator(loader);
    }

    @Test
    void computesCostForFastModel() {
        long cost = calculator.computeCost("mock-fast-v1", 100, 50);
        assertEquals(45, cost);
    }

    @Test
    void computesCostForSmartModel() {
        long cost = calculator.computeCost("mock-smart-v1", 1000, 500);
        assertEquals(12500, cost);
    }

    @Test
    void unknownModelReturnsZero() {
        long cost = calculator.computeCost("nonexistent-model", 100, 50);
        assertEquals(0, cost);
    }

    @Test
    void zeroCostForCacheHit() {
        long cost = calculator.computeCost("mock-fast-v1", 0, 0);
        assertEquals(0, cost);
    }
}
