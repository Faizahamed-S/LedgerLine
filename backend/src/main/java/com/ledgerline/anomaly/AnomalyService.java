package com.ledgerline.anomaly;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Rule-based anomaly flags (no ML/LLM). Suggest only: this class inserts flag rows and nothing else. It never
 * changes balances, accounts or users; a person reviews each flag and decides what to do.
 */
@Service
public class AnomalyService {

    private static final String TRANSACTION_SQL = """
            SELECT t.id, t.kind, t.status, t.amount, t.from_account_id, t.to_account_id, t.reject_reason,
                   u.email AS initiator_email
            FROM transactions t JOIN users u ON u.id = t.initiated_by
            WHERE t.id = ?
            """;

    /** Same source account, same status, debits only, within a window ending at this transaction. */
    private static final String RECENT_DEBITS_SQL = """
            SELECT COUNT(*)
            FROM transactions t JOIN transactions cur ON cur.id = ?
            WHERE t.from_account_id = cur.from_account_id
              AND t.status = ?
              AND t.kind IN ('WITHDRAWAL', 'TRANSFER')
              AND t.created_at > cur.created_at - (CAST(? AS double precision) * INTERVAL '1 second')
              AND t.created_at <= cur.created_at
            """;

    private static final String DEBIT_BALANCE_AFTER_SQL =
            "SELECT balance_after FROM ledger_entries WHERE transaction_id = ? AND account_id = ?";

    private static final String INSERT_FLAG_SQL = """
            INSERT INTO anomaly_flags (transaction_id, account_id, rule, severity, message)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (transaction_id, rule) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final AnomalyProperties properties;

    public AnomalyService(JdbcTemplate jdbc, AnomalyProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    private record Tx(long id, String kind, String status, long amount, Long fromAccountId, Long toAccountId,
                      String rejectReason, String initiatorEmail) {

        boolean isDebit() {
            return "WITHDRAWAL".equals(kind) || "TRANSFER".equals(kind);
        }

        /** The customer account the request was about: the target of a deposit, otherwise the source. */
        Long subjectAccountId() {
            return "DEPOSIT".equals(kind) ? toAccountId : fromAccountId;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluate(long transactionId) {
        List<Tx> rows = jdbc.query(TRANSACTION_SQL, (rs, rowNum) -> new Tx(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getLong("amount"),
                rs.getObject("from_account_id", Long.class),
                rs.getObject("to_account_id", Long.class),
                rs.getString("reject_reason"),
                rs.getString("initiator_email")), transactionId);
        if (rows.isEmpty()) {
            return;
        }
        Tx tx = rows.get(0);
        if ("POSTED".equals(tx.status())) {
            evaluatePosted(tx);
        } else {
            evaluateRejected(tx);
        }
    }

    private void evaluatePosted(Tx tx) {
        if (tx.amount() >= properties.largeAmountPaise() && tx.subjectAccountId() != null) {
            flag(AnomalyRule.LARGE_AMOUNT, tx, tx.subjectAccountId(), String.format(Locale.ROOT,
                    "%s of %s, at or above the %s threshold", tx.kind(), rupees(tx.amount()),
                    rupees(properties.largeAmountPaise())));
        }
        if (!tx.isDebit() || tx.fromAccountId() == null) {
            return;
        }
        long recentDebits = countRecentDebits(tx, "POSTED", properties.rapidDebitsWindowSeconds());
        if (recentDebits >= properties.rapidDebitsCount()) {
            flag(AnomalyRule.RAPID_DEBITS, tx, tx.fromAccountId(), String.format(Locale.ROOT,
                    "%d posted debits from this account within %d seconds", recentDebits,
                    properties.rapidDebitsWindowSeconds()));
        }
        if (tx.amount() >= properties.nearDrainMinAmountPaise()) {
            Long balanceAfter = jdbc.queryForObject(DEBIT_BALANCE_AFTER_SQL, Long.class, tx.id(), tx.fromAccountId());
            if (balanceAfter != null) {
                long priorBalance = balanceAfter + tx.amount();
                if (balanceAfter * 100 < priorBalance * properties.nearDrainRemainingPercent()) {
                    flag(AnomalyRule.NEAR_DRAIN, tx, tx.fromAccountId(), String.format(Locale.ROOT,
                            "%s of %s left %s of a prior balance of %s (under %d%%)", tx.kind(),
                            rupees(tx.amount()), rupees(balanceAfter), rupees(priorBalance),
                            properties.nearDrainRemainingPercent()));
                }
            }
        }
    }

    private void evaluateRejected(Tx tx) {
        if ("NOT_AUTHORISED".equals(tx.rejectReason()) && tx.subjectAccountId() != null) {
            flag(AnomalyRule.FOREIGN_PROBE, tx, tx.subjectAccountId(), String.format(Locale.ROOT,
                    "%s tried a %s of %s on an account they may not use", tx.initiatorEmail(), tx.kind(),
                    rupees(tx.amount())));
        }
        if (tx.isDebit() && tx.fromAccountId() != null) {
            long recentRefusals = countRecentDebits(tx, "REJECTED", properties.repeatedRefusalsWindowSeconds());
            if (recentRefusals >= properties.repeatedRefusalsCount()) {
                flag(AnomalyRule.REPEATED_REFUSALS, tx, tx.fromAccountId(), String.format(Locale.ROOT,
                        "%d refused debits from this account within %d minutes", recentRefusals,
                        properties.repeatedRefusalsWindowSeconds() / 60));
            }
        }
    }

    private long countRecentDebits(Tx tx, String status, long windowSeconds) {
        Long count = jdbc.queryForObject(RECENT_DEBITS_SQL, Long.class, tx.id(), status, windowSeconds);
        return count == null ? 0 : count;
    }

    private void flag(AnomalyRule rule, Tx tx, long accountId, String message) {
        jdbc.update(INSERT_FLAG_SQL, tx.id(), accountId, rule.name(), rule.severity().name(), message);
    }

    private static String rupees(long paise) {
        return String.format(Locale.ROOT, "₹%,d.%02d", paise / 100, Math.abs(paise % 100));
    }
}
