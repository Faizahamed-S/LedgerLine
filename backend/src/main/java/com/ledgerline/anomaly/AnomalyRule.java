package com.ledgerline.anomaly;

public enum AnomalyRule {
    LARGE_AMOUNT(Severity.HIGH),
    FOREIGN_PROBE(Severity.HIGH),
    REPEATED_REFUSALS(Severity.MEDIUM),
    RAPID_DEBITS(Severity.MEDIUM),
    NEAR_DRAIN(Severity.LOW);

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    private final Severity severity;

    AnomalyRule(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
