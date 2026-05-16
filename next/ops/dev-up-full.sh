#!/usr/bin/env bash
# next/ops/dev-up-full.sh
#
# One-shot bring-up of BOTH the legacy stack (MSSQL + Mailpit, seeded with
# FLSTest data) AND the target stack (Postgres + pgAdmin, with all Flyway
# migrations applied). Use this when you want to compare legacy vs new
# side-by-side while developing.
#
# What it does:
#   1. Brings up the legacy stack via e2e/scripts/dev-up.sh
#      (mssql + mailpit under the fls-e2e compose project)
#   2. Seeds the legacy FLSTest DB via e2e/scripts/seed.sh
#      (schema + static seed + deterministic test fixture)
#   3. Brings up the target stack (postgres + pgadmin) by activating the
#      'next' compose profile on the same fls-e2e project
#   4. Applies every Flyway migration in next/server/src/main/resources/db/
#      migration/ against the target Postgres
#   5. Prints connection details for both stacks
#
# Idempotent: re-running brings everything to the same end-state without
# tearing down. Tear down with:
#
#   bash e2e/scripts/dev-down.sh                                 # legacy only
#   docker compose -p fls-e2e --profile next down                # target only
#   docker compose -p fls-e2e --profile next down -v             # also wipe pg data
#
# Requires: Docker Engine 27+ with compose-v2 plugin, Java 25 (sdkman),
# Gradle wrapper (next/server/gradlew is committed).
#
# Windows / git-bash notes mirror e2e/scripts/dev-up.sh — MSYS auto-translates
# unix-style paths passed to docker.exe; shell scripts are LF-pinned via the
# repo's .gitattributes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="fls-e2e"

cd "${REPO_ROOT}"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# 1. Legacy stack
log "Bringing up legacy stack (MSSQL + Mailpit)"
bash e2e/scripts/dev-up.sh

# 2. Legacy seed
log "Seeding legacy FLSTest database"
bash e2e/scripts/seed.sh

# 3. Target stack (postgres + pgadmin)
log "Bringing up target stack (Postgres + pgAdmin) via 'next' profile"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" --profile next up -d postgres pgadmin

# Wait for postgres health (compose's depends_on already gates pgadmin).
log "Waiting for Postgres to report healthy..."
elapsed=0
while [[ $elapsed -lt 60 ]]; do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" ps -q postgres | tr -d '\r')" 2>/dev/null | tr -d '\r' || echo unknown)"
    if [[ "${status}" == "healthy" ]]; then
        log "    postgres: healthy"
        break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done
if [[ "${status:-unknown}" != "healthy" ]]; then
    echo "error: postgres did not become healthy within 60s" >&2
    docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" logs --tail 30 postgres >&2 || true
    exit 1
fi

# 4. Flyway migrate against the target Postgres
log "Applying Flyway migrations against target Postgres"
(
    cd next/server
    # Use the wrapper; sdkman PATH is sourced via login shell where needed.
    # DATASOURCE_* env vars are consumed by the flyway block in build.gradle.kts.
    DATASOURCE_URL="jdbc:postgresql://localhost:5432/alpenflight" \
    DATASOURCE_USER="alpenflight" \
    DATASOURCE_PASSWORD="alpenflight" \
        ./gradlew flywayMigrate flywayInfo --no-daemon --console=plain --quiet
)

cat <<INFO

\033[1;32m==> Dev stack ready\033[0m

  Legacy SQL Server          localhost:1433  (sa / Demo#FLS#2026)
  Mailpit SMTP                localhost:1025
  Mailpit Web UI              http://localhost:8025

  Target Postgres            localhost:5432  (alpenflight / alpenflight)
  pgAdmin                    http://localhost:5050  (dev@example.com / dev)

The 'AlpenFlight Target Postgres' connection appears pre-wired in pgAdmin on first
login. Schema 'public' is fully populated with the V1+V2 baseline (S-012).

Tear down:
  bash e2e/scripts/dev-down.sh                              # legacy only
  docker compose -p fls-e2e --profile next down             # target only
  docker compose -p fls-e2e --profile next down -v          # also wipe pg
INFO
