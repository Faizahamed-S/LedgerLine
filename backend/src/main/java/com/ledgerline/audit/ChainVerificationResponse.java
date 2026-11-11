package com.ledgerline.audit;

import java.time.Instant;
import java.util.List;

public record ChainVerificationResponse(
        boolean intact,
        long accountsChecked,
        long entriesChecked,
        long unchainedLegacyEntries,
        List<ChainBreak> breaks,
        Instant checkedAt) {
}
