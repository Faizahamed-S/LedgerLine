package com.ledgerline.anomaly;

import java.time.Instant;

public record FlagReviewResponse(String decision, String note, long reviewerId, String reviewerEmail,
                                 Instant reviewedAt) {
}
