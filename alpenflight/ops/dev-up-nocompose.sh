#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# shellcheck source=lib/shared-network.sh
source "${SCRIPT_DIR}/lib/shared-network.sh"
# shellcheck source=lib/fail-loud.sh
source "${SCRIPT_DIR}/lib/fail-loud.sh"

KEYCLOAK_IMAGE="alpenflight-keycloak:local"
MAILPIT_IMAGE="axllent/mailpit:v1.21"
KC_READY_URL="http://localhost:9090/health/ready"
MAILPIT_READY_URL="http://localhost:8025/api/v1/info"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

command -v docker >/dev/null 2>&1 || die "docker CLI not found on PATH"
docker info >/dev/null 2>&1 || die "docker daemon unreachable"

[[ -n "${DATASOURCE_URL:-}" ]] \
    || die "DATASOURCE_URL unset — source ~/.bashrc for the LAN Postgres coordinates (never start a local Postgres container)"

docker image inspect "${KEYCLOAK_IMAGE}" >/dev/null 2>&1 \
    || die "${KEYCLOAK_IMAGE} missing — build it: docker build -t ${KEYCLOAK_IMAGE} --build-arg ALPENFLIGHT_WEB_BASE_URL=http://localhost:4201/ ${REPO_ROOT}/alpenflight/auth"

wait_ready() {
    local label="$1" url="$2" needle="$3" container="$4" attempts="$5"
    for ((i = 0; i < attempts; i++)); do
        if curl -sf "${url}" 2>/dev/null | grep -q "${needle}"; then
            log "${label} ready"
            return 0
        fi
        sleep 5
    done
    docker logs --tail 60 "${container}" >&2 || true
    die "${label} did not become ready at ${url} — logs above"
}

recreate() {
    docker rm -f "$1" >/dev/null 2>&1 || true
}

cd "${REPO_ROOT}"

log "Ensuring shared network ${ALPENFLIGHT_SHARED_NETWORK}"
ensure_shared_network

log "Starting Mailpit"
recreate mailpit
docker run -d --name mailpit \
    --network "${ALPENFLIGHT_SHARED_NETWORK}" --network-alias mailpit \
    --restart unless-stopped \
    -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 \
    -e MP_MAX_MESSAGES=5000 \
    -e MP_SMTP_AUTH_ACCEPT_ANY=1 \
    -e MP_SMTP_AUTH_ALLOW_INSECURE=1 \
    "${MAILPIT_IMAGE}" >/dev/null
wait_ready "Mailpit" "${MAILPIT_READY_URL}" '"Version"' mailpit 12

KC_ENV_ARGS=(--env-file "${REPO_ROOT}/alpenflight/auth/.env.example")
[[ -f "${REPO_ROOT}/alpenflight/auth/.env" ]] \
    && KC_ENV_ARGS+=(--env-file "${REPO_ROOT}/alpenflight/auth/.env")

log "Starting Keycloak"
recreate keycloak
docker run -d --name keycloak \
    --network "${ALPENFLIGHT_SHARED_NETWORK}" --network-alias keycloak \
    --restart unless-stopped \
    -p 127.0.0.1:8090:8080 -p 127.0.0.1:9090:9000 \
    "${KC_ENV_ARGS[@]}" \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
    -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    -e KC_HOSTNAME=localhost \
    -e KC_HOSTNAME_URL=http://localhost:8090 \
    -e KC_HTTP_ENABLED=true \
    -e KC_HEALTH_ENABLED=true \
    -e KC_LOG_LEVEL=INFO \
    "${KEYCLOAK_IMAGE}" start-dev --http-port=8080 --import-realm >/dev/null
wait_ready "Keycloak" "${KC_READY_URL}" '"status": "UP"' keycloak 90

log "Applying Flyway migrations against ${DATASOURCE_URL}"
(cd alpenflight/server && ./gradlew flywayMigrate --no-daemon --console=plain --quiet)

printf '\033[1;32m==> Stack ready (compose-free)\033[0m\n'
cat <<INFO

  Keycloak admin              http://localhost:8090  (admin / admin)
  Keycloak mgmt (health)      ${KC_READY_URL}
  Mailpit SMTP                localhost:1025
  Mailpit Web UI              http://localhost:8025
  Postgres                    ${DATASOURCE_URL} (external — not managed here)

Next, in order (never Gradle and Playwright at once on a 2-core box):

  cd alpenflight/server && ./gradlew bootJar --no-daemon && ./gradlew --stop
  cd alpenflight/server && SPRING_PROFILES_ACTIVE=dev \\
      ALPENFLIGHT_KC_ADMIN_BASE_URL=http://localhost:8090 \\
      ALPENFLIGHT_OIDC_ISSUER_URI=http://localhost:8090/realms/alpenflight \\
      nohup java -jar build/libs/alpenflight-server-*-SNAPSHOT.jar > backend.log 2>&1 &
  cd alpenflight/web && pnpm e2e:real-idp --workers=1
INFO
