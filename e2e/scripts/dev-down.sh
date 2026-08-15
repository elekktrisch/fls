#!/usr/bin/env bash

set -euo pipefail

PROJECT="fls-e2e"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "error: docker-compose.yml not found at ${COMPOSE_FILE}" >&2
  exit 1
fi

echo "==> Tearing down fls-e2e stack (project=${PROJECT})"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v

echo "==> Done."
