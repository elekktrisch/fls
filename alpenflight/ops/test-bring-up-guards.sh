#!/usr/bin/env bash
# Guards on the dev/CI bring-up scripts. Needs no Docker daemon: `docker` is
# stubbed on PATH.
#
# Both guards exist because a bring-up that reports success over a dead stack
# converts a missing service into a readiness-poll timeout minutes later, in a
# different repo layer, with no mention of the service that never started.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FANOUT_WORKFLOW="${REPO_ROOT}/.github/workflows/alpenflight-proof-fanout.yml"

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() {
    printf '  FAIL %s\n' "$1" >&2
    failures=$((failures + 1))
}

# mode=up-fails: every probe the scripts make succeeds, only `compose ... up`
# fails. mode=no-compose-plugin: `docker compose` is not a docker command.
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
    # `network inspect -f '{{.Driver}}'` must answer bridge or the scripts abort
    # on driver drift instead of on the failure under test.
    [[ "${2:-}" == "inspect" ]] && echo bridge
    exit 0
fi
exit 0
STUB
    } >"${dir}/docker"
    chmod +x "${dir}/docker"
    printf '%s' "${dir}"
}

# Runs a bring-up script against a stubbed docker, into RUN_OUT / RUN_STATUS.
# Not a command substitution: that would strand both in a subshell.
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

# The success path must survive the fail-loud wrappers.
run_with_stub all-succeed alpenflight/ops/dev-up-infra.sh
if [[ "${RUN_STATUS}" -eq 0 ]]; then
    pass "dev-up-infra (healthy stack): exits 0"
else
    fail "dev-up-infra (healthy stack): exited ${RUN_STATUS} over a successful bring-up"
fi
assert_matches "dev-up-infra (healthy stack)" "Infra ready" "${RUN_OUT}"

echo "== fan-out brings Mailpit up before the legacy e2e suite =="

# e2e/global-setup.ts gates the WHOLE legacy suite on Mailpit, so a Mailpit
# bring-up scheduled after the legacy specs times the suite out before any spec
# runs.
mailpit_line="$(grep -n 'dev-up-infra.sh' "${FANOUT_WORKFLOW}" | head -1 | cut -d: -f1)"
legacy_pw_line="$(grep -n 'working-directory: e2e$' "${FANOUT_WORKFLOW}" | head -1 | cut -d: -f1)"

if [[ -z "${mailpit_line}" || -z "${legacy_pw_line}" ]]; then
    fail "fan-out ordering: could not locate the mailpit bring-up / legacy e2e steps"
elif [[ "${mailpit_line}" -lt "${legacy_pw_line}" ]]; then
    pass "fan-out ordering: mailpit bring-up (line ${mailpit_line}) precedes legacy e2e (line ${legacy_pw_line})"
else
    fail "fan-out ordering: mailpit bring-up (line ${mailpit_line}) runs AFTER the legacy e2e steps (line ${legacy_pw_line}) — global-setup will time out before any spec runs"
fi

if [[ "${failures}" -gt 0 ]]; then
    printf '\n%d guard(s) failed\n' "${failures}" >&2
    exit 1
fi
printf '\nall bring-up guards passed\n'
