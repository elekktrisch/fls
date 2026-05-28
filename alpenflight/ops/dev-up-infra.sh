#!/usr/bin/env bash
# alpenflight/ops/dev-up-infra.sh
#
# Brings up the alpenflight-infra compose project: the cross-project shared
# `alpenflight_shared` network + Mailpit (SMTP sink reused by Keycloak in
# alpenflight-dev AND by the legacy server in fls-e2e via host port 1025).
#
# Idempotent. Tear down:
#
#   docker compose -p alpenflight-infra down [-v]
#
# `alpenflight_shared` is `external: true` in docker-compose.yml — no compose
# project owns its lifecycle. This script creates it idempotently via the
# inspect-first helper in lib/shared-network.sh. Operators remove the network
# manually when retiring the dev stack: `docker network rm alpenflight_shared`.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-infra"

# shellcheck source=lib/shared-network.sh
source "${SCRIPT_DIR}/lib/shared-network.sh"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

cd "${REPO_ROOT}"

log "Ensuring shared network ${ALPENFLIGHT_SHARED_NETWORK}"
ensure_shared_network

log "Bringing up Mailpit under project ${PROJECT}"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" --profile infra \
    up -d --wait --wait-timeout 30 mailpit

printf '\033[1;32m==> Infra ready\033[0m\n'
cat <<INFO

  Mailpit SMTP                localhost:1025
  Mailpit Web UI              http://localhost:8025
  Shared network              ${ALPENFLIGHT_SHARED_NETWORK} (bridge)

Tear down:
  docker compose -p ${PROJECT} down       # keep volume (in-memory anyway)
  docker compose -p ${PROJECT} down -v    # wipe Mailpit inbox

Remove the shared network only when retiring the dev stack entirely:
  docker network rm ${ALPENFLIGHT_SHARED_NETWORK}
INFO
