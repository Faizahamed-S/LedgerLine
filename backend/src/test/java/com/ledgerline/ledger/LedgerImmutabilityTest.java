package com.ledgerline.ledger;

import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the ledger is append-only at the database level: ledger_app lacks the privileges, and even the table
 * owner is stopped by the triggers.
 */
class LedgerImmutabilityTest extends IntegrationTestBase {

    private long entryId;
    private long entryAmount;
    private long transactionId;

    @BeforeEach
    void postARealTransfer() {
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        long a = api.openActiveAccount(alice);
        long b = api.openActiveAccount(bob);
        assertThat(api.deposit(alice.token(), a, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var transfer = api.transfer(alice.token(), a, b, 1_000);
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        transactionId = transfer.getBody().get("transactionId").asLong();
        entryId = jdbc.queryForObject(
                "SELECT id FROM ledger_entries WHERE transaction_id = ? AND account_id = ?",
                Long.class, transactionId, b);
        entryAmount = jdbc.queryForObject("SELECT amount FROM ledger_entries WHERE id = ?", Long.class, entryId);
    }

    @Test
    void appRoleCannotChangeOrDeleteLedgerRows() {
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET amount = amount + 1 WHERE id = ?", transactionId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");

        assertEntryUnchanged();
    }

    @Test
    void ownerRoleIsStoppedByTheAppendOnlyTriggers() {
        assertThatThrownBy(() -> ownerJdbc().update(
                "UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> ownerJdbc().update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> ownerJdbc().execute("TRUNCATE ledger_entries"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");

        assertEntryUnchanged();
    }

    private void assertEntryUnchanged() {
        assertThat(ownerJdbc().queryForObject("SELECT amount FROM ledger_entries WHERE id = ?", Long.class, entryId))
                .isEqualTo(entryAmount);
        assertThat(ownerJdbc().queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class)).isPositive();
    }
}
