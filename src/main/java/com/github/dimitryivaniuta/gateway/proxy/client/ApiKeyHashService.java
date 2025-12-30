package com.github.dimitryivaniuta.gateway.proxy.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ApiKeyHashService {

    private final SecureRandom secureRandom = new SecureRandom();

    private final String pepper;
    private final String algorithm;

    public ApiKeyHashService(
            @Value("${security.api-key.pepper:}") String pepper,
            @Value("${security.api-key.hash-algorithm:SHA-256}") String algorithm
    ) {
        this.pepper = pepper == null ? "" : pepper;
        this.algorithm = algorithm;
    }

    /** Generates a strong random API key (base64url, no padding). Store ONLY hash in DB. */
    public String generateRawApiKey() {
        byte[] buf = new byte[32]; // 256-bit
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Hashes raw API key with optional pepper (server-side secret). Returns lowercase hex. */
    public String hash(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new IllegalArgumentException("rawApiKey must not be blank");
        }
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            // Simple peppering: hash(raw + ":" + pepper). Pepper is not stored in DB.
            byte[] input = (rawApiKey + ":" + pepper).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] digest = md.digest(input);
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash API key", e);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
