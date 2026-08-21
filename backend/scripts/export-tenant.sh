#!/usr/bin/env bash
# Ticket 008: exports a single tenant's data — and only that tenant's data —
# into a portable, plain-SQL file that import-tenant.sh can load into a
# fresh, dedicated instance. This is the "clonarse 1:1 a instancia dedicada"
# half of the ticket's acceptance criteria.
#
# Why not just `pg_dump --where=...`? Stock pg_dump has no per-row filter
# for a data-only dump — it dumps whole tables or nothing. Instead, this
# script uses psql's `\copy (SELECT ... WHERE tenant_id = ...) TO STDOUT`
# per table, wrapped in the same `COPY <table> (<cols>) FROM stdin; ... \.`
# blocks pg_dump itself would emit for a full table — so the output file is
# ordinary, boring SQL that any `psql -f` can replay, not a bespoke format.
#
# Why the tenant id is embedded directly instead of passed as a psql -v
# variable: found out live (not in any test) that psql's `:'var'`
# substitution isn't reliably applied inside a `\copy (...)` sub-query
# across psql builds — it's a backslash command with its own ad-hoc
# tokenizer, not a regular SQL statement. Embedding the value directly is
# safe here specifically because it's validated as a well-formed UUID
# first (see below) — never do this with a value that hasn't been
# validated against a fixed shape.
#
# Table order matters: children are exported after their parents so
# import-tenant.sh can load them in the same order without FK errors
# (tenant -> app_user/tenant_identity_provider/identity_client -> refresh_token).
#
# Usage:
#   PGHOST=... PGPORT=... PGUSER=... PGPASSWORD=... PGDATABASE=... \
#     ./export-tenant.sh <tenant_id> [output_file]
#
# Any libpq connection env var (PGHOST, PGPORT, PGUSER, PGPASSWORD,
# PGDATABASE) or a plain PGDATABASE-less `psql` alias set up beforehand
# works — this script only ever calls `psql`, never touches the connection
# details itself, so it works unchanged in a local Docker Compose, an
# already-tunneled remote box, or a CI runner.
set -euo pipefail

TENANT_ID="${1:?Usage: export-tenant.sh <tenant_id> [output_file]}"
OUT="${2:-tenant-${TENANT_ID}.sql}"

UUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
if [[ ! "${TENANT_ID}" =~ ${UUID_RE} ]]; then
  echo "Not a well-formed UUID: ${TENANT_ID}" >&2
  exit 1
fi

copy_block() {
  local header="$1" query="$2"
  echo "COPY ${header} FROM stdin;"
  psql -X -v ON_ERROR_STOP=1 -c "\\copy (${query}) TO STDOUT"
  echo '\.'
  echo
}

{
  echo "-- Exported tenant ${TENANT_ID} on $(date -u +%FT%TZ) — see backend/scripts/import-tenant.sh"
  echo "BEGIN;"
  echo

  copy_block \
    "tenant (id, name, app_name, primary_color, access_token_ttl_seconds, refresh_token_ttl_seconds, email_verification_ttl_seconds, password_reset_ttl_seconds, otp_ttl_seconds, created_at)" \
    "SELECT id, name, app_name, primary_color, access_token_ttl_seconds, refresh_token_ttl_seconds, email_verification_ttl_seconds, password_reset_ttl_seconds, otp_ttl_seconds, created_at FROM tenant WHERE id = '${TENANT_ID}'"

  copy_block \
    "app_user (id, tenant_id, email, phone, nombre, apellidos, password_hash, email_verified, phone_verified, totp_secret_encrypted, created_at, two_factor_method)" \
    "SELECT id, tenant_id, email, phone, nombre, apellidos, password_hash, email_verified, phone_verified, totp_secret_encrypted, created_at, two_factor_method FROM app_user WHERE tenant_id = '${TENANT_ID}'"

  copy_block \
    "tenant_identity_provider (id, tenant_id, provider, enabled, client_id, client_secret_encrypted)" \
    "SELECT id, tenant_id, provider, enabled, client_id, client_secret_encrypted FROM tenant_identity_provider WHERE tenant_id = '${TENANT_ID}'"

  copy_block \
    "identity_client (id, tenant_id, client_id, client_secret_hash, is_first_party, redirect_uris)" \
    "SELECT id, tenant_id, client_id, client_secret_hash, is_first_party, redirect_uris FROM identity_client WHERE tenant_id = '${TENANT_ID}'"

  copy_block \
    "refresh_token (id, user_id, client_id, token_hash, revoked, expires_at)" \
    "SELECT rt.id, rt.user_id, rt.client_id, rt.token_hash, rt.revoked, rt.expires_at FROM refresh_token rt JOIN app_user u ON u.id = rt.user_id WHERE u.tenant_id = '${TENANT_ID}'"

  echo "COMMIT;"
} > "${OUT}"

echo "Exported tenant ${TENANT_ID} to ${OUT}" >&2
