#!/usr/bin/env bash
# alpenflight/ops/dev-up-infra.sh
#
# Brings up the alpenflight-infra compose project: the cross-project shared
# `alpenflight_shared` network + Mailpit (SMTP sink reused by Keycloak in
# alpenflight-dev AND by the legacy server in fls-e2e via host port 1025).
#
# Idempotent: re-running on a green stack is a no-op (`docker compose up -d
# --wait` is idempotent). Tears down via:
#
#   docker compose -p alpenflight-infra down [-v]
#
# `alpenflight_shared` is `external: true` in docker-compose.yml — no compose
# project owns its lifecycle. This script creates it idempotently with the
# inspect-first pattern (a `docker network create ... 2>/dev/null || true`
# idiom would swallow real daemon errors that surface hours later as silent
# SMTP DNS failures). Operators remove the network manually when retiring
# the dev stack entirely: `docker network rm alpenflight_shared`.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-infra"
NETWORK="alpenflight_shared"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

cd "${REPO_ROOT}"

# Inspect-first network create. Refuse on driver-drift so a half-finished
# attempt with a non-bridge driver fails loudly instead of silently routing
# differently.
if docker network inspect "${NETWORK}" >/dev/null 2>&1; then
    driver="$(docker network inspect -f '{{.Driver}}' "${NETWORK}" | tr -d '\r')"
    if [[ "${driver}" != "bridge" ]]; then
        echo "error: network '${NETWORK}' exists with driver '${driver}' (expected 'bridge')" >&2
        echo "       remove with: docker network rm ${NETWORK}" >&2
        exit 1
    fi
else
    log "Creating shared network ${NETWORK}"
    docker network create "${NETWORK}" --driver bridge
fi

log "Bringing up Mailpit under project ${PROJECT}"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" --profile infra \
    up -d --wait --wait-timeout 30 mailpit

cat <<INFO

\033[1;32m==> Infra ready\033[0m

  Mailpit SMTP                localhost:1025
  Mailpit Web UI              http://localhost:8025
  Shared network              ${NETWORK} (bridge)

Tear down:
  docker compose -p ${PROJECT} down       # keep volume (in-memory anyway)
  docker compose -p ${PROJECT} down -v    # wipe Mailpit inbox

Remove the shared network only when retiring the dev stack entirely:
  docker network rm ${NETWORK}
INFO
