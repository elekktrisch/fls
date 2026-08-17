#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-dev"

ALPENFLIGHT_WEB_BASE_URL="${ALPENFLIGHT_WEB_BASE_URL:-http://localhost:4201/}"
export ALPENFLIGHT_WEB_BASE_URL
LOGIN_PREVIEW_REDIRECT_URI_URL_ENCODED="$(printf '%s' "${ALPENFLIGHT_WEB_BASE_URL}" | sed 's|:|%3A|g; s|/|%2F|g')"

# shellcheck source=lib/shared-network.sh
source "${SCRIPT_DIR}/lib/shared-network.sh"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

cd "${REPO_ROOT}"

require_shared_network

log "Stopping keycloak + dropping H2 volume"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v keycloak

log "Rebuilding keycloak image (themes + realm-export)"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" build keycloak

log "Starting keycloak"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" up -d --wait --wait-timeout 120 keycloak

printf '\033[1;32m==> Keycloak ready\033[0m\n'
cat <<INFO

  Admin console     http://localhost:8090  (admin / admin)
  Realm discovery   http://localhost:8090/realms/alpenflight/.well-known/openid-configuration
  Health            http://localhost:9090/health/ready

  Login preview     http://localhost:8090/realms/alpenflight/protocol/openid-connect/auth?client_id=alpenflight-web&response_type=code&scope=openid&redirect_uri=${LOGIN_PREVIEW_REDIRECT_URI_URL_ENCODED}&state=preview
  Theme back links  ${ALPENFLIGHT_WEB_BASE_URL}  (baked into the image by this rebuild)
INFO
