#!/usr/bin/env bash
set -euo pipefail
# Invoked once by the PostgreSQL image, before the application runs Flyway.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=owner_password="$LEDGER_OWNER_PASSWORD" --set=app_password="$LEDGER_APP_PASSWORD" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE ROLE ledger_owner LOGIN PASSWORD :'owner_password' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE ledger_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOBYPASSRLS;
GRANT USAGE,CREATE ON SCHEMA public TO ledger_owner;
GRANT USAGE ON SCHEMA public TO ledger_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ledger_owner IN SCHEMA public GRANT SELECT,INSERT,UPDATE ON TABLES TO ledger_app;
SQL
