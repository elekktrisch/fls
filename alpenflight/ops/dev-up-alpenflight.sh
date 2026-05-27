#!/usr/bin/env bash
# alpenflight/ops/dev-up-alpenflight.sh
#
# Brings up the alpenflight-dev compose project (Postgres + pgAdmin +
# Keycloak) AND runs Flyway migrations against the new Postgres. Idempotent.
#
# Tears down via:
#
#   docker compose -p alpenflight-dev down [-v]
#
# Requires:
#   - The `alpenflight_shared` external network must exist (run
#     dev-up-infra.sh first or use the dev-up-full.sh orchestrator).
#   - Mailpit must be reachable on the shared network for Keycloak's
#     verify-email send to land (not enforced at boot — Keycloak's SMTP
#     DNS only resolves at first send. Bring infra up first; `--wait`
#     here only confirms the new-stack services started).
#
# Java 25 (sdkman), Gradle wrapper (alpenflight/server/gradlew is committed).
# DATASOURCE_* env vars are consumed by the flyway block in build.gradle.kts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-dev"
NETWORK="alpenflight_shared"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

cd "${REPO_ROOT}"

# Fail fast if the shared network is missing — Keycloak attaching to a
# non-existent external network produces a confusing compose error. Direct
# the operator to dev-up-infra.sh.
if ! docker network inspect "${NETWORK}" >/dev/null 2>&1; then
    echo "error: shared network '${NETWORK}' is missing" >&2
    echo "       run: bash alpenflight/ops/dev-up-infra.sh" >&2
    exit 1
fi

# Driver-drift refuse, same as dev-up-infra.sh — keeps the two scripts
# symmetric so an operator who ran them in the wrong order sees the same
# diagnostic.
driver="$(docker network inspect -f '{{.Driver}}' "${NETWORK}" | tr -d '\r')"
if [[ "${driver}" != "bridge" ]]; then
    echo "error: network '${NETWORK}' exists with driver '${driver}' (expected 'bridge')" >&2
    echo "       remove with: docker network rm ${NETWORK}" >&2
    exit 1
fi

# Services named explicitly — `--profile next` alone would also pull mssql
# (default profile) into this project and double-bind 1433.
log "Bringing up target stack (Postgres + pgAdmin + Keycloak) under project ${PROJECT}"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" \
    up -d --wait --wait-timeout 240 postgres pgadmin keycloak

log "Applying Flyway migrations against target Postgres"
(
    cd alpenflight/server
    DATASOURCE_URL="jdbc:postgresql://localhost:5432/alpenflight" \
    DATASOURCE_USER="alpenflight" \
    DATASOURCE_PASSWORD="alpenflight" \
        ./gradlew flywayMigrate flywayInfo --no-daemon --console=plain --quiet
)

cat <<INFO

\033[1;32m==> AlpenFlight stack ready\033[0m

  Target Postgres             localhost:5432  (alpenflight / alpenflight)
  pgAdmin                     http://localhost:5050  (dev@example.com / dev)
  Keycloak admin              http://localhost:8090  (admin / admin)
  Keycloak mgmt (health)      http://localhost:9090/health/ready

Tear down:
  docker compose -p ${PROJECT} down              # keep volumes
  docker compose -p ${PROJECT} down -v           # wipe pg + keycloak H2
INFO
