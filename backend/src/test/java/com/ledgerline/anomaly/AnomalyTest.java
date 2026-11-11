package com.ledgerline.anomaly;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Flags are evaluated asynchronously after commit, so assertions on flags wait (bounded) for them to appear. */
class AnomalyTest extends IntegrationTestBase {

    private static final Duration FLAG_TIMEOUT = Duration.ofSeconds(15);

    @Test
    void largeDepositCreatesAHighLargeAmountFlagAndMovesNothingExtra() {
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);

        ResponseEntity<JsonNode> deposit = api.deposit(api.loginAdmin().token(), account, 5_000_000);
        assertThat(deposit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long transactionId = deposit.getBody().get("transactionId").asLong();

        Map<String, Object> flag = awaitFlag(transactionId, "LARGE_AMOUNT");
        assertThat(flag.get("severity")).isEqualTo("HIGH");
        assertThat(((Number) flag.get("account_id")).longValue()).isEqualTo(account);

        assertThat(balanceOf(account)).isEqualTo(5_000_000);
        assertThat(ledgerSum(account)).isEqualTo(5_000_000);
        assertThat(countTransactionsInvolving(account)).isEqualTo(1);
    }

    @Test
    void foreignWithdrawalAttemptCreatesAForeignProbeFlag() {
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        long aliceAccount = api.openActiveAccount(alice);
        assertThat(api.deposit(alice.token(), aliceAccount, 10_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> probe = api.withdraw(bob.token(), aliceAccount, 1_000);
        assertThat(probe.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> flag = awaitFlag(probe.getBody().get("transactionId").asLong(), "FOREIGN_PROBE");
        assertThat(flag.get("severity")).isEqualTo("HIGH");
        assertThat(((Number) flag.get("account_id")).longValue()).isEqualTo(aliceAccount);
        assertThat((String) flag.get("message")).contains(bob.email());
        assertThat(balanceOf(aliceAccount)).isEqualTo(10_000);
    }

    @Test
    void adminReviewsAFlagOnceAndASecondReviewIsAConflict() {
        long flagId = createForeignProbeFlag();
        TestUser admin = api.loginAdmin();
        TestUser auditor = api.newAuditor();
        long ledgerEntriesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        long frozenBefore = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE status = 'FROZEN'", Long.class);

        ResponseEntity<JsonNode> review = review(admin, flagId, "CONFIRMED", "Probe from an unknown holder");
        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review.getBody().get("review").get("decision").asText()).isEqualTo("CONFIRMED");
        assertThat(review.getBody().get("review").get("reviewerEmail").asText()).isEqualTo(admin.email());

        assertThat(review(admin, flagId, "DISMISSED", "Changed my mind").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flag_reviews WHERE flag_id = ?", Long.class, flagId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT decision FROM flag_reviews WHERE flag_id = ?", String.class, flagId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'FLAG_REVIEWED' AND target_type = 'FLAG' AND target_id = ?",
                Long.class, flagId)).isEqualTo(1);
        // CONFIRMED is only a record: nothing was frozen and no money moved.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE status = 'FROZEN'", Long.class))
                .isEqualTo(frozenBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class))
                .isEqualTo(ledgerEntriesBefore);

        JsonNode reviewed = get(auditor, "/api/audit/flags?status=REVIEWED").getBody();
        assertThat(containsFlag(reviewed, flagId)).isTrue();
        assertThat(containsFlag(get(auditor, "/api/audit/flags?status=OPEN").getBody(), flagId)).isFalse();
    }

    @Test
    void holderCannotListFlagsAndAuditorCannotReview() {
        long flagId = createForeignProbeFlag();
        TestUser holder = api.newHolder();
        TestUser auditor = api.newAuditor();

        assertThat(get(holder, "/api/audit/flags").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(review(auditor, flagId, "DISMISSED", "Not allowed").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flag_reviews WHERE flag_id = ?", Long.class, flagId))
                .isZero();
    }

    private long createForeignProbeFlag() {
        TestUser owner = api.newHolder();
        TestUser intruder = api.newHolder();
        long account = api.openActiveAccount(owner);
        ResponseEntity<JsonNode> probe = api.withdraw(intruder.token(), account, 100);
        assertThat(probe.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> flag = awaitFlag(probe.getBody().get("transactionId").asLong(), "FOREIGN_PROBE");
        return ((Number) flag.get("id")).longValue();
    }

    private Map<String, Object> awaitFlag(long transactionId, String rule) {
        String sql = "SELECT COUNT(*) FROM anomaly_flags WHERE transaction_id = ? AND rule = ?";
        await().atMost(FLAG_TIMEOUT)
                .until(() -> jdbc.queryForObject(sql, Long.class, transactionId, rule) == 1);
        return jdbc.queryForMap("SELECT * FROM anomaly_flags WHERE transaction_id = ? AND rule = ?", transactionId, rule);
    }

    private ResponseEntity<JsonNode> review(TestUser user, long flagId, String decision, String note) {
        return api.send(HttpMethod.POST, "/api/admin/flags/" + flagId + "/review", user.token(),
                Map.of("decision", decision, "note", note), null);
    }

    private ResponseEntity<JsonNode> get(TestUser user, String path) {
        return api.send(HttpMethod.GET, path, user.token(), null, null);
    }

    private static boolean containsFlag(JsonNode flags, long flagId) {
        for (JsonNode flag : flags) {
            if (flag.get("id").asLong() == flagId) {
                return true;
            }
        }
        return false;
    }
}
