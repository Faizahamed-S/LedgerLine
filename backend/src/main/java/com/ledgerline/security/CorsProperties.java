package com.ledgerline.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerline.cors")
public record CorsProperties(String allowedOrigin) {
}
