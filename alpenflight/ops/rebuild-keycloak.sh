#!/usr/bin/env bash
# alpenflight/ops/rebuild-keycloak.sh
#
# Rebuild + restart the Keycloak container with a clean H2 volume so the
# baked realm-export AND the baked theme assets re-apply from scratch.
#
# Why all three steps:
#   - `down -v keycloak` drops the H2 volume — without this, Keycloak's
#     default IGNORE_EXISTING import strategy silently preserves the old
#     realm and the rebuild appears to have no effect.
#   - `build keycloak` re-bakes themes/ + realm-export.json into the image.
#   - `up -d keycloak --wait` blocks until /health/ready returns UP, so
#     follow-up smokes don't race against boot.
#
# Use after editing anything under alpenflight/auth/themes/, the
# realm-export.json, or the Dockerfile.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-dev"
NETWORK="alpenflight_shared"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

cd "${REPO_ROOT}"

# Keycloak attaches to the external shared network; rebuilding it on a
# clean box where infra was never brought up surfaces a confusing
# compose error. Pre-flight and direct the operator instead.
if ! docker network inspect "${NETWORK}" >/dev/null 2>&1; then
    echo "error: shared network '${NETWORK}' is missing" >&2
    echo "       run: bash alpenflight/ops/dev-up-infra.sh" >&2
    exit 1
fi

log "Stopping keycloak + dropping H2 volume"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v keycloak

log "Rebuilding keycloak image (themes + realm-export)"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" build keycloak

log "Starting keycloak"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" up -d --wait --wait-timeout 120 keycloak

cat <<INFO

\033[1;32m==> Keycloak ready\033[0m

  Admin console     http://localhost:8090  (admin / admin)
  Realm discovery   http://localhost:8090/realms/alpenflight/.well-known/openid-configuration
  Health            http://localhost:9090/health/ready

  Login preview     http://localhost:8090/realms/alpenflight/protocol/openid-connect/auth?client_id=alpenflight-web&response_type=code&scope=openid&redirect_uri=http%3A%2F%2Flocalhost%3A4200%2F&state=preview
INFO
