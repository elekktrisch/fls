#!/usr/bin/env bash
# dev-down.sh - tear down the FLS e2e test stack dependencies
# ----------------------------------------------------------------------------
# Stops + removes the `fls-e2e` compose project (mssql + mailpit) and
# wipes their anonymous volumes via `down -v`.
#
# NOTE: the FLS Web API (Mono console) and the flsweb webpack-dev-server
# are NOT managed by this script - they're started manually per
# TESTING.md. If they're still running, kill them with:
#     pkill -f FLS.Server.Console.exe
#     pkill -f 'webpack-dev-server'
# ----------------------------------------------------------------------------

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
