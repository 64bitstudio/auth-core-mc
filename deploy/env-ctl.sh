#!/usr/bin/env bash
# Ticket 049 (rediseño dev/qa/prod): arranque/parada manual de los stacks
# DEV y QA en la VM — corren "bajo demanda" (VM de 2 OCPU/12GB compartida
# con QA/PROD/SonarQube/Traefik/el runner), a diferencia de PROD, que
# siempre está activo. El propio pipeline (deploy-dev/deploy-qa) ya hace
# "docker compose up -d" en cada push — este script es para cuando Marco
# quiere apagarlos manualmente entre sesiones de trabajo, sin esperar al
# próximo deploy para volver a levantarlos.
#
# Uso (desde la raíz del checkout, en la VM):
#   ./deploy/env-ctl.sh dev down
#   ./deploy/env-ctl.sh qa up
#   ./deploy/env-ctl.sh dev status
#
# "down" NO borra volúmenes (sin --volumes) — para en el timon, no destruye
# datos. Requiere que deploy/.env.<env> (fuera del checkout, ver
# docs/ARQUITECTURA.md ticket 049) ya exista con IMAGE_TAG=current puesto
# manualmente si se usa "up" sin pasar por el pipeline (el pipeline real
# sobreescribe IMAGE_TAG vía env var de paso, este script depende de que ya
# esté en el archivo real de la VM para un "up" manual standalone).
set -euo pipefail

ENV="${1:?uso: env-ctl.sh <dev|qa> <up|down|status>}"
ACTION="${2:?uso: env-ctl.sh <dev|qa> <up|down|status>}"

case "$ENV" in
  dev|qa) ;;
  prod) echo "❌ PROD no se para manualmente con este script — siempre activo por diseño (ver docs/ARQUITECTURA.md ticket 049)." >&2; exit 1 ;;
  *) echo "❌ ambiente desconocido: $ENV (usar 'dev' o 'qa')" >&2; exit 1 ;;
esac

COMPOSE_FILE="deploy/docker-compose.$ENV.yml"
ENV_FILE="/home/ubuntu/secrets/auth-core-mc/.env.$ENV"

case "$ACTION" in
  up)
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d
    ;;
  down)
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" down
    ;;
  status)
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps
    ;;
  *)
    echo "❌ acción desconocida: $ACTION (usar 'up', 'down' o 'status')" >&2
    exit 1
    ;;
esac
