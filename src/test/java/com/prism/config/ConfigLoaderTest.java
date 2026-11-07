package com.prism.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void loadsAllConfigFiles() throws Exception {
        ConfigLoader loader = new ConfigLoader(new ObjectMapper());
        loader.load();

        assertNotNull(loader.getGatewayConfig());
        assertNotNull(loader.getModelPricing());
        assertNotNull(loader.getSeedKeys());

        assertTrue(loader.getGatewayConfig().getAliases().containsKey("fast"));
        assertTrue(loader.getGatewayConfig().getAliases().containsKey("smart"));
        assertTrue(loader.getGatewayConfig().getAliases().containsKey("auto"));

        assertFalse(loader.getModelPricing().isEmpty());

        assertEquals(3, loader.getSeedKeys().size());
        assertEquals("sk-prism-demo-alpha-001", loader.getSeedKeys().get(0).getKey());
    }

    @Test
    void pricingComputesCostCorrectly() throws Exception {
        ConfigLoader loader = new ConfigLoader(new ObjectMapper());
        loader.load();

        ModelPricing pricing = loader.getPricing("mock-fast-v1");
        assertNotNull(pricing);

        long cost = pricing.computeCostMicroUsd(1000, 500);
        assertEquals(450, cost);
    }
}
