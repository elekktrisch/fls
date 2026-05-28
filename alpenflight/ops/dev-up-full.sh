#!/usr/bin/env bash
# alpenflight/ops/dev-up-full.sh
#
# One-shot orchestrator: brings up infra (Mailpit) + legacy (MSSQL) + seeds
# the legacy DB + brings up the AlpenFlight target stack (Postgres + pgAdmin
# + Keycloak) with Flyway migrations applied. Idempotent.
#
# Composed from four single-purpose scripts; edit those, not this:
#
#   alpenflight/ops/dev-up-infra.sh         shared network + Mailpit
#   e2e/scripts/dev-up.sh                   legacy MSSQL
#   e2e/scripts/seed.sh                     legacy FLSTest seed
#   alpenflight/ops/dev-up-alpenflight.sh   Postgres + pgAdmin + Keycloak + Flyway
#
# Requires: Docker Engine 27+ with compose-v2 plugin, Java 25 (sdkman),
# the committed Gradle wrapper.
#
# Tear-down order: alpenflight-dev → fls-e2e → alpenflight-infra (target →
# legacy → infra). Reverse order leaves orphan containers attached to a
# removed-and-recreated network. See alpenflight/ops/README.md § Tear-down.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${REPO_ROOT}"

bash "${SCRIPT_DIR}/dev-up-infra.sh" \
    && bash e2e/scripts/dev-up.sh \
    && bash e2e/scripts/seed.sh \
    && bash "${SCRIPT_DIR}/dev-up-alpenflight.sh"

printf '\033[1;32m==> Dev stack ready\033[0m\n'
cat <<INFO

  Legacy SQL Server          localhost:1433  (sa / Demo#FLS#2026)
  Mailpit SMTP                localhost:1025
  Mailpit Web UI              http://localhost:8025

  Target Postgres            localhost:5432  (alpenflight / alpenflight)
  pgAdmin                    http://localhost:5050  (dev@example.com / dev)
  Keycloak admin             http://localhost:8090  (admin / admin)
  Keycloak mgmt (health)     http://localhost:9090/health/ready

Tear down (in order):
  docker compose -p alpenflight-dev down [-v]
  bash e2e/scripts/dev-down.sh
  docker compose -p alpenflight-infra down [-v]
  docker network rm alpenflight_shared   # only when retiring the dev stack
INFO
