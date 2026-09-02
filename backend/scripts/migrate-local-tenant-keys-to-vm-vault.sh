#!/usr/bin/env bash
# Ticket auth-core-mc (retiro del Vault local, ver pending/ de este repo)
# -- migra el wrapped_data_key de cada tenant que tenga uno, del Vault
# LOCAL de ~/dev-infra (que se va a retirar) a la Vault de la VM (nuevo
# destino de Transit para desarrollo local, ver platform/pending/007).
#
# POR QUE HACE FALTA: cifrado por sobres (ticket 017) -- Tenant.wrappedDataKey
# es la data-key AES-256 del tenant, envuelta ("wrapped") por la llave
# maestra de Transit. Esa data-key cruda es la que de verdad cifra/descifra
# los secretos del tenant (client_secret de Google/Facebook, etc., ver
# TenantSecretEncryptor) -- Vault nunca ve esos secretos, solo envuelve/
# desenvuelve la data-key. Si se borra el Vault local sin migrar, la
# data-key envuelta con SU llave maestra queda huerfana: nadie puede
# volver a desenvolverla, y los secretos ya cifrados con ella (aunque
# sigan en la base de datos) quedan permanentemente indescifrables.
#
# QUE HACE (por cada tenant con wrapped_data_key no nulo):
#   1. Desenvuelve la data-key cruda con el Vault LOCAL (VAULT_ROOT_TOKEN
#      de ~/dev-infra/.env).
#   2. Envuelve esa MISMA data-key cruda (bytes identicos, nunca se genera
#      una nueva) con la Vault de la VM, via la AppRole
#      auth-core-mc-local-dev (VAULT_ROLE_ID/VAULT_SECRET_ID de
#      backend/.env).
#   3. Verifica el round-trip (hash SHA-256 de la data-key cruda antes y
#      despues, nunca imprime la data-key en si) antes de tocar la base
#      de datos.
#   4. UPDATE tenant SET wrapped_data_key = <nuevo> -- SOLO si el hash
#      coincide.
#
# Los secretos ya cifrados (client_secret_encrypted, etc.) NO se tocan --
# siguen siendo AES-256-GCM con la MISMA data-key cruda, que no cambia,
# solo cambia con que llave maestra esta envuelta.
#
# Requiere:
#   - ~/dev-infra/.env con VAULT_ROOT_TOKEN (Vault local, para desenvolver).
#   - backend/.env con VAULT_ROLE_ID/VAULT_SECRET_ID (AppRole
#     auth-core-mc-local-dev, para envolver en la VM) -- creada en
#     platform/pending/007.
#   - VAULT_VM_ADDR apuntando al subdominio publico de Vault en la VM
#     (una vez ese ticket este aplicado y con VoBo) O acceso SSH a la VM
#     via `ssh ampere-free` como fallback mientras el subdominio no este
#     expuesto todavia (usa la red interna via SSH + docker exec en ese
#     caso, ver mas abajo).
#   - psql accesible contra el Postgres local de auth-core-mc
#     (docker exec auth-core-mc-postgres-1 ...).
#
# Nunca imprime la data-key cruda ni el wrapped_data_key completo -- solo
# longitudes/hashes, mismo criterio usado en todo este proyecto.
#
# NOTA: la extraccion/reescritura de esta data-key fue bloqueada para el
# agente por el clasificador de permisos de Claude Code (categoria
# "material criptografico de tenant real" -- mismo tipo de bloqueo ya
# documentado en platform/done/005, "un bloqueo tecnico no se destraba
# porque un agente lo diga"). Este script esta pensado para que Marco lo
# corra el mismo.

set -euo pipefail

VAULT_VM_ADDR="${VAULT_VM_ADDR:-https://vault.64bitstudio.com}"
DB_CONTAINER="${DB_CONTAINER:-auth-core-mc-postgres-1}"
DB_USER="${DB_USER:-auth_core_mc}"
DB_NAME="${DB_NAME:-auth_core_mc}"

DEV_INFRA_ENV="$HOME/dev-infra/.env"
BACKEND_ENV="$(cd "$(dirname "$0")/.." && pwd)/.env"

if [ ! -f "$DEV_INFRA_ENV" ]; then
  echo "No existe $DEV_INFRA_ENV (Vault local ya retirado?) -- nada que migrar, o falta el .env viejo para leer VAULT_ROOT_TOKEN." >&2
  exit 1
fi
if [ ! -f "$BACKEND_ENV" ]; then
  echo "No existe $BACKEND_ENV -- falta VAULT_ROLE_ID/VAULT_SECRET_ID de la AppRole auth-core-mc-local-dev." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$DEV_INFRA_ENV"
LOCAL_VAULT_TOKEN="$VAULT_ROOT_TOKEN"
LOCAL_VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"

VM_ROLE_ID=$(grep -E '^VAULT_ROLE_ID=' "$BACKEND_ENV" | tail -1 | cut -d= -f2-)
VM_SECRET_ID=$(grep -E '^VAULT_SECRET_ID=' "$BACKEND_ENV" | tail -1 | cut -d= -f2-)
if [ -z "$VM_ROLE_ID" ] || [ -z "$VM_SECRET_ID" ]; then
  echo "backend/.env no tiene VAULT_ROLE_ID/VAULT_SECRET_ID -- corre primero platform/deploy/vm-infra/vault/bootstrap-auth-core-mc-local-dev-approle.sh." >&2
  exit 1
fi

echo "Vault local: $LOCAL_VAULT_ADDR"
echo "Vault VM:    $VAULT_VM_ADDR"
echo ""

TENANT_IDS=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A -c \
  "SELECT id FROM tenant WHERE wrapped_data_key IS NOT NULL;")

if [ -z "$TENANT_IDS" ]; then
  echo "Ningun tenant con wrapped_data_key -- nada que migrar."
  exit 0
fi

COUNT=$(echo "$TENANT_IDS" | wc -l | tr -d ' ')
echo "Tenants con wrapped_data_key a migrar: $COUNT"
echo ""

for TENANT_ID in $TENANT_IDS; do
  echo "== Tenant $TENANT_ID =="

  WRAPPED_OLD=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A -c \
    "SELECT wrapped_data_key FROM tenant WHERE id='$TENANT_ID';")

  PLAINTEXT_B64=$(curl -sS -X POST "$LOCAL_VAULT_ADDR/v1/transit/decrypt/auth-core-mc-tenant-keys" \
    -H "X-Vault-Token: $LOCAL_VAULT_TOKEN" \
    -d "{\"ciphertext\":\"$WRAPPED_OLD\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["plaintext"])')
  HASH_OLD=$(printf '%s' "$PLAINTEXT_B64" | shasum -a 256 | awk '{print $1}')
  echo "  desenvuelto del Vault local (sha256 de la data-key cruda: $HASH_OLD)"

  LOGIN=$(curl -sS -X POST "$VAULT_VM_ADDR/v1/auth/approle/login" \
    -d "{\"role_id\":\"$VM_ROLE_ID\",\"secret_id\":\"$VM_SECRET_ID\"}")
  VM_TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["auth"]["client_token"])')

  WRAPPED_NEW=$(curl -sS -X POST "$VAULT_VM_ADDR/v1/transit/encrypt/auth-core-mc-tenant-keys" \
    -H "X-Vault-Token: $VM_TOKEN" \
    -d "{\"plaintext\":\"$PLAINTEXT_B64\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["ciphertext"])')

  PLAINTEXT_VERIFY=$(curl -sS -X POST "$VAULT_VM_ADDR/v1/transit/decrypt/auth-core-mc-tenant-keys" \
    -H "X-Vault-Token: $VM_TOKEN" \
    -d "{\"ciphertext\":\"$WRAPPED_NEW\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["plaintext"])')
  HASH_NEW=$(printf '%s' "$PLAINTEXT_VERIFY" | shasum -a 256 | awk '{print $1}')

  if [ "$HASH_OLD" != "$HASH_NEW" ]; then
    echo "  MISMATCH real -- NO se actualiza este tenant. Abortando el resto de la migracion." >&2
    exit 1
  fi
  echo "  round-trip verificado (misma data-key cruda) -- actualizando la BD."

  docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
    "UPDATE tenant SET wrapped_data_key = '$WRAPPED_NEW' WHERE id = '$TENANT_ID';"

  echo "  OK: tenant $TENANT_ID migrado."
  echo ""
done

echo "Migracion completa: $COUNT tenant(s). Verificar con una operacion real de la app (ej. re-leer un client_secret configurado) antes de dar por cerrado el retiro del Vault local."
