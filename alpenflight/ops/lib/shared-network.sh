
ALPENFLIGHT_SHARED_NETWORK="alpenflight_shared"

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

ensure_shared_network() {
    if docker network inspect "${ALPENFLIGHT_SHARED_NETWORK}" >/dev/null 2>&1; then
        _assert_bridge_driver
    else
        docker network create "${ALPENFLIGHT_SHARED_NETWORK}" --driver bridge >/dev/null
    fi
}

require_shared_network() {
    if ! docker network inspect "${ALPENFLIGHT_SHARED_NETWORK}" >/dev/null 2>&1; then
        echo "error: shared network '${ALPENFLIGHT_SHARED_NETWORK}' is missing" >&2
        echo "       run: bash alpenflight/ops/dev-up-infra.sh" >&2
        return 1
    fi
    _assert_bridge_driver
}
