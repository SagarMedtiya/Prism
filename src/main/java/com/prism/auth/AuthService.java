package com.prism.auth;

import com.prism.model.VirtualKey;
import com.prism.repository.VirtualKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final VirtualKeyRepository keyRepository;

    /**
     * Resolves a bearer token to a VirtualKey.
     * Returns empty if the key is unknown or disabled.
     */
    public Mono<VirtualKey> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Mono.empty();
        }

        String hash = hashKey(bearerToken);
        return keyRepository.findByKeyHash(hash)
                .filter(VirtualKey::isActive);
    }

    public static String hashKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash key", e);
        }
    }
}