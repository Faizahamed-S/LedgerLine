#!/bin/sh
# Runs inside the postgres container on first initialisation of an EMPTY data volume only.
# Reuses backend/db/setup-roles.sql (mounted at /setup) so the role SQL has a single source of truth.
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v owner_pw="$LEDGER_OWNER_PASSWORD" -v app_pw="$LEDGER_APP_PASSWORD" \
  -f /setup/setup-roles.sql
