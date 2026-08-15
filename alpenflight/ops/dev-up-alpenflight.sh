#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-dev"

# shellcheck source=lib/shared-network.sh
source "${SCRIPT_DIR}/lib/shared-network.sh"
# shellcheck source=lib/fail-loud.sh
source "${SCRIPT_DIR}/lib/fail-loud.sh"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

cd "${REPO_ROOT}"

require_compose_v2
require_shared_network

log "Bringing up target stack (Postgres + pgAdmin + Keycloak) under project ${PROJECT}"
compose_up_or_die "target stack (postgres + pgadmin + keycloak)" - "${PROJECT}" "${COMPOSE_FILE}" \
    up -d --wait --wait-timeout 240 postgres pgadmin keycloak

log "Applying Flyway migrations against target Postgres"
(
    cd alpenflight/server
    DATASOURCE_URL="jdbc:postgresql://localhost:5432/alpenflight" \
    DATASOURCE_USER="alpenflight" \
    DATASOURCE_PASSWORD="alpenflight" \
        ./gradlew flywayMigrate flywayInfo --no-daemon --console=plain --quiet
)

printf '\033[1;32m==> AlpenFlight stack ready\033[0m\n'
cat <<INFO

  Target Postgres             localhost:5432  (alpenflight / alpenflight)
  pgAdmin                     http://localhost:5050  (dev@example.com / dev)
  Keycloak admin              http://localhost:8090  (admin / admin)
  Keycloak mgmt (health)      http://localhost:9090/health/ready

Tear down:
  docker compose -p ${PROJECT} down              # keep volumes
  docker compose -p ${PROJECT} down -v           # wipe pg + keycloak H2
INFO
