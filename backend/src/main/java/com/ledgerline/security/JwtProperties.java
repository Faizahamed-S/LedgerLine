package com.ledgerline.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

@ConfigurationProperties("ledgerline.jwt")
public record JwtProperties(String secret, long ttlMinutes) {

    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "ledgerline.jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("ledgerline.jwt.ttl-minutes (JWT_TTL_MINUTES) must be positive");
        }
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=***, ttlMinutes=" + ttlMinutes + "]";
    }
}
