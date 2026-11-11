package com.ledgerline.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEndpointsTest extends IntegrationTestBase {

    @Test
    void holderIsForbiddenFromBothEndpoints() {
        TestUser holder = api.newHolder();
        long auditRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class);

        assertThat(get(holder, "/api/audit/log").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(holder, "/api/audit/reconciliation").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class)).isEqualTo(auditRowsBefore);
    }

    @Test
    void auditorReadsTheLogNewestFirstWithActorEmails() {
        TestUser auditor = api.newAuditor();

        ResponseEntity<JsonNode> response = get(auditor, "/api/audit/log?limit=5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = response.getBody();
        assertThat(rows.size()).isBetween(1, 5);

        List<Instant> createdAt = new ArrayList<>();
        rows.forEach(row -> createdAt.add(Instant.parse(row.get("createdAt").asText())));
        assertThat(createdAt).isSortedAccordingTo((a, b) -> b.compareTo(a));

        long newestId = jdbc.queryForObject(
                "SELECT id FROM audit_log ORDER BY created_at DESC, id DESC LIMIT 1", Long.class);
        assertThat(rows.get(0).get("id").asLong()).isEqualTo(newestId);

        JsonNode auditorLogin = null;
        for (JsonNode row : rows) {
            if ("LOGIN_SUCCESS".equals(row.get("action").asText()) && row.get("actorId").asLong() == auditor.id()) {
                auditorLogin = row;
            }
        }
        assertThat(auditorLogin).as("the auditor's own login is in the newest rows").isNotNull();
        assertThat(auditorLogin.get("actorEmail").asText()).isEqualTo(auditor.email());
        assertThat(auditorLogin.get("details").asText()).contains(auditor.email());
    }

    @Test
    void reconciliationIsBalancedAfterRealMoneyMovement() {
        TestUser auditor = api.newAuditor();
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        long a = api.openActiveAccount(alice);
        long b = api.openActiveAccount(bob);
        assertThat(api.deposit(alice.token(), a, 50_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.deposit(bob.token(), b, 20_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.transfer(alice.token(), a, b, 15_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.withdraw(bob.token(), b, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.withdraw(alice.token(), a, 999_999).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<JsonNode> response = get(auditor, "/api/audit/reconciliation");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("balanced").asBoolean()).isTrue();
        assertThat(body.get("ledgerSumPaise").asLong()).isZero();
        assertThat(body.get("mismatchedAccounts").size()).isZero();
        assertThat(body.get("customerTotalPaise").asLong()).isEqualTo(-body.get("externalBalancePaise").asLong());

        assertThat(body.get("customerTotalPaise").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE type = 'CUSTOMER'", Long.class));
        assertThat(body.get("externalBalancePaise").asLong()).isEqualTo(balanceOf(externalAccountId()));
        assertThat(body.get("postedCount").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE status = 'POSTED'", Long.class));
        assertThat(body.get("rejectedCount").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE status = 'REJECTED'", Long.class));
        assertThat(body.get("duplicateRequestCount").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE replay_count > 0", Long.class));
        assertThat(balanceOf(a)).isEqualTo(35_000);
        assertThat(balanceOf(b)).isEqualTo(30_000);
    }

    private ResponseEntity<JsonNode> get(TestUser user, String path) {
        return api.send(HttpMethod.GET, path, user.token(), null, null);
    }
}
