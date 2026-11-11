-- V3: least-privilege grants for ledger_app, and append-only enforcement for money/audit tables.
-- Runs as ledger_owner. ledger_app gets no DELETE or TRUNCATE on any table.

GRANT USAGE ON SCHEMA public TO ledger_app;

GRANT SELECT, INSERT ON transactions   TO ledger_app;
GRANT SELECT, INSERT ON ledger_entries TO ledger_app;
GRANT SELECT, INSERT ON audit_log      TO ledger_app;

GRANT SELECT, INSERT ON accounts TO ledger_app;
GRANT UPDATE (balance, status, name) ON accounts TO ledger_app;

GRANT SELECT, INSERT ON users TO ledger_app;
GRANT UPDATE (password_hash, full_name, role, status) ON users TO ledger_app;

GRANT SELECT, INSERT ON idempotency_keys TO ledger_app;
GRANT UPDATE (transaction_id, replay_count, last_replayed_at) ON idempotency_keys TO ledger_app;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_app;

REVOKE ALL ON flyway_schema_history FROM ledger_app;

CREATE FUNCTION forbid_ledger_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% on table % is not allowed: the table is append-only', TG_OP, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER transactions_no_update_delete
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER transactions_no_truncate
    BEFORE TRUNCATE ON transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_change();
