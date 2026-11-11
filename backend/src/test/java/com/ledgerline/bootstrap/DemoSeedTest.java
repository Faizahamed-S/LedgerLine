package com.ledgerline.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "ledgerline.seed.enabled=true",
        "ledgerline.seed.password=" + DemoSeedTest.SEED_PASSWORD
})
class DemoSeedTest extends IntegrationTestBase {

    static final String SEED_PASSWORD = "demo-seed-password-123";

    @Autowired
    private DemoSeedRunner demoSeedRunner;

    @Test
    void seedCreatesOneLoginPerRoleAndMovesMoneyOnlyThroughMoneyService() {
        assertThat(roleOf(DemoSeedRunner.HOLDER1_EMAIL)).isEqualTo("HOLDER");
        assertThat(roleOf(DemoSeedRunner.HOLDER2_EMAIL)).isEqualTo("HOLDER");
        assertThat(roleOf(DemoSeedRunner.AUDITOR_EMAIL)).isEqualTo("AUDITOR");

        long account1 = accountOf(DemoSeedRunner.HOLDER1_EMAIL);
        long account2 = accountOf(DemoSeedRunner.HOLDER2_EMAIL);
        assertThat(balanceOf(account1))
                .isEqualTo(DemoSeedRunner.HOLDER1_DEPOSIT_PAISE - DemoSeedRunner.TRANSFER_PAISE);
        assertThat(balanceOf(account2))
                .isEqualTo(DemoSeedRunner.HOLDER2_DEPOSIT_PAISE + DemoSeedRunner.TRANSFER_PAISE);
        assertThat(ledgerSum(account1)).isEqualTo(balanceOf(account1));
        assertThat(ledgerSum(account2)).isEqualTo(balanceOf(account2));

        for (String key : new String[] {
                "seed-deposit-holder1", "seed-deposit-holder2", "seed-transfer-holder1-holder2"}) {
            assertThat(jdbc.queryForObject("""
                    SELECT t.status FROM idempotency_keys k JOIN transactions t ON t.id = k.transaction_id
                    WHERE k.idempotency_key = ?
                    """, String.class, key)).as(key).isEqualTo("POSTED");
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'DEMO_SEED_CREATED'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_APPROVED' AND target_id IN (?, ?)",
                Long.class, account1, account2)).isEqualTo(2);

        TestUser auditor = api.login(DemoSeedRunner.AUDITOR_EMAIL, SEED_PASSWORD);
        ResponseEntity<JsonNode> reconciliation =
                api.send(HttpMethod.GET, "/api/audit/reconciliation", auditor.token(), null, null);
        assertThat(reconciliation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reconciliation.getBody().get("balanced").asBoolean()).isTrue();
    }

    @Test
    void runningTheSeedAgainChangesNothing() throws Exception {
        long transactionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class);
        long usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);

        demoSeedRunner.run(new DefaultApplicationArguments());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isEqualTo(transactionsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'DEMO_SEED_CREATED'", Long.class)).isEqualTo(1);
    }

    private String roleOf(String email) {
        return jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email);
    }

    private long accountOf(String email) {
        return jdbc.queryForObject("""
                SELECT a.id FROM accounts a JOIN users u ON u.id = a.owner_id
                WHERE u.email = ? AND a.status = 'ACTIVE'
                """, Long.class, email);
    }
}
