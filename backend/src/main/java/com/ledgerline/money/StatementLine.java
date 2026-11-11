package com.ledgerline.money;

import com.ledgerline.domain.TransactionKind;
import com.ledgerline.domain.TransactionStatus;

import java.time.Instant;

public record StatementLine(Instant createdAt, Long transactionId, TransactionKind kind, StatementDirection direction,
                            long amountPaise, Long balanceAfterPaise, Long counterpartyAccountId,
                            String initiatedByEmail, TransactionStatus status, String rejectReason) {
}
