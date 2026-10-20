#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set credit_password="$CREDIT_DB_PASSWORD" --set assessment_password="$ASSESSMENT_DB_PASSWORD" <<'SQL'
CREATE ROLE credit LOGIN PASSWORD :'credit_password';
CREATE ROLE assessment LOGIN PASSWORD :'assessment_password';
CREATE SCHEMA core AUTHORIZATION credit;
CREATE SCHEMA auth AUTHORIZATION credit;
CREATE SCHEMA assessment AUTHORIZATION assessment;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
