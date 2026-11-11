package com.ledgerline.money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotNull @Positive @Max(MoneyLimits.MAX_AMOUNT_PAISE) Long amountPaise) {
}
