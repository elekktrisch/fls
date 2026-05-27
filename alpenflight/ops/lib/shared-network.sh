# alpenflight/ops/lib/shared-network.sh
#
# Sourced helpers for the external `alpenflight_shared` bridge network
# that connects the three compose projects (fls-e2e, alpenflight-infra,
# alpenflight-dev). Source from dev-up-*.sh, rebuild-keycloak.sh, and
# e2e/scripts/dev-up.sh so the inspect-first / driver-drift / fail-fast
# semantics stay in one place.
#
# Usage:
#
#   source "${REPO_ROOT}/alpenflight/ops/lib/shared-network.sh"
#   ensure_shared_network        # create-on-miss; driver-drift refuse
#   require_shared_network       # fail-fast on miss; driver-drift refuse

ALPENFLIGHT_SHARED_NETWORK="alpenflight_shared"

# Refuse with an actionable message if the network exists but with the
# wrong driver. A half-finished prior attempt that left e.g. an overlay
# network behind would route differently or fail confusingly.
_assert_bridge_driver() {
    local driver
    driver="$(docker network inspect -f '{{.Driver}}' "${ALPENFLIGHT_SHARED_NETWORK}" | tr -d '\r')"
    if [[ "${driver}" != "bridge" ]]; then
        echo "error: network '${ALPENFLIGHT_SHARED_NETWORK}' exists with driver '${driver}' (expected 'bridge')" >&2
        echo "       remove with: docker network rm ${ALPENFLIGHT_SHARED_NETWORK}" >&2
        echo "       then re-run this script" >&2
        return 1
    fi
}

# Idempotent create. Inspect-first beats `docker network create ... ||
# true` — the latter swallows real daemon errors (rootless permission,
# daemon down) that surface hours later as silent SMTP DNS failures.
ensure_shared_network() {
    if docker network inspect "${ALPENFLIGHT_SHARED_NETWORK}" >/dev/null 2>&1; then
        _assert_bridge_driver
    else
        docker network create "${ALPENFLIGHT_SHARED_NETWORK}" --driver bridge >/dev/null
    fi
}

# Fail-fast variant: assumes the operator brought infra up first. Used by
# dev-up-alpenflight.sh, rebuild-keycloak.sh, and e2e/scripts/dev-up.sh,
# all of which should NOT silently create the network.
require_shared_network() {
    if ! docker network inspect "${ALPENFLIGHT_SHARED_NETWORK}" >/dev/null 2>&1; then
        echo "error: shared network '${ALPENFLIGHT_SHARED_NETWORK}' is missing" >&2
        echo "       run: bash alpenflight/ops/dev-up-infra.sh" >&2
        return 1
    fi
    _assert_bridge_driver
}
