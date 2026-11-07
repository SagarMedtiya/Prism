package com.prism.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    @Test
    void hashIsDeterministic() {
        String key = "sk-prism-demo-alpha-001";
        String hash1 = AuthService.hashKey(key);
        String hash2 = AuthService.hashKey(key);
        assertEquals(hash1, hash2);
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        String hash1 = AuthService.hashKey("sk-prism-demo-alpha-001");
        String hash2 = AuthService.hashKey("sk-prism-demo-beta-002");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void hashIsHexString() {
        String hash = AuthService.hashKey("test-key");
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
