#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"

NEW_STACK_SERVICES=(postgres pgadmin keycloak mailpit)

red() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$*"; }

fail=0

config_json="$(docker compose -f "${COMPOSE_FILE}" \
    --profile next --profile infra \
    config --format json)"

while read -r svc; do
    has_hc="$(jq -r --arg s "$svc" '.services[$s].healthcheck.test // empty' <<<"${config_json}")"
    if [[ -z "${has_hc}" ]]; then
        red "rule_1 FAIL: service '${svc}' has no healthcheck.test"
        fail=1
    fi
done < <(jq -r '.services | keys[]' <<<"${config_json}")

for svc in "${NEW_STACK_SERVICES[@]}"; do
    image="$(jq -r --arg s "$svc" '.services[$s].image // empty' <<<"${config_json}")"
    if [[ -z "${image}" ]]; then
        continue
    fi
    if [[ "${image}" == *:latest ]] || [[ "${image}" != *:* ]]; then
        red "rule_2 FAIL: service '${svc}' uses floating tag '${image}' (must pin a version)"
        fail=1
    fi
done

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
