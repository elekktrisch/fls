
die() {
    printf '\033[1;31merror [%s]:\033[0m %s\n' "$(basename "$0")" "$*" >&2
    exit 1
}

require_compose_v2() {
    command -v docker >/dev/null 2>&1 \
        || die "docker CLI not found on PATH — bring-up cannot start"
    docker compose version >/dev/null 2>&1 \
        || die "docker compose (v2 plugin) unavailable — install it (Alpine: apk add docker-cli-compose; Debian: docker-compose-plugin) and re-run"
}

compose_up_or_die() {
    local label="$1" profile="$2" project="$3" compose_file="$4"
    shift 4
    local -a base=(docker compose -p "${project}" -f "${compose_file}")
    [[ "${profile}" != "-" ]] && base+=(--profile "${profile}")

    if "${base[@]}" "$@"; then
        return 0
    fi

    printf '\033[1;31m==> BRING-UP FAILED: %s\033[0m\n' "${label}" >&2
    "${base[@]}" ps >&2 || true
    "${base[@]}" logs --tail 60 >&2 || true
    die "${label} did not come up (compose project '${project}') — container state and logs above"
}

run_step_or_die() {
    local label="$1"
    shift
    printf '\033[1;36m==>\033[0m %s\n' "${label}"
    if ! "$@"; then
        die "step failed: ${label} — the stack is NOT up; fix this step before re-running"
    fi
}
