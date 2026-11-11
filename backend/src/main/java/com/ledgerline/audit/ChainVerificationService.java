package com.ledgerline.audit;

import com.ledgerline.ledger.LedgerHash;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Re-walks every account's hash chain. Read-only, one consistent snapshot, no locks. */
@Service
public class ChainVerificationService {

    public static final String HASH_MISMATCH = "HASH_MISMATCH";
    public static final String PREV_HASH_MISMATCH = "PREV_HASH_MISMATCH";
    public static final String MISSING_HASH = "MISSING_HASH";

    private static final String ENTRIES_SQL = """
            SELECT id, transaction_id, account_id, amount, balance_after, prev_hash, entry_hash
            FROM ledger_entries
            ORDER BY account_id, id
            """;

    private final JdbcTemplate jdbc;

    public ChainVerificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChainVerificationResponse verify() {
        ChainWalker walker = new ChainWalker();
        jdbc.query(ENTRIES_SQL, walker);
        return new ChainVerificationResponse(walker.breaks.isEmpty(), walker.accounts, walker.entries,
                walker.legacy, walker.breaks, Instant.now());
    }

    /**
     * Per account, in id order: entries without hashes before the first hashed entry are legacy (pre-V4); after
     * that, every entry must link to the previous entry's stored hash and its own hash must recompute. A changed
     * entry therefore shows up as exactly one HASH_MISMATCH at that entry.
     */
    private static final class ChainWalker implements RowCallbackHandler {

        private final List<ChainBreak> breaks = new ArrayList<>();
        private long accounts;
        private long entries;
        private long legacy;

        private Long currentAccount;
        private boolean chainStarted;
        private String previousHash;

        @Override
        public void processRow(ResultSet rs) throws SQLException {
            long entryId = rs.getLong("id");
            long transactionId = rs.getLong("transaction_id");
            long accountId = rs.getLong("account_id");
            long amount = rs.getLong("amount");
            long balanceAfter = rs.getLong("balance_after");
            String prevHash = rs.getString("prev_hash");
            String entryHash = rs.getString("entry_hash");

            if (!Objects.equals(currentAccount, accountId)) {
                currentAccount = accountId;
                chainStarted = false;
                previousHash = null;
                accounts++;
            }
            entries++;

            if (entryHash == null && prevHash == null && !chainStarted) {
                legacy++;
                return;
            }

            String expectedPrev = previousHash == null ? LedgerHash.GENESIS : previousHash;
            if (entryHash == null || prevHash == null) {
                breaks.add(new ChainBreak(accountId, entryId, MISSING_HASH, expectedPrev, entryHash));
            } else {
                if (!prevHash.equals(expectedPrev)) {
                    breaks.add(new ChainBreak(accountId, entryId, PREV_HASH_MISMATCH, expectedPrev, prevHash));
                }
                String recomputed = LedgerHash.compute(prevHash, transactionId, accountId, amount, balanceAfter);
                if (!recomputed.equals(entryHash)) {
                    breaks.add(new ChainBreak(accountId, entryId, HASH_MISMATCH, recomputed, entryHash));
                }
            }
            chainStarted = true;
            previousHash = entryHash;
        }
    }
}
