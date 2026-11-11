package com.ledgerline.money;

import org.springframework.http.HttpStatus;

public record MoneyResult(TransactionResponse body, HttpStatus status, boolean replayed) {
}
