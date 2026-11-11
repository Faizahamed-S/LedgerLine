package com.ledgerline.account;

import com.ledgerline.domain.Account;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.User;

import java.time.Instant;

public record AccountResponse(Long id, String name, AccountType type, AccountStatus status, long balancePaise,
                              Long ownerId, String ownerEmail, Instant createdAt) {

    public static AccountResponse from(Account account) {
        User owner = account.getOwner();
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getStatus(),
                account.getBalance(),
                owner == null ? null : owner.getId(),
                owner == null ? null : owner.getEmail(),
                account.getCreatedAt());
    }
}
