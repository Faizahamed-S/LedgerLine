package com.ledgerline.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fixed-window limits. Each rule allows {@code limit} requests per {@code windowSeconds}. */
@ConfigurationProperties("ledgerline.ratelimit")
public record RateLimitProperties(
        boolean enabled,
        Rule loginIp,
        Rule loginEmail,
        Rule registerIp,
        Rule money,
        Rule api) {

    public record Rule(int limit, long windowSeconds) {

        public Rule {
            if (limit <= 0 || windowSeconds <= 0) {
                throw new IllegalArgumentException("Rate limit and window must be positive");
            }
        }
    }
}
