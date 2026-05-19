#!/usr/bin/env bash
# alpenflight/ops/dev-up-full.sh
#
# One-shot bring-up of BOTH the legacy stack (MSSQL + Mailpit, seeded with
# FLSTest data) AND the target stack (Postgres + pgAdmin + Keycloak, with
# all Flyway migrations applied). Use this when you want to compare legacy
# vs new side-by-side while developing.
#
# What it does:
#   1. Brings up the legacy stack via e2e/scripts/dev-up.sh
#      (mssql + mailpit under the `fls-e2e` compose project — the legacy
#      stack keeps its historical project name since `fls-` is the brand
#      of the system being modernized AWAY from).
#   2. Seeds the legacy FLSTest DB via e2e/scripts/seed.sh
#      (schema + static seed + deterministic test fixture)
#   3. Brings up the target stack (postgres + pgadmin + keycloak) by
#      activating the 'next' compose profile under the `alpenflight-dev`
#      compose project — separate from `fls-e2e` so the two stacks teardown
#      independently and don't share project-scoped resources.
#   4. Applies every Flyway migration in alpenflight/server/src/main/resources/db/
#      migration/ against the target Postgres
#   5. Prints connection details for both stacks
#
# Idempotent: re-running brings everything to the same end-state without
# tearing down. Tear down with:
#
#   bash e2e/scripts/dev-down.sh                            # legacy only
#   docker compose -p alpenflight-dev down                  # target only (keep volumes)
#   docker compose -p alpenflight-dev down -v               # target only + wipe pg data
#
# Requires: Docker Engine 27+ with compose-v2 plugin, Java 25 (sdkman),
# Gradle wrapper (alpenflight/server/gradlew is committed).
#
# Windows / git-bash notes mirror e2e/scripts/dev-up.sh — MSYS auto-translates
# unix-style paths passed to docker.exe; shell scripts are LF-pinned via the
# repo's .gitattributes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROJECT="alpenflight-dev"

cd "${REPO_ROOT}"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# 1. Legacy stack
log "Bringing up legacy stack (MSSQL + Mailpit)"
bash e2e/scripts/dev-up.sh

# 2. Legacy seed
log "Seeding legacy FLSTest database"
bash e2e/scripts/seed.sh

# 3. Target stack (postgres + pgadmin + keycloak)
# Services named explicitly rather than `--profile next` — the new stack
# runs under its own project (`alpenflight-dev`), and `--profile next`
# would also pull in the default-profile services (mssql, mailpit) under
# this project name, double-binding ports 1433/1025/8025.
log "Bringing up target stack (Postgres + pgAdmin + Keycloak) under project ${PROJECT}"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" up -d --wait --wait-timeout 240 \
    postgres pgadmin keycloak

# 4. Flyway migrate against the target Postgres
log "Applying Flyway migrations against target Postgres"
(
    cd alpenflight/server
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
  Keycloak admin             http://localhost:8090  (admin / admin)
  Keycloak mgmt (health)     http://localhost:9090/health/ready

The 'AlpenFlight Target Postgres' connection appears pre-wired in pgAdmin on first
login. Schema 'public' is fully populated with the V1+V2 baseline (S-012).

Keycloak has no realm yet — S-019 ships the AlpenFlight realm export. Create
one by hand via the admin console for now if you need it.

Tear down:
  bash e2e/scripts/dev-down.sh                            # legacy only
  docker compose -p alpenflight-dev down                  # target only (keep volumes)
  docker compose -p alpenflight-dev down -v               # target only + wipe pg
INFO
