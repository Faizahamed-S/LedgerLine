package com.ledgerline.audit;

public record MismatchedAccount(Long accountId, long balancePaise, long ledgerSumPaise) {
}
