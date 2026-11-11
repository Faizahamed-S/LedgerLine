package com.ledgerline.money;

/** Published when a money request was decided and recorded (POSTED or REJECTED). Not published for replays. */
public record MoneyRecordedEvent(long transactionId) {
}
