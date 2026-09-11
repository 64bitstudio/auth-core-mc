#!/usr/bin/env bash
# Ticket auth-core-mc/051 -- migra el wrapped_data_key del tenant "Acme"
# (unico tenant local con datos reales dependientes del Vault local, ver
# pending/051-retirar-vault-local-dev.md) del Vault LOCAL a la Vault de
# la VM, usando SSH + la AppRole administrativa "platform-admin" -- NO
# depende de que vault.64bitstudio.com ya este expuesto publicamente
# (el DNS ya existe, pero este script usa SSH directo, no ese subdominio).
# Ademas renombra el tenant de "Acme"/"MC APP" a "64Bit Studio" (pedido
# explicito de Marco, 2026-09-02), sin tocar ningun otro campo.
#
# El agente (Claude Code) quedo bloqueado por el clasificador de permisos
# al intentar correr el paso de migracion el mismo -- pensado para que
# Marco lo corra directamente. Un solo comando:
#
#   ./backend/scripts/migrate-and-rename-acme-tenant.sh
#
# Que hace (ver comentarios inline para el detalle completo de cada
# paso): desenvuelve la data-key cruda de Acme con el Vault local,
# la envuelve (mismos bytes, nunca genera una nueva) con la Vault de la
# VM, verifica que el round-trip preserva exactamente la misma data-key
# (comparando SHA-256, nunca imprime la data-key en si), y solo entonces
# actualiza tenant.wrapped_data_key en la base de datos local. Aborta sin
# tocar la BD si el hash no coincide. Al final, renombra el tenant.

set -euo pipefail

TENANT_ID="11111111-1111-1111-1111-111111111111"
DB_CONTAINER="auth-core-mc-postgres-1"
DB_USER="auth_core_mc"
DB_NAME="auth_core_mc"
NEW_NAME="64Bit Studio"
NEW_APP_NAME="64Bit Studio"

cd "$HOME/dev-infra"
source .env

echo "== 1/5: desenvolviendo la data-key de Acme con el Vault local =="
WRAPPED_OLD=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A -c \
  "SELECT wrapped_data_key FROM tenant WHERE id='$TENANT_ID';")
PLAINTEXT_B64=$(docker exec -e VAULT_TOKEN="$VAULT_ROOT_TOKEN" vault vault write -field=plaintext transit/decrypt/auth-core-mc-tenant-keys ciphertext="$WRAPPED_OLD")
HASH_OLD=$(printf '%s' "$PLAINTEXT_B64" | shasum -a 256 | awk '{print $1}')
echo "   OK (sha256 de la data-key cruda, no la key en si: $HASH_OLD)"

echo "== 2/5: envolviendo la MISMA data-key con la Vault de la VM (platform-admin, via SSH) =="
NEW_WRAPPED=$(printf '%s\n' "$PLAINTEXT_B64" | ssh ampere-free '
set -euo pipefail
read -r PLAINTEXT
ADMIN_ROLE_ID=$(cat /home/ubuntu/secrets/vault/platform-admin-role-id)
ADMIN_SECRET_ID=$(cat /home/ubuntu/secrets/vault/platform-admin-secret-id)
LOGIN_OUT=$(docker exec vault vault write -format=json auth/approle/login role_id="$ADMIN_ROLE_ID" secret_id="$ADMIN_SECRET_ID")
ADMIN_TOKEN=$(printf "%s" "$LOGIN_OUT" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"auth\"][\"client_token\"])")
docker exec -e VAULT_TOKEN="$ADMIN_TOKEN" vault vault write -field=ciphertext transit/encrypt/auth-core-mc-tenant-keys plaintext="$PLAINTEXT"
docker exec -e VAULT_TOKEN="$ADMIN_TOKEN" vault vault token revoke -self >/dev/null 2>&1 || true
')
echo "   OK (nuevo wrapped_data_key generado, longitud ${#NEW_WRAPPED})"

echo "== 3/5: verificando el round-trip (debe dar la MISMA data-key cruda) =="
HASH_NEW=$(printf '%s\n' "$NEW_WRAPPED" | ssh ampere-free '
set -euo pipefail
read -r CIPHERTEXT
ADMIN_ROLE_ID=$(cat /home/ubuntu/secrets/vault/platform-admin-role-id)
ADMIN_SECRET_ID=$(cat /home/ubuntu/secrets/vault/platform-admin-secret-id)
LOGIN_OUT=$(docker exec vault vault write -format=json auth/approle/login role_id="$ADMIN_ROLE_ID" secret_id="$ADMIN_SECRET_ID")
ADMIN_TOKEN=$(printf "%s" "$LOGIN_OUT" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"auth\"][\"client_token\"])")
PT=$(docker exec -e VAULT_TOKEN="$ADMIN_TOKEN" vault vault write -field=plaintext transit/decrypt/auth-core-mc-tenant-keys ciphertext="$CIPHERTEXT")
docker exec -e VAULT_TOKEN="$ADMIN_TOKEN" vault vault token revoke -self >/dev/null 2>&1 || true
printf "%s" "$PT" | shasum -a 256 | awk "{print \$1}"
')
if [ "$HASH_OLD" != "$HASH_NEW" ]; then
  echo "   MISMATCH -- algo salio mal, NO se toca la base de datos." >&2
  exit 1
fi
echo "   OK: hash identico, la data-key cruda no cambio."

echo "== 4/5: actualizando tenant.wrapped_data_key en la base de datos local =="
docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "UPDATE tenant SET wrapped_data_key = '$NEW_WRAPPED' WHERE id = '$TENANT_ID';"

echo "== 5/5: renombrando el tenant de \"Acme\"/\"MC APP\" a \"$NEW_NAME\" =="
docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "UPDATE tenant SET name = '$NEW_NAME', app_name = '$NEW_APP_NAME' WHERE id = '$TENANT_ID';"

echo ""
echo "Listo. El tenant (ahora \"$NEW_NAME\") ya usa la Vault de la VM -- verificar con una operacion real de la app cuando application.properties apunte ahi."
