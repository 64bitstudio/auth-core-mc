#!/usr/bin/env bash
# Ticket 008: loads a tenant dump produced by export-tenant.sh into a target
# database. That target is meant to be a BRAND NEW dedicated instance whose
# schema was already created by Flyway (i.e. the app has already been
# started once — and stopped — against this database, so V1__init.sql and
# V2__two_factor_method.sql already ran) and that has NO other tenant's data
# in it yet — this script does not filter or deduplicate, so importing into
# a database that already has rows with the same primary keys fails loudly
# (UNIQUE/PK violation) instead of silently overwriting anything.
#
# Usage:
#   PGHOST=... PGPORT=... PGUSER=... PGPASSWORD=... PGDATABASE=... \
#     ./import-tenant.sh <dump_file>
set -euo pipefail

DUMP_FILE="${1:?Usage: import-tenant.sh <dump_file>}"

if [[ ! -f "${DUMP_FILE}" ]]; then
  echo "No such file: ${DUMP_FILE}" >&2
  exit 1
fi

psql -X -v ON_ERROR_STOP=1 -f "${DUMP_FILE}"

echo "Imported ${DUMP_FILE}" >&2
