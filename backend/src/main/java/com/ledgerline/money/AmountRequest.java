package com.ledgerline.money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Body of a deposit or withdrawal. */
public record AmountRequest(
        @NotNull @Positive @Max(MoneyLimits.MAX_AMOUNT_PAISE) Long amountPaise) {
}
