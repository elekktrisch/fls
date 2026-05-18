#!/usr/bin/env bash
# next/auth/scripts/export-realm.sh
#
# Re-export the alpenflight realm from a running Keycloak and write it to
# next/auth/realm-export.json. After running, `git diff` should be empty if
# nothing changed in the realm; otherwise the diff is the intended drift.
#
# Requires: Keycloak up under project `alpenflight-dev` (e.g. via
#   next/ops/dev-up-full.sh, or `docker compose -p alpenflight-dev up -d --wait keycloak`).
#
# Uses the REST partial-export + users API rather than `kc.sh export`
# because the offline export path locks the H2 DB the running container
# already holds; REST is the online path that works against a live server.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TARGET="${REPO_ROOT}/next/auth/realm-export.json"
KC_HOST="${KC_HOST:-http://localhost:8090}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASS="${KC_ADMIN_PASS:-admin}"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

log "Acquiring admin token"
TOKEN=$(curl -sS -X POST "${KC_HOST}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=password&client_id=admin-cli&username=${ADMIN_USER}&password=${ADMIN_PASS}" \
  | jq -r .access_token)
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || { echo "ERROR: admin token fetch failed"; exit 1; }

KC_ADMIN="${KC_HOST}/admin/realms/alpenflight"
WORK="$(mktemp -d)"
trap "rm -rf $WORK" EXIT

log "Fetching realm + clients + roles (partial-export)"
curl -sS -X POST "${KC_ADMIN}/partial-export?exportClients=true&exportGroupsAndRoles=true" \
  -H "Authorization: Bearer $TOKEN" > "${WORK}/realm-partial.json"

log "Fetching users"
curl -sS "${KC_ADMIN}/users?briefRepresentation=false&max=1000" \
  -H "Authorization: Bearer $TOKEN" > "${WORK}/users.json"

log "Fetching per-user role mappings"
echo '{}' > "${WORK}/user-roles.json"
for u in $(jq -r '.[].username' "${WORK}/users.json"); do
  USER_ID=$(curl -sS "${KC_ADMIN}/users?username=${u}&exact=true" -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')
  ROLES=$(curl -sS "${KC_ADMIN}/users/${USER_ID}/role-mappings/realm" -H "Authorization: Bearer $TOKEN" | jq -r '[.[].name] | sort')
  jq --arg u "$u" --argjson r "$ROLES" '. + {($u): $r}' "${WORK}/user-roles.json" > "${WORK}/user-roles.tmp"
  mv "${WORK}/user-roles.tmp" "${WORK}/user-roles.json"
done

log "Normalizing + writing ${TARGET}"
"$(dirname "${BASH_SOURCE[0]}")/normalize-realm-export.sh" \
  "${WORK}/realm-partial.json" \
  "${WORK}/users.json" \
  "${WORK}/user-roles.json" \
  > "${TARGET}"

log "Done. \`git diff ${TARGET#$REPO_ROOT/}\` shows any drift."
