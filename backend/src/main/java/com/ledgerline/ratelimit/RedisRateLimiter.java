package com.ledgerline.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-window counters in Redis. Fails open: if Redis is unreachable the request is allowed, because money
 * integrity is enforced by the database, not by the rate limiter.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** INCR and EXPIRE in one atomic step, so a counter can never be left without a TTL. */
    private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return c
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {

        static Decision allow() {
            return new Decision(true, 0);
        }
    }

    /** Counts one request against {@code rl:{rule}:{subject}}. */
    public Decision hit(String rule, String subject, RateLimitProperties.Rule limit) {
        String key = "rl:" + rule + ":" + subject;
        try {
            Long count = redis.execute(INCREMENT_WITH_TTL, List.of(key), String.valueOf(limit.windowSeconds()));
            if (count == null || count <= limit.limit()) {
                return Decision.allow();
            }
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = ttl == null || ttl <= 0 ? limit.windowSeconds() : ttl;
            return new Decision(false, retryAfter);
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request ({}): {}", key, e.getMessage());
            return Decision.allow();
        }
    }
}
