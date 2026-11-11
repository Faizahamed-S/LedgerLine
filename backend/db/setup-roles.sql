-- One-time setup of the LedgerLine database roles. NOT a Flyway migration.
-- Run manually as a superuser, connected to the ledgerline database, BEFORE the app's first start:
--
--   psql -h localhost -U postgres -d ledgerline \
--        -v owner_pw='<owner password>' -v app_pw='<app password>' \
--        -f backend/db/setup-roles.sql
--
-- The passwords must match DB_OWNER_PASSWORD and DB_APP_PASSWORD given to the backend
-- (see application.yml for the local-dev defaults).
--
-- ledger_owner: owns the schema; Flyway connects as this role to run migrations.
-- ledger_app:   used by the running application; privileges are granted per table in V3.

\set ON_ERROR_STOP on

CREATE ROLE ledger_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE ledger_app LOGIN PASSWORD :'app_pw';

ALTER SCHEMA public OWNER TO ledger_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
