#!/usr/bin/env bash
# alpenflight/ops/lint-compose.sh
#
# Static checks on docker-compose.yml. Run by .github/workflows/compose-lint.yml
# and runnable locally:
#
#   bash alpenflight/ops/lint-compose.sh
#
# Rules:
#   1. Every service has a `healthcheck.test`.
#   2. No `:latest` image tags on new-stack services (postgres, pgadmin, keycloak).
#      Legacy services (mssql, mailpit) are exempt — they predate ADR 0010.
#   3. New-stack data ports bind to 127.0.0.1 (no LAN exposure).
#
# Exits non-zero on the first violation with a pointer to the offending service.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"

# Services that follow ADR 0010 hygiene. Add to this list as new-stack services land.
NEW_STACK_SERVICES=(postgres pgadmin keycloak)

red() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$*"; }

fail=0

# Resolved config (with all profiles enabled so every service appears).
config_json="$(docker compose -f "${COMPOSE_FILE}" \
    --profile next \
    config --format json)"

# Rule 1 — every service has healthcheck.test.
while read -r svc; do
    has_hc="$(jq -r --arg s "$svc" '.services[$s].healthcheck.test // empty' <<<"${config_json}")"
    if [[ -z "${has_hc}" ]]; then
        red "rule_1 FAIL: service '${svc}' has no healthcheck.test"
        fail=1
    fi
done < <(jq -r '.services | keys[]' <<<"${config_json}")

# Rule 2 — no :latest on new-stack services.
for svc in "${NEW_STACK_SERVICES[@]}"; do
    image="$(jq -r --arg s "$svc" '.services[$s].image // empty' <<<"${config_json}")"
    if [[ -z "${image}" ]]; then
        # Service not in the file yet (e.g. before keycloak lands). Not a lint
        # failure here — the smoke job catches absence.
        continue
    fi
    if [[ "${image}" == *:latest ]] || [[ "${image}" != *:* ]]; then
        red "rule_2 FAIL: service '${svc}' uses floating tag '${image}' (must pin a version)"
        fail=1
    fi
done

# Rule 3 — new-stack services bind host ports to 127.0.0.1 only.
for svc in "${NEW_STACK_SERVICES[@]}"; do
    while read -r host_ip; do
        if [[ -n "${host_ip}" && "${host_ip}" != "127.0.0.1" ]]; then
            red "rule_3 FAIL: service '${svc}' binds to host '${host_ip}' (must be 127.0.0.1)"
            fail=1
        fi
    done < <(jq -r --arg s "$svc" '.services[$s].ports[]?.host_ip // ""' <<<"${config_json}")
done

if [[ ${fail} -ne 0 ]]; then
    exit 1
fi

green "compose-lint OK"
