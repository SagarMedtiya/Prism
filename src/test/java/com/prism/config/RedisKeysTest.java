package com.prism.config;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class RedisKeysTest {

    @Test
    void rpmKeyFormat() {
        assertEquals("prism:rpm:42", RedisKeys.rpm(42));
    }

    @Test
    void budgetKeyIncludesMonth() {
        String key = RedisKeys.budget(1);
        assertTrue(key.startsWith("prism:budget:1:"));
        assertTrue(key.contains(YearMonth.now().toString()));
    }

    @Test
    void budgetKeyWithExplicitMonth() {
        YearMonth month = YearMonth.of(2026, 3);
        assertEquals("prism:budget:5:2026-03", RedisKeys.budget(5, month));
    }

    @Test
    void differentKeysForDifferentIds() {
        assertNotEquals(RedisKeys.rpm(1), RedisKeys.rpm(2));
        assertNotEquals(RedisKeys.budget(1), RedisKeys.budget(2));
    }
}
