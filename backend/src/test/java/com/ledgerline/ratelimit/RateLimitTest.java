package com.ledgerline.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "ledgerline.ratelimit.enabled=true",
        "ledgerline.ratelimit.login-email.limit=3"
})
class RateLimitTest extends IntegrationTestBase {

    @Test
    void fourthLoginForTheSameEmailIsThrottledWithoutTouchingTheDatabase() {
        String email = Api.uniqueEmail("ratelimited");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(attemptLogin(email, "wrong-password-" + attempt).getStatusCode())
                    .as("attempt %d", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<JsonNode> fourth = attemptLogin(email, "wrong-password-4");
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String retryAfter = fourth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 900L);
        assertThat(fourth.getBody().get("message").asText())
                .isEqualTo("Too many requests, try again in " + retryAfter + " seconds");

        assertThat(attemptLogin(Api.ADMIN_EMAIL, Api.ADMIN_PASSWORD).getStatusCode())
                .as("a different email from the same client is not throttled")
                .isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN_FAILED' AND details ->> 'email' = ?",
                Long.class, email))
                .as("the throttled attempt never reached the login service")
                .isEqualTo(3);
    }

    private ResponseEntity<JsonNode> attemptLogin(String email, String password) {
        return api.send(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", password), null);
    }
}
