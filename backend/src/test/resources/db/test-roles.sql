-- TEST-ONLY roles for the Testcontainers database. These passwords exist only in throwaway test containers.
-- Production roles are created with backend/db/setup-roles.sql, whose passwords come from psql variables.
CREATE ROLE ledger_owner LOGIN PASSWORD 'owner_test';
CREATE ROLE ledger_app LOGIN PASSWORD 'app_test';
ALTER SCHEMA public OWNER TO ledger_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
