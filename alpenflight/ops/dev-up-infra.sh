#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-infra"

# shellcheck source=lib/shared-network.sh
source "${SCRIPT_DIR}/lib/shared-network.sh"
# shellcheck source=lib/fail-loud.sh
source "${SCRIPT_DIR}/lib/fail-loud.sh"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

cd "${REPO_ROOT}"

require_compose_v2

log "Ensuring shared network ${ALPENFLIGHT_SHARED_NETWORK}"
ensure_shared_network

log "Bringing up Mailpit under project ${PROJECT}"
compose_up_or_die "Mailpit" infra "${PROJECT}" "${COMPOSE_FILE}" \
    up -d --wait --wait-timeout 90 mailpit

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
