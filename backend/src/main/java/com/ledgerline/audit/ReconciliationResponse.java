package com.ledgerline.audit;

import java.time.Instant;
import java.util.List;

public record ReconciliationResponse(
        long ledgerSumPaise,
        long customerTotalPaise,
        long externalBalancePaise,
        boolean balanced,
        List<MismatchedAccount> mismatchedAccounts,
        long postedCount,
        long rejectedCount,
        long duplicateRequestCount,
        Instant checkedAt) {
}
