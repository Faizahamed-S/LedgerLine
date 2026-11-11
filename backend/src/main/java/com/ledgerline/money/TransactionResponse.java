package com.ledgerline.money;

import com.ledgerline.domain.Account;
import com.ledgerline.domain.LedgerTransaction;
import com.ledgerline.domain.TransactionKind;
import com.ledgerline.domain.TransactionStatus;

import java.time.Instant;

public record TransactionResponse(Long transactionId, TransactionKind kind, TransactionStatus status,
                                  long amountPaise, Long fromAccountId, Long toAccountId, String rejectReason,
                                  Instant createdAt) {

    public static TransactionResponse from(LedgerTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getKind(),
                transaction.getStatus(),
                transaction.getAmount(),
                idOf(transaction.getFromAccount()),
                idOf(transaction.getToAccount()),
                transaction.getRejectReason(),
                transaction.getCreatedAt());
    }

    private static Long idOf(Account account) {
        return account == null ? null : account.getId();
    }
}
