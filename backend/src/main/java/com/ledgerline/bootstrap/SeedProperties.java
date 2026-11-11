package com.ledgerline.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerline.seed")
public record SeedProperties(boolean enabled, String password) {

    @Override
    public String toString() {
        return "SeedProperties[enabled=" + enabled + ", password=***]";
    }
}
