package com.prism.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.ConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AliasResolverTest {

    private AliasResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        ConfigLoader loader = new ConfigLoader(new ObjectMapper());
        loader.load();
        resolver = new AliasResolver(loader);
    }

    @Test
    void resolvesFastAlias() {
        RouteResult result = resolver.resolve("fast");
        assertEquals("mock-fast-v1", result.getPrimaryModel());
        assertEquals(List.of("mock-fast-v2"), result.getFallbacks());
        assertEquals("fast", result.getResolvedAlias());
    }

    @Test
    void resolvesSmartAlias() {
        RouteResult result = resolver.resolve("smart");
        assertEquals("mock-smart-v1", result.getPrimaryModel());
        assertEquals(List.of("mock-smart-v2"), result.getFallbacks());
        assertEquals("smart", result.getResolvedAlias());
    }

    @Test
    void directModelNameHasNoFallbacks() {
        RouteResult result = resolver.resolve("mock-fast-v1");
        assertEquals("mock-fast-v1", result.getPrimaryModel());
        assertTrue(result.getFallbacks().isEmpty());
        assertNull(result.getResolvedAlias());
    }

    @Test
    void unknownModelReturnsItself() {
        RouteResult result = resolver.resolve("nonexistent-model");
        assertEquals("nonexistent-model", result.getPrimaryModel());
        assertTrue(result.getFallbacks().isEmpty());
    }

    @Test
    void autoAliasIsDetected() {
        assertTrue(resolver.isAutoAlias("auto"));
    }

    @Test
    void fastIsNotAutoAlias() {
        assertFalse(resolver.isAutoAlias("fast"));
    }

    @Test
    void directModelIsNotAutoAlias() {
        assertFalse(resolver.isAutoAlias("mock-fast-v1"));
    }

    @Test
    void autoConfigHasRoutes() {
        var autoConfig = resolver.getAutoConfig();
        assertNotNull(autoConfig);
        assertEquals("difficulty", autoConfig.getClassifier());
        assertEquals("fast", autoConfig.getRoutes().get("easy"));
        assertEquals("smart", autoConfig.getRoutes().get("hard"));
    }
}
