package com.ledgerline.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** Read-only views over the ledger for ADMIN and AUDITOR. Plain SQL, no locks. */
@Service
public class AuditQueryService {

    public static final int DEFAULT_LOG_LIMIT = 100;
    public static final int MAX_LOG_LIMIT = 500;

    private static final String AUDIT_LOG_SQL = """
            SELECT a.id, a.created_at, a.actor_id, u.email AS actor_email, a.action,
                   a.target_type, a.target_id, a.details::text AS details
            FROM audit_log a
            LEFT JOIN users u ON u.id = a.actor_id
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT ?
            """;

    private static final String MISMATCHED_ACCOUNTS_SQL = """
            SELECT a.id, a.balance, COALESCE(SUM(e.amount), 0) AS ledger_sum
            FROM accounts a
            LEFT JOIN ledger_entries e ON e.account_id = a.id
            GROUP BY a.id, a.balance
            HAVING a.balance <> COALESCE(SUM(e.amount), 0)
            ORDER BY a.id
            """;

    private final JdbcTemplate jdbc;

    public AuditQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first. {@code limit} is clamped to 1..500. */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> recentLog(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LOG_LIMIT));
        return jdbc.query(AUDIT_LOG_SQL, (rs, rowNum) -> new AuditLogResponse(
                rs.getLong("id"),
                instant(rs, "created_at"),
                rs.getObject("actor_id", Long.class),
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getObject("target_id", Long.class),
                rs.getString("details")), effectiveLimit);
    }

    /**
     * REPEATABLE READ gives every query here the same snapshot, so money moving while the check runs cannot make
     * the books look unbalanced. In Postgres this takes no locks.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationResponse reconcile() {
        long ledgerSum = queryLong("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries");
        long customerTotal = queryLong("SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE type = 'CUSTOMER'");
        long externalBalance = queryLong("SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE type = 'EXTERNAL'");
        List<MismatchedAccount> mismatched = jdbc.query(MISMATCHED_ACCOUNTS_SQL, (rs, rowNum) ->
                new MismatchedAccount(rs.getLong("id"), rs.getLong("balance"), rs.getLong("ledger_sum")));
        long posted = queryLong("SELECT COUNT(*) FROM transactions WHERE status = 'POSTED'");
        long rejected = queryLong("SELECT COUNT(*) FROM transactions WHERE status = 'REJECTED'");
        long duplicates = queryLong("SELECT COUNT(*) FROM idempotency_keys WHERE replay_count > 0");

        boolean balanced = ledgerSum == 0 && customerTotal == -externalBalance && mismatched.isEmpty();
        return new ReconciliationResponse(ledgerSum, customerTotal, externalBalance, balanced, mismatched,
                posted, rejected, duplicates, Instant.now());
    }

    private long queryLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
