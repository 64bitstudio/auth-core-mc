#!/usr/bin/env bash
# Ticket 049: limpieza de imágenes/recursos Docker tras cada deploy, en la
# VM (capa gratuita — maximizar aprovechamiento de disco). Ver
# docs/ARQUITECTURA.md ticket 049 para el porqué de cada retención.
#
# Uso:
#   ./cleanup.sh dev     # conserva solo 1 imagen de release (la actual)
#   ./cleanup.sh qa      # conserva solo 1 imagen de release (la actual)
#   ./cleanup.sh prod    # conserva 2 (la actual + la anterior, rollback)
#
# Deliberadamente NO usa "docker system prune -af" (que borraría CUALQUIER
# imagen no usada por un contenedor corriendo, incluida la anterior de PROD
# que sí queremos conservar para rollback) — en su lugar, borra por nombre
# de repositorio ("auth-core-mc-dev"/"auth-core-mc-qa"/"auth-core-mc-prod")
# más allá del límite de retención, y solo entonces corre los prune
# genéricos (dangling, build cache, contenedores detenidos) que son siempre
# seguros de limpiar.
set -euo pipefail

ENV="${1:?uso: cleanup.sh <dev|qa|prod>}"
case "$ENV" in
  dev) KEEP=1 ;;
  qa) KEEP=1 ;;
  prod) KEEP=2 ;;
  *) echo "❌ ambiente desconocido: $ENV (usar 'dev', 'qa' o 'prod')" >&2; exit 1 ;;
esac

REPO="auth-core-mc-$ENV"

echo "== Retención de imágenes de release para $REPO (conservar $KEEP) =="

# IDs de imágenes de este repositorio, más nuevas primero (por fecha de
# creación real de la imagen, no por el orden en que Docker las lista).
# `while read` en vez de `mapfile` (bash4+) a propósito: el bash 3.2 que
# trae macOS de fábrica (sin `mapfile`) también debe poder correr/probar
# este script sin depender de qué bash termine ejecutándolo.
IMAGE_IDS=()
while IFS= read -r id; do
  IMAGE_IDS+=("$id")
done < <(
  docker images "$REPO" --format '{{.CreatedAt}}|{{.ID}}' \
    | sort -r \
    | awk -F'|' '{print $2}' \
    | awk '!seen[$0]++'
)

TOTAL=${#IMAGE_IDS[@]}
echo "Imágenes encontradas para $REPO: $TOTAL"

if [ "$TOTAL" -gt "$KEEP" ]; then
  TO_REMOVE=("${IMAGE_IDS[@]:$KEEP}")
  echo "Borrando ${#TO_REMOVE[@]} imagen(es) más allá de la retención: ${TO_REMOVE[*]}"
  docker rmi -f "${TO_REMOVE[@]}"
else
  echo "Nada que borrar (dentro del límite de retención)."
fi

echo "== Limpieza general (dangling, build cache, contenedores detenidos) =="
docker image prune -f
docker builder prune -f
docker container prune -f

echo "== Estado de disco tras la limpieza =="
docker system df
