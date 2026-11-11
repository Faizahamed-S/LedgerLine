package com.ledgerline.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the API over real HTTP with real JWTs. The ledger is append-only and is never cleaned, so every helper
 * creates fresh users and accounts; tests must never assume the database is empty.
 */
public class Api {

    public static final String ADMIN_EMAIL = "admin@test.local";
    public static final String ADMIN_PASSWORD = "admin-test-password-123";
    public static final String USER_PASSWORD = "test-user-password-123";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TestRestTemplate rest;
    private TestUser admin;

    public Api(TestRestTemplate rest) {
        this.rest = rest;
    }

    public record TestUser(long id, String email, String token) {
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.local";
    }

    // ---- users ----

    public synchronized TestUser loginAdmin() {
        if (admin == null) {
            admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        }
        return admin;
    }

    public TestUser login(String email, String password) {
        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", email, "password", password), null);
        assertThat(response.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        return new TestUser(body.get("user").get("id").asLong(), email, body.get("token").asText());
    }

    public TestUser newHolder() {
        return newUser("HOLDER");
    }

    public TestUser newAuditor() {
        return newUser("AUDITOR");
    }

    private TestUser newUser(String role) {
        String email = uniqueEmail(role.toLowerCase(Locale.ROOT));
        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/admin/users", loginAdmin().token(),
                Map.of("email", email, "fullName", "Test " + role, "password", USER_PASSWORD, "role", role), null);
        assertThat(response.getStatusCode()).as("create %s", role).isEqualTo(HttpStatus.CREATED);
        return login(email, USER_PASSWORD);
    }

    // ---- accounts ----

    /** Requests an account as the holder; it stays PENDING. */
    public long requestAccount(TestUser holder) {
        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/accounts", holder.token(),
                Map.of("name", "Test account"), null);
        assertThat(response.getStatusCode()).as("request account").isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    /** Holder requests an account and the admin approves it. */
    public long openActiveAccount(TestUser holder) {
        long accountId = requestAccount(holder);
        ResponseEntity<JsonNode> response = setAccountStatus(accountId, "ACTIVE");
        assertThat(response.getStatusCode()).as("approve account").isEqualTo(HttpStatus.OK);
        return accountId;
    }

    public ResponseEntity<JsonNode> setAccountStatus(long accountId, String status) {
        return send(HttpMethod.PATCH, "/api/admin/accounts/" + accountId + "/status", loginAdmin().token(),
                Map.of("status", status), null);
    }

    // ---- money ----

    public ResponseEntity<JsonNode> deposit(String token, long accountId, long amountPaise) {
        return deposit(token, accountId, amountPaise, newKey());
    }

    public ResponseEntity<JsonNode> deposit(String token, long accountId, long amountPaise, String key) {
        return send(HttpMethod.POST, "/api/accounts/" + accountId + "/deposits", token,
                Map.of("amountPaise", amountPaise), key);
    }

    public ResponseEntity<JsonNode> withdraw(String token, long accountId, long amountPaise) {
        return withdraw(token, accountId, amountPaise, newKey());
    }

    public ResponseEntity<JsonNode> withdraw(String token, long accountId, long amountPaise, String key) {
        return send(HttpMethod.POST, "/api/accounts/" + accountId + "/withdrawals", token,
                Map.of("amountPaise", amountPaise), key);
    }

    public ResponseEntity<JsonNode> transfer(String token, long fromAccountId, long toAccountId, long amountPaise) {
        return transfer(token, fromAccountId, toAccountId, amountPaise, newKey());
    }

    public ResponseEntity<JsonNode> transfer(String token, long fromAccountId, long toAccountId, long amountPaise,
                                             String key) {
        return send(HttpMethod.POST, "/api/transfers", token,
                Map.of("fromAccountId", fromAccountId, "toAccountId", toAccountId, "amountPaise", amountPaise), key);
    }

    public ResponseEntity<JsonNode> statement(String token, long accountId) {
        return send(HttpMethod.GET, "/api/accounts/" + accountId + "/statement", token, null, null);
    }

    // ---- raw ----

    /** {@code token} and {@code idempotencyKey} are left out of the request when null. */
    public ResponseEntity<JsonNode> send(HttpMethod method, String path, String token, Object body,
                                         String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
