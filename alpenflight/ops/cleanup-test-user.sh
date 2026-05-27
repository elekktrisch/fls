#!/bin/sh
# alpenflight/ops/cleanup-test-user.sh
#
# Wipe a single test user from BOTH Keycloak (alpenflight realm) AND the
# alpenflight Postgres DB so a signup flow can be replayed against the
# same email without nuking the whole stack (`rebuild-keycloak.sh` +
# `compose down -v`).
#
# Useful for:
#   - re-running /signup end-to-end multiple times during dev.
#   - clearing a botched first-broker-login before retrying the Google IdP flow.
#
# Usage:
#   bash alpenflight/ops/cleanup-test-user.sh you@example.com
#
# Env knobs (with sensible localhost defaults):
#   KEYCLOAK_URL    http://localhost:8090
#   REALM           alpenflight
#   KC_ADMIN_USER   admin
#   KC_ADMIN_PASS   admin
#   PG_CONTAINER    alpenflight-dev-postgres-1
#   PG_DB           alpenflight
#   PG_USER         alpenflight
#
# Refuses to run against non-localhost Keycloak (the script triggers
# destructive DELETE operations; we don't want it pointed at prod by
# accident).

set -eu

EMAIL="${1:-}"
[ -n "$EMAIL" ] || { echo "usage: $0 <email>"; exit 2; }

# Argument validation — the email lands in both an admin REST query string
# (curl --data-urlencode handles that safely) AND a psql parameter; reject
# obviously-malformed input up front. RFC-5321 permits quoted-local-parts
# like `"o'brien"@x.com`, but those are vanishingly rare in real signups
# and the cleanup workflow doesn't need to handle them.
case "$EMAIL" in
  *[!A-Za-z0-9._%+@-]*|*@*@*|*'@'|'@'*) echo "invalid email '$EMAIL'"; exit 2 ;;
  *@*.*) ;;
  *) echo "invalid email '$EMAIL' (no domain)"; exit 2 ;;
esac

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
REALM="${REALM:-alpenflight}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASS="${KC_ADMIN_PASS:-admin}"

PG_CONTAINER="${PG_CONTAINER:-alpenflight-dev-postgres-1}"
PG_DB="${PG_DB:-alpenflight}"
PG_USER="${PG_USER:-alpenflight}"

# Safety gate — destructive operations only against a local dev stack.
case "$KEYCLOAK_URL" in
  http://localhost:*|http://127.0.0.1:*|http://keycloak:*) ;;
  *) echo "refusing to run against non-localhost KEYCLOAK_URL=${KEYCLOAK_URL}"; exit 1 ;;
esac

command -v jq >/dev/null 2>&1 || { echo "jq is required (apt-get install -y jq)"; exit 1; }

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 1. Keycloak — find the realm user by email + delete.
# ---------------------------------------------------------------------------

TOKEN=$(curl -fsS -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli' \
  -d "username=${ADMIN_USER}" \
  -d "password=${ADMIN_PASS}" \
  | jq -r '.access_token')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "failed to acquire admin token from ${KEYCLOAK_URL}"; exit 1; }

USERS=$(curl -fsS -G "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "email=${EMAIL}" \
  --data-urlencode "exact=true")
USER_IDS=$(echo "$USERS" | jq -r '.[].id')

if [ -z "$USER_IDS" ]; then
  log "no Keycloak user found in realm=${REALM} with email=${EMAIL}"
else
  for id in $USER_IDS; do
    log "deleting Keycloak user ${id} (email=${EMAIL})"
    curl -fsS -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${id}" \
      -H "Authorization: Bearer ${TOKEN}"
  done
fi

# ---------------------------------------------------------------------------
# 2. Postgres — drop the JIT-projected t_user row (S-169) by notification_email.
#    Federated-identity links inside Keycloak go away with the user-delete
#    above; the SPA-side mock-auth bearer cache (sessionStorage) is the
#    browser's problem (devtools → Application → Storage → Clear).
# ---------------------------------------------------------------------------

if docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
  log "deleting t_user rows in postgres where notification_email = ${EMAIL}"
  # psql's `--set=email=...` defines a server-side variable; `:'email'`
  # expands as a properly-quoted SQL string literal. Avoids the shell
  # interpolation that would break on a `'` in the local-part and gives
  # us SQL-injection-safe parameter binding without `EXECUTE … USING`.
  ROWS=$(docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB}" -At \
    --set=email="${EMAIL}" \
    -c "DELETE FROM t_user WHERE notification_email = :'email' RETURNING id;")
  if [ -n "$ROWS" ]; then
    log "deleted t_user rows: ${ROWS}"
  else
    log "no t_user rows matched (the JIT projection fires on first authenticated /me — a verify-email signup that never completed login leaves no row)"
  fi
else
  log "postgres container '${PG_CONTAINER}' not running — skipping DB cleanup (compose project default is 'alpenflight-dev'; override PG_CONTAINER if you used a different -p)"
fi

log "done — sign up again with ${EMAIL} for a fresh end-to-end run"
