-- V4: per-account hash chain on ledger entries, and rule-based anomaly flags with human review.

-- Nullable so entries written before V4 stay valid; the application sets both on every new entry.
ALTER TABLE ledger_entries
    ADD COLUMN prev_hash  TEXT NULL,
    ADD COLUMN entry_hash TEXT NULL;

CREATE TABLE anomaly_flags (
    id             BIGSERIAL   PRIMARY KEY,
    transaction_id BIGINT      NULL REFERENCES transactions (id),
    account_id     BIGINT      NOT NULL REFERENCES accounts (id),
    rule           TEXT        NOT NULL,
    severity       TEXT        NOT NULL,
    message        TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT anomaly_flags_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT anomaly_flags_transaction_rule_unique UNIQUE (transaction_id, rule)
);

CREATE INDEX anomaly_flags_created_at_idx ON anomaly_flags (created_at);

CREATE TABLE flag_reviews (
    id          BIGSERIAL   PRIMARY KEY,
    flag_id     BIGINT      NOT NULL UNIQUE REFERENCES anomaly_flags (id),
    reviewer_id BIGINT      NOT NULL REFERENCES users (id),
    decision    TEXT        NOT NULL,
    note        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT flag_reviews_decision_check CHECK (decision IN ('DISMISSED', 'CONFIRMED'))
);

GRANT SELECT, INSERT ON anomaly_flags TO ledger_app;
GRANT SELECT, INSERT ON flag_reviews  TO ledger_app;
GRANT USAGE, SELECT ON SEQUENCE anomaly_flags_id_seq, flag_reviews_id_seq TO ledger_app;

CREATE TRIGGER anomaly_flags_no_update_delete
    BEFORE UPDATE OR DELETE ON anomaly_flags
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER anomaly_flags_no_truncate
    BEFORE TRUNCATE ON anomaly_flags
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER flag_reviews_no_update_delete
    BEFORE UPDATE OR DELETE ON flag_reviews
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER flag_reviews_no_truncate
    BEFORE TRUNCATE ON flag_reviews
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_change();
