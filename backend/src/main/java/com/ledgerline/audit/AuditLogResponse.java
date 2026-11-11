package com.ledgerline.audit;

import java.time.Instant;

/** {@code details} is the stored JSON, returned as a raw string. */
public record AuditLogResponse(Long id, Instant createdAt, Long actorId, String actorEmail, String action,
                               String targetType, Long targetId, String details) {
}
