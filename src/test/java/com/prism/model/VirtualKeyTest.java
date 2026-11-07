package com.prism.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VirtualKeyTest {

    @Test
    void parseAllowlistFromJson() {
        VirtualKey key = VirtualKey.builder()
                .modelAllowlist("[\"fast\",\"smart\",\"auto\"]")
                .status("active")
                .build();

        List<String> allowed = key.getAllowedModels();
        assertEquals(3, allowed.size());
        assertTrue(allowed.contains("fast"));
        assertTrue(allowed.contains("smart"));
        assertTrue(allowed.contains("auto"));
    }

    @Test
    void invalidJsonReturnsEmptyList() {
        VirtualKey key = VirtualKey.builder()
                .modelAllowlist("not json")
                .status("active")
                .build();

        assertTrue(key.getAllowedModels().isEmpty());
    }

    @Test
    void nullAllowlistReturnsEmptyList() {
        VirtualKey key = VirtualKey.builder()
                .modelAllowlist(null)
                .status("active")
                .build();

        assertTrue(key.getAllowedModels().isEmpty());
    }

    @Test
    void isActiveWhenStatusActive() {
        VirtualKey key = VirtualKey.builder().status("active").build();
        assertTrue(key.isActive());
    }

    @Test
    void isNotActiveWhenDisabled() {
        VirtualKey key = VirtualKey.builder().status("disabled").build();
        assertFalse(key.isActive());
    }

    @Test
    void isNotActiveWhenNull() {
        VirtualKey key = VirtualKey.builder().status(null).build();
        assertFalse(key.isActive());
    }
}
