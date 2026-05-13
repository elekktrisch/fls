#!/usr/bin/env bash
# dev-up.sh - bring up the FLS e2e test stack dependencies
# ----------------------------------------------------------------------------
# Starts SQL Server (FLSTest database host) and Mailpit (SMTP sink) under
# the `fls-e2e` compose project so this stack can run alongside any
# manually-started containers (e.g. an existing `fls-mssql` for dev work).
#
# Assumed environment (see TESTING.md for the full playbook):
#
#   - Linux x86_64 with Docker Engine 27+ and the compose-v2 plugin
#     (`docker compose`, NOT the old `docker-compose` binary).
#   - Mono 6.12 (`mono-complete`) installed if you also want to start the
#     FLS Web API locally. Build artifacts expected at
#     flsserver/src/FLS.Server.Console/bin/Debug/FLS.Server.Console.exe
#     with EntityFramework.SqlServer.dll dropped next to them.
#   - The NuGet CLI under Mono at /usr/local/bin/nuget.exe (only needed
#     for a from-scratch server build).
#   - Node 8 available via nvm, only needed for the flsweb webpack-1
#     bundle / dev-server build (see TESTING.md Milestone 5).
#
# This script ONLY brings up the database + email sink. The FLS Web API
# and webpack-dev-server are still started manually per TESTING.md
# Milestones 3 and 5 - the Playwright config's webServer block waits
# for both to be reachable on their default ports before tests run.
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

echo "==> Starting fls-e2e stack (project=${PROJECT})"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" up -d

wait_for_health() {
  local service="$1"
  local timeout="${2:-180}"
  local container
  container="$(docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" ps -q "${service}")"
  if [[ -z "${container}" ]]; then
    echo "error: container for service '${service}' not found" >&2
    return 1
  fi

  echo "==> Waiting for ${service} (container ${container:0:12}) to become healthy..."
  local elapsed=0
  while (( elapsed < timeout )); do
    local status
    status="$(docker inspect -f '{{.State.Health.Status}}' "${container}" 2>/dev/null || echo 'unknown')"
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
  Mailpit SMTP                       localhost:1025
  Mailpit HTTP API + Web UI          http://localhost:8025

Next steps:
  1. Apply schema + seed to the FLSTest DB - see TESTING.md Milestone 1.
  2. Start the FLS Web API - see TESTING.md Milestone 3:
       cd flsserver/src/FLS.Server.Console/bin/Debug
       FLS_LISTEN_URL="http://*:25567/" mono FLS.Server.Console.exe
  3. Start the flsweb dev server - see TESTING.md Milestone 5:
       cd /tmp/flsweb-build && yarn start
  4. Run the suite:
       cd e2e && npx playwright test
  5. Tear down with: bash e2e/scripts/dev-down.sh
INFO
