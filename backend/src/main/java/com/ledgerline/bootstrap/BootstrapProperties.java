package com.ledgerline.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerline.bootstrap")
public record BootstrapProperties(boolean enabled, String email, String password) {

    @Override
    public String toString() {
        return "BootstrapProperties[enabled=" + enabled + ", email=" + email + ", password=***]";
    }
}
