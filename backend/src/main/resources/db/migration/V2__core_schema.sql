-- V2: core schema. All money columns are BIGINT in paise.

CREATE TABLE users (
    id            BIGSERIAL   PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    full_name     TEXT        NOT NULL,
    role          TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_role_check   CHECK (role IN ('ADMIN', 'HOLDER', 'AUDITOR')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE accounts (
    id         BIGSERIAL   PRIMARY KEY,
    owner_id   BIGINT      NULL REFERENCES users (id),
    name       TEXT        NOT NULL,
    type       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'PENDING',
    balance    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_type_check             CHECK (type IN ('CUSTOMER', 'EXTERNAL')),
    CONSTRAINT accounts_status_check           CHECK (status IN ('PENDING', 'ACTIVE', 'FROZEN')),
    CONSTRAINT accounts_non_negative_balance   CHECK (type = 'EXTERNAL' OR balance >= 0),
    CONSTRAINT accounts_owner_matches_type     CHECK ((type = 'EXTERNAL') = (owner_id IS NULL))
);

CREATE UNIQUE INDEX accounts_single_external_idx ON accounts (type) WHERE type = 'EXTERNAL';

CREATE TABLE transactions (
    id                  BIGSERIAL   PRIMARY KEY,
    kind                TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    amount              BIGINT      NOT NULL,
    from_account_id     BIGINT      NULL REFERENCES accounts (id),
    to_account_id       BIGINT      NULL REFERENCES accounts (id),
    initiated_by        BIGINT      NOT NULL REFERENCES users (id),
    reject_reason       TEXT        NULL,
    balance_at_decision BIGINT      NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transactions_kind_check          CHECK (kind IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT transactions_status_check        CHECK (status IN ('POSTED', 'REJECTED')),
    CONSTRAINT transactions_amount_positive     CHECK (amount > 0),
    CONSTRAINT transactions_reject_reason_check CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

CREATE INDEX transactions_from_account_idx ON transactions (from_account_id);
CREATE INDEX transactions_to_account_idx   ON transactions (to_account_id);

CREATE TABLE ledger_entries (
    id             BIGSERIAL   PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES transactions (id),
    account_id     BIGINT      NOT NULL REFERENCES accounts (id),
    amount         BIGINT      NOT NULL,
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_amount_non_zero CHECK (amount <> 0)
);

CREATE INDEX ledger_entries_account_id_idx ON ledger_entries (account_id, id);

CREATE TABLE idempotency_keys (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id),
    idempotency_key  TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,
    transaction_id   BIGINT      NULL REFERENCES transactions (id),
    replay_count     INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_replayed_at TIMESTAMPTZ NULL,
    CONSTRAINT idempotency_keys_user_key_unique UNIQUE (user_id, idempotency_key)
);

CREATE TABLE audit_log (
    id          BIGSERIAL   PRIMARY KEY,
    actor_id    BIGINT      NULL REFERENCES users (id),
    action      TEXT        NOT NULL,
    target_type TEXT        NULL,
    target_id   BIGINT      NULL,
    details     JSONB       NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO accounts (owner_id, name, type, status, balance)
VALUES (NULL, 'External World', 'EXTERNAL', 'ACTIVE', 0);
