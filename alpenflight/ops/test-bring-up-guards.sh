#!/usr/bin/env bash

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FANOUT_WORKFLOW="${REPO_ROOT}/.github/workflows/alpenflight-proof-fanout.yml"
PLAYWRIGHT_CONFIG="${REPO_ROOT}/alpenflight/web/e2e/playwright.config.ts"

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() {
    printf '  FAIL %s\n' "$1" >&2
    failures=$((failures + 1))
}

make_stub_dir() {
    local mode="$1" dir
    dir="$(mktemp -d)"
    {
        printf '#!/usr/bin/env bash\nmode=%q\n' "${mode}"
        cat <<'STUB'
if [[ "${1:-}" == "compose" ]]; then
    shift
    if [[ "${mode}" == "no-compose-plugin" ]]; then
        echo "docker: 'compose' is not a docker command." >&2
        exit 1
    fi
    if [[ "${mode}" == "up-fails" ]]; then
        for arg in "$@"; do
            if [[ "${arg}" == "up" ]]; then
                echo "stub: compose up failed" >&2
                exit 1
            fi
        done
    fi
    exit 0
fi
if [[ "${1:-}" == "network" ]]; then
    driver_the_bring_up_scripts_require=bridge
    [[ "${2:-}" == "inspect" ]] && echo "${driver_the_bring_up_scripts_require}"
    exit 0
fi
exit 0
STUB
    } >"${dir}/docker"
    chmod +x "${dir}/docker"
    printf '%s' "${dir}"
}

RUN_OUT=""
RUN_STATUS=0
run_with_stub() {
    local mode="$1" script="$2" stub tmp
    stub="$(make_stub_dir "${mode}")"
    tmp="$(mktemp)"
    (cd "${REPO_ROOT}" && PATH="${stub}:${PATH}" bash "${script}") >"${tmp}" 2>&1
    RUN_STATUS=$?
    RUN_OUT="$(cat "${tmp}")"
    rm -rf "${stub}" "${tmp}"
}

assert_status_nonzero() {
    local label="$1" status="$2"
    if [[ "${status}" -eq 0 ]]; then
        fail "${label}: exited 0 over a failed bring-up"
    else
        pass "${label}: exits non-zero"
    fi
}

assert_absent() {
    local label="$1" needle="$2" haystack="$3"
    if grep -qiF -- "${needle}" <<<"${haystack}"; then
        fail "${label}: printed the success banner '${needle}' over a failed bring-up"
    else
        pass "${label}: no '${needle}' banner"
    fi
}

assert_matches() {
    local label="$1" pattern="$2" haystack="$3"
    if grep -qiE -- "${pattern}" <<<"${haystack}"; then
        pass "${label}: diagnostic matches /${pattern}/"
    else
        fail "${label}: no diagnostic matching /${pattern}/ — the failure is not named at its source"
    fi
}

echo "== dev-up scripts fail loudly =="

run_with_stub up-fails alpenflight/ops/dev-up-infra.sh
assert_status_nonzero "dev-up-infra" "${RUN_STATUS}"
assert_absent "dev-up-infra" "Infra ready" "${RUN_OUT}"
assert_matches "dev-up-infra" "error.*mailpit" "${RUN_OUT}"

run_with_stub up-fails alpenflight/ops/dev-up-alpenflight.sh
assert_status_nonzero "dev-up-alpenflight" "${RUN_STATUS}"
assert_absent "dev-up-alpenflight" "AlpenFlight stack ready" "${RUN_OUT}"
assert_matches "dev-up-alpenflight" "error.*(postgres|keycloak)" "${RUN_OUT}"

run_with_stub up-fails alpenflight/ops/dev-up-full.sh
assert_status_nonzero "dev-up-full" "${RUN_STATUS}"
assert_absent "dev-up-full" "Dev stack ready" "${RUN_OUT}"
assert_matches "dev-up-full" "error.*(mailpit|infra)" "${RUN_OUT}"

run_with_stub no-compose-plugin alpenflight/ops/dev-up-full.sh
assert_status_nonzero "dev-up-full (no compose plugin)" "${RUN_STATUS}"
assert_absent "dev-up-full (no compose plugin)" "Dev stack ready" "${RUN_OUT}"
assert_matches "dev-up-full (no compose plugin)" "docker compose.*(unavailable|plugin)" "${RUN_OUT}"

run_with_stub all-succeed alpenflight/ops/dev-up-infra.sh
if [[ "${RUN_STATUS}" -eq 0 ]]; then
    pass "dev-up-infra (healthy stack): exits 0"
else
    fail "dev-up-infra (healthy stack): exited ${RUN_STATUS} over a successful bring-up"
fi
assert_matches "dev-up-infra (healthy stack)" "Infra ready" "${RUN_OUT}"

echo "== fan-out brings Mailpit up before the legacy e2e suite =="

mailpit_line="$(grep -n 'dev-up-infra.sh' "${FANOUT_WORKFLOW}" | head -1 | cut -d: -f1)"
legacy_pw_line="$(grep -n 'working-directory: e2e$' "${FANOUT_WORKFLOW}" | head -1 | cut -d: -f1)"

if [[ -z "${mailpit_line}" || -z "${legacy_pw_line}" ]]; then
    fail "fan-out ordering: could not locate the mailpit bring-up / legacy e2e steps"
elif [[ "${mailpit_line}" -lt "${legacy_pw_line}" ]]; then
    pass "fan-out ordering: mailpit bring-up (line ${mailpit_line}) precedes legacy e2e (line ${legacy_pw_line})"
else
    fail "fan-out ordering: mailpit bring-up (line ${mailpit_line}) runs AFTER the legacy e2e steps (line ${legacy_pw_line}) — global-setup will time out before any spec runs"
fi

echo "== every site that bakes or asserts the Keycloak client baseUrl names the real-OIDC SPA port =="

port_of_default_in() {
    local file="$1" variable="$2"
    grep -oE "${variable}'?\] \?\? 'http://localhost:[0-9]{4}'" "${file}" | grep -oE '[0-9]{4}'
}

REAL_OIDC_SPA_PORT="$(port_of_default_in "${PLAYWRIGHT_CONFIG}" "E2E_REAL_IDP_BASE_URL")"
MOCK_AUTH_SPA_PORT="$(port_of_default_in "${PLAYWRIGHT_CONFIG}" "E2E_BASE_URL")"

ports_named_on_lines_matching() {
    local file="$1" selector="$2"
    grep -E -- "${selector}" "${file}" \
        | grep -oE 'localhost(:|%3A)[0-9]{4}|--port[= ][0-9]{4}' \
        | grep -oE '[0-9]{4}$'
}

assert_names_the_real_oidc_spa_port() {
    local label="$1" file="$2" selector="$3" named seen=0
    while read -r named; do
        [[ -z "${named}" ]] && continue
        seen=1
        if [[ "${named}" == "${REAL_OIDC_SPA_PORT}" ]]; then
            pass "${label}: names ${named}, the port the real-OIDC SPA serves"
        else
            fail "${label}: names port ${named}, but the SPA that follows the Keycloak login-theme back links serves on ${REAL_OIDC_SPA_PORT} — the theme would return the member to a different application"
        fi
    done < <(ports_named_on_lines_matching "${file}" "${selector}")
    if [[ "${seen}" -eq 0 ]]; then
        fail "${label}: no port matched /${selector}/ in ${file} — this guard lost its own input"
    fi
}

if [[ -z "${REAL_OIDC_SPA_PORT}" || -z "${MOCK_AUTH_SPA_PORT}" ]]; then
    fail "playwright.config.ts no longer declares both SPA base-URL defaults — this guard lost its own input"
elif [[ "${REAL_OIDC_SPA_PORT}" == "${MOCK_AUTH_SPA_PORT}" ]]; then
    fail "the real-OIDC SPA and the mock-auth SPA both claim port ${REAL_OIDC_SPA_PORT} — the two loops cannot run together"
else
    pass "playwright.config.ts separates the mock-auth SPA (${MOCK_AUTH_SPA_PORT}) from the real-OIDC SPA (${REAL_OIDC_SPA_PORT})"
fi

assert_names_the_real_oidc_spa_port "playwright real-idp dev server" \
    "${PLAYWRIGHT_CONFIG}" 'configuration=development'
assert_names_the_real_oidc_spa_port "web package.json start script" \
    "${REPO_ROOT}/alpenflight/web/package.json" '"start":'
assert_names_the_real_oidc_spa_port "keycloak Dockerfile build arg" \
    "${REPO_ROOT}/alpenflight/auth/Dockerfile" '^ARG ALPENFLIGHT_WEB_BASE_URL'
assert_names_the_real_oidc_spa_port "docker-compose keycloak build arg" \
    "${REPO_ROOT}/docker-compose.yml" 'ALPENFLIGHT_WEB_BASE_URL'
assert_names_the_real_oidc_spa_port "dev-up-alpenflight keycloak build arg" \
    "${REPO_ROOT}/alpenflight/ops/dev-up-alpenflight.sh" 'ALPENFLIGHT_WEB_BASE_URL'
assert_names_the_real_oidc_spa_port "dev-up-nocompose keycloak build hint" \
    "${REPO_ROOT}/alpenflight/ops/dev-up-nocompose.sh" 'ALPENFLIGHT_WEB_BASE_URL'
assert_names_the_real_oidc_spa_port "rebuild-keycloak login preview" \
    "${REPO_ROOT}/alpenflight/ops/rebuild-keycloak.sh" 'ALPENFLIGHT_WEB_BASE_URL'
assert_names_the_real_oidc_spa_port "check-keycloak-integration expectation" \
    "${REPO_ROOT}/alpenflight/auth/scripts/check-keycloak-integration.sh" 'E2E_REAL_IDP_BASE_URL'
assert_names_the_real_oidc_spa_port "check-theme-load redirect uri" \
    "${REPO_ROOT}/alpenflight/auth/scripts/check-theme-load.sh" 'REDIRECT_URI_URL_ENCODED='

while read -r workflow; do
    assert_names_the_real_oidc_spa_port "workflow $(basename "${workflow}")" \
        "${workflow}" '^ *(ALPENFLIGHT_WEB_BASE_URL|EXPECTED_BASE_URL):'
done < <(grep -lE '^ *(ALPENFLIGHT_WEB_BASE_URL|EXPECTED_BASE_URL):' "${REPO_ROOT}"/.github/workflows/*.yml)

if [[ "${failures}" -gt 0 ]]; then
    printf '\n%d guard(s) failed\n' "${failures}" >&2
    exit 1
fi
printf '\nall bring-up guards passed\n'
