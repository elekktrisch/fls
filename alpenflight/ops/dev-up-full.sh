#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# shellcheck source=lib/fail-loud.sh
source "${SCRIPT_DIR}/lib/fail-loud.sh"

cd "${REPO_ROOT}"

require_compose_v2

run_step_or_die "infra: shared network + Mailpit" bash "${SCRIPT_DIR}/dev-up-infra.sh"
run_step_or_die "legacy MSSQL (fls-e2e)" bash e2e/scripts/dev-up.sh
run_step_or_die "legacy FLSTest seed" bash e2e/scripts/seed.sh
run_step_or_die "AlpenFlight stack: Postgres + pgAdmin + Keycloak + Flyway" \
    bash "${SCRIPT_DIR}/dev-up-alpenflight.sh"

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
