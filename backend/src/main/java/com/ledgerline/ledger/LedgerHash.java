package com.ledgerline.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The per-account hash chain on ledger entries. Each entry's hash covers the previous entry's hash, so changing,
 * inserting or removing any entry breaks every link after it, even if done by the table owner.
 */
public final class LedgerHash {

    /** prev_hash of an account's first hashed entry. */
    public static final String GENESIS = "GENESIS";

    private LedgerHash() {
    }

    /** SHA-256 hex of "prev_hash|transaction_id|account_id|amount|balance_after". */
    public static String compute(String prevHash, long transactionId, long accountId, long amount, long balanceAfter) {
        String canonical = prevHash + "|" + transactionId + "|" + accountId + "|" + amount + "|" + balanceAfter;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
