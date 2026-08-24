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

echo "==> Starting fls-e2e stack (project=${PROJECT})"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" up -d mssql mailpit

wait_for_health() {
  local service="$1"
  local timeout="${2:-180}"
  local container
  container="$(docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" ps -q "${service}" | tr -d '\r')"
  if [[ -z "${container}" ]]; then
    echo "error: container for service '${service}' not found" >&2
    return 1
  fi

  echo "==> Waiting for ${service} (container ${container:0:12}) to become healthy..."
  local elapsed=0
  while (( elapsed < timeout )); do
    local status
    status="$(docker inspect -f '{{.State.Health.Status}}' "${container}" 2>/dev/null | tr -d '\r' || echo 'unknown')"
    case "${status}" in
      healthy)
        echo "    ${service}: healthy"
        return 0
        ;;
      unhealthy)
        echo "error: ${service} reported unhealthy" >&2
        docker logs --tail 30 "${container}" >&2 || true
        return 1
        ;;
      *)
        sleep 3
        elapsed=$((elapsed + 3))
        ;;
    esac
  done

  echo "error: ${service} did not become healthy within ${timeout}s" >&2
  docker logs --tail 30 "${container}" >&2 || true
  return 1
}

wait_for_health mssql 240
wait_for_health mailpit 60

cat <<'INFO'

==> fls-e2e stack is up

  SQL Server (sa / Demo#FLS#2026)   localhost:1433
  Mailpit (SMTP 1025)               http://localhost:8025

Next steps:
  1. Apply schema + seed to the FLSTest DB:
       bash e2e/scripts/seed.sh
     (defaults to the fls-e2e-mssql-1 container started above; see
      TESTING.md Milestone 1 for the manual walk-through.)
  2. Start the FLS Web API - see TESTING.md Milestone 3:
       cd flsserver/src/FLS.Server.Console/bin/Debug
       FLS_LISTEN_URL="http://*:25567/" mono FLS.Server.Console.exe
  3. Start the flsweb dev server - see TESTING.md Milestone 5:
       cd /tmp/flsweb-build && yarn start
  4. Run the suite:
       cd e2e && npx playwright test
  5. Tear down with: bash e2e/scripts/dev-down.sh
INFO
