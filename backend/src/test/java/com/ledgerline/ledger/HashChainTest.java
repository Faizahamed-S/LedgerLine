package com.ledgerline.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HashChainTest extends IntegrationTestBase {

    @Test
    void chainLinksEveryEntryAndDetectsTamperingEvenByTheTableOwner() {
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        TestUser auditor = api.newAuditor();
        long a = api.openActiveAccount(alice);
        long b = api.openActiveAccount(bob);
        assertThat(api.deposit(alice.token(), a, 50_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.transfer(alice.token(), a, b, 1_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.transfer(alice.token(), a, b, 2_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.transfer(bob.token(), b, a, 500).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertLinked(a, 4);
        assertLinked(b, 3);

        JsonNode intact = chain(auditor);
        assertThat(intact.get("intact").asBoolean()).isTrue();
        assertThat(intact.get("breaks").size()).isZero();
        assertThat(intact.get("unchainedLegacyEntries").asLong()).isZero();

        Map<String, Object> target = jdbc.queryForMap(
                "SELECT id, amount FROM ledger_entries WHERE account_id = ? ORDER BY id LIMIT 1 OFFSET 1", b);
        long entryId = ((Number) target.get("id")).longValue();
        long originalAmount = ((Number) target.get("amount")).longValue();

        try {
            setAmountBypassingTriggers(entryId, originalAmount + 1);

            JsonNode broken = chain(auditor);
            assertThat(broken.get("intact").asBoolean()).isFalse();
            assertThat(broken.get("breaks").size()).isEqualTo(1);
            JsonNode breakAt = broken.get("breaks").get(0);
            assertThat(breakAt.get("entryId").asLong()).isEqualTo(entryId);
            assertThat(breakAt.get("accountId").asLong()).isEqualTo(b);
            assertThat(breakAt.get("problem").asText()).isEqualTo("HASH_MISMATCH");
            assertThat(breakAt.get("storedHash").asText()).isNotEqualTo(breakAt.get("expectedHash").asText());
        } finally {
            // Restore so the shared test database stays consistent for the other test classes.
            setAmountBypassingTriggers(entryId, originalAmount);
        }

        assertThat(chain(auditor).get("intact").asBoolean()).isTrue();
    }

    /** Each entry's prev_hash is the previous entry's entry_hash; the first one starts from GENESIS. */
    private void assertLinked(long accountId, int expectedEntries) {
        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT prev_hash, entry_hash FROM ledger_entries WHERE account_id = ? ORDER BY id", accountId);
        assertThat(entries).hasSize(expectedEntries);
        String previous = LedgerHash.GENESIS;
        for (Map<String, Object> entry : entries) {
            assertThat(entry.get("prev_hash")).isEqualTo(previous);
            assertThat((String) entry.get("entry_hash")).hasSize(64);
            previous = (String) entry.get("entry_hash");
        }
    }

    /**
     * Tampering as the table OWNER, who can switch the append-only triggers off. DISABLE TRIGGER USER rather than
     * ALL: ALL also covers the internal foreign-key triggers, which only a superuser may disable.
     */
    private void setAmountBypassingTriggers(long entryId, long amount) {
        ownerJdbc().execute("ALTER TABLE ledger_entries DISABLE TRIGGER USER");
        try {
            ownerJdbc().update("UPDATE ledger_entries SET amount = ? WHERE id = ?", amount, entryId);
        } finally {
            ownerJdbc().execute("ALTER TABLE ledger_entries ENABLE TRIGGER USER");
        }
    }

    private JsonNode chain(TestUser auditor) {
        ResponseEntity<JsonNode> response = api.send(HttpMethod.GET, "/api/audit/chain", auditor.token(), null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
