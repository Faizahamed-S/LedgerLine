package com.ledgerline.anomaly;

import java.time.Instant;

/** {@code review} is null while the flag is OPEN. */
public record FlagResponse(long id, Long transactionId, long accountId, String rule, String severity, String message,
                           Instant createdAt, FlagReviewResponse review) {
}
