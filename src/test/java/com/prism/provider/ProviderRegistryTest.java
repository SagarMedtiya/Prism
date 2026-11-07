package com.prism.provider;

import com.prism.provider.mock.MockProviderA;
import com.prism.provider.mock.MockProviderB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {

    private final ProviderRegistry registry = new ProviderRegistry(
            List.of(new MockProviderA(), new MockProviderB()));

    @Test
    void findsProviderById() {
        assertTrue(registry.getByProviderId("mock-provider-a").isPresent());
        assertTrue(registry.getByProviderId("mock-provider-b").isPresent());
        assertFalse(registry.getByProviderId("nonexistent").isPresent());
    }

    @Test
    void findsProviderByModel() {
        assertEquals("mock-provider-a",
                registry.getByModel("mock-fast-v1").get().getProviderId());
        assertEquals("mock-provider-b",
                registry.getByModel("mock-smart-v1").get().getProviderId());
        assertEquals("mock-provider-a",
                registry.getByModel("mock-smart-v2").get().getProviderId());
        assertEquals("mock-provider-b",
                registry.getByModel("mock-fast-v2").get().getProviderId());
    }

    @Test
    void unknownModelReturnsEmpty() {
        assertFalse(registry.getByModel("nonexistent").isPresent());
    }

    @Test
    void allReturnsAllProviders() {
        assertEquals(2, registry.all().size());
    }
}
