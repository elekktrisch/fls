#!/usr/bin/env bash
# alpenflight/auth/scripts/check-keycloak-integration.sh
#
# Integration probes that need a running Keycloak + mailpit (CI does the
# bring-up; the sibling check-realm-shape.sh runs against the committed
# realm-export.json in isolation).
#
# Covers S-173 acceptance criteria that the static shape guard can't reach:
#
#   1. alpenflight-web client baseUrl env-substitution resolved at boot —
#      login HTML's "Back to Start" footer href renders to the env-set value
#      (validates realm-export ${env:ALPENFLIGHT_WEB_BASE_URL} substitution +
#      footer.ftl shape + alpenflight-web.baseUrl wiring end-to-end).
#
#   2. Verify-email FreeMarker template (S-173 boy-scout) — admin-API trigger
#      a send-verify-email and assert mailpit received it. FreeMarker template
#      failures suppress the SMTP send entirely, so mailpit-received is the
#      load-bearing positive signal.
#
# Usage (local, against a running stack):
#
#   bash alpenflight/auth/scripts/check-keycloak-integration.sh
#
# Usage (CI, called after `docker compose --profile next up --wait`):
#
#   same; uses defaults for localhost:8090 / localhost:8025 / admin / admin.
#
# Exit 0 on pass, 1 with diagnostic on first failure.

set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
REALM="${REALM:-alpenflight}"
WEB_CLIENT_ID="${WEB_CLIENT_ID:-alpenflight-web}"
EXPECTED_BASE_URL="${EXPECTED_BASE_URL:-http://localhost:4200/}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASS="${KC_ADMIN_PASS:-admin}"

fail() { echo "FAIL: $1"; exit 1; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }

require_jq() { command -v jq >/dev/null 2>&1 || fail "jq is required (apt-get install -y jq)"; }
require_jq

echo "integration probe: ${KEYCLOAK_URL} (realm=${REALM}) + ${MAILPIT_URL}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

admin_token() {
  curl -fsS -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=password' \
    -d 'client_id=admin-cli' \
    -d "username=${ADMIN_USER}" \
    -d "password=${ADMIN_PASS}" \
    | jq -r '.access_token'
}

# Login HTML rendered by the standard authorization-code flow. The header /
# footer macros (S-171 footer.ftl) substitute against the resolved client
# baseUrl, so this is the canonical end-to-end probe.
login_html() {
  local redirect; redirect=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "${EXPECTED_BASE_URL}")
  curl -fsS -L "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/auth?client_id=${WEB_CLIENT_ID}&response_type=code&scope=openid&redirect_uri=${redirect}&state=probe&nonce=probe"
}

# ---------------------------------------------------------------------------
# 1. Footer "Back to Start" href reflects ${env:ALPENFLIGHT_WEB_BASE_URL}
# ---------------------------------------------------------------------------
HTML=$(login_html) || fail "could not reach ${KEYCLOAK_URL} login page — is Keycloak up?"

# footer.ftl emits <a class="af-back-to-landing" href="${client.baseUrl}">.
# Scrape the href attribute on the link with the class we own.
FOOTER_HREF=$(printf '%s' "$HTML" \
  | python3 -c "
import re, sys
m = re.search(r'<a[^>]*class=\"af-back-to-landing\"[^>]*href=\"([^\"]+)\"', sys.stdin.read())
print(m.group(1) if m else '')
")

[[ -n "$FOOTER_HREF" ]] || fail "footer.ftl 'af-back-to-landing' link not found in login HTML (S-171 macro broken?)"
[[ "$FOOTER_HREF" == "$EXPECTED_BASE_URL" ]] \
  || fail "footer href mismatch — expected '${EXPECTED_BASE_URL}', got '${FOOTER_HREF}'. alpenflight-web.baseUrl env-substitution not resolved correctly."
ok "footer 'Back to Start' href = ${FOOTER_HREF} (env-substitution resolved end-to-end)"

# ---------------------------------------------------------------------------
# 2. Verify-email FreeMarker template (S-173 boy-scout)
# ---------------------------------------------------------------------------
TOKEN=$(admin_token) || fail "could not acquire admin token — KC_BOOTSTRAP_ADMIN_* not seeded?"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || fail "admin token empty"

TEST_USER="verify-email-probe"
TEST_EMAIL="verify-email-probe@example.org"

# Clean any leftover from a previous failed run before re-asserting state.
PREEXIST_ID=$(curl -fsS -G "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "username=${TEST_USER}" \
  --data-urlencode "exact=true" | jq -r '.[0].id // empty')
if [[ -n "$PREEXIST_ID" ]]; then
  curl -fsS -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${PREEXIST_ID}" \
    -H "Authorization: Bearer ${TOKEN}"
fi

# Mailpit: snapshot the message count BEFORE the send so we don't false-positive
# on a stale email from a prior run.
MAILPIT_BEFORE=$(curl -fsS "${MAILPIT_URL}/api/v1/messages?limit=1" | jq -r '.total // 0')

# Create the test user. Locale=de matches the real failure case (operator's
# 2026-05-27 trace) and exercises the de message-bundle path.
CREATE_BODY=$(cat <<JSON
{
  "username": "${TEST_USER}",
  "email": "${TEST_EMAIL}",
  "emailVerified": false,
  "enabled": true,
  "attributes": { "locale": ["de"] }
}
JSON
)
HTTP=$(curl -fsS -o /tmp/kc-create.out -w '%{http_code}' \
  -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "${CREATE_BODY}")
[[ "$HTTP" == "201" ]] || fail "user-create HTTP ${HTTP}: $(cat /tmp/kc-create.out)"

USER_ID=$(curl -fsS -G "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "username=${TEST_USER}" \
  --data-urlencode "exact=true" | jq -r '.[0].id')
[[ -n "$USER_ID" && "$USER_ID" != "null" ]] || fail "could not locate created test user"

cleanup() {
  curl -fsS -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}" \
    -H "Authorization: Bearer ${TOKEN}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Trigger verify-email. The redirect_uri must match the alpenflight-web client's
# redirectUris allowlist; the canonical SPA root is the safest choice.
REDIRECT=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "${EXPECTED_BASE_URL}")
HTTP=$(curl -fsS -o /tmp/kc-verify.out -w '%{http_code}' \
  -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}/send-verify-email?client_id=${WEB_CLIENT_ID}&redirect_uri=${REDIRECT}" \
  -H "Authorization: Bearer ${TOKEN}")
[[ "$HTTP" == "204" ]] || fail "send-verify-email HTTP ${HTTP}: $(cat /tmp/kc-verify.out) — FreeMarker template failure (Failed to template email) surfaces as 500 here"

# Poll mailpit (`/api/v1/messages` returns DESC by Received). The test email
# is targeted by exact To address — query string is mailpit's search DSL.
RECEIVED=""
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  RECEIVED=$(curl -fsS -G "${MAILPIT_URL}/api/v1/search" \
    --data-urlencode "query=to:\"${TEST_EMAIL}\"" \
    | jq -r '.messages[0].ID // empty')
  if [[ -n "$RECEIVED" ]]; then break; fi
  sleep 1
done
[[ -n "$RECEIVED" ]] \
  || fail "verify-email never reached mailpit (kc.log will show 'SEND_VERIFY_EMAIL_ERROR / Failed to template email' if FreeMarker is the cause; email/theme.properties is the likely culprit per S-173 boy-scout)"

# Sanity-check the message content rendered (not just the envelope) — a
# FreeMarker partial failure would deliver an empty/blank body.
BODY_SIZE=$(curl -fsS "${MAILPIT_URL}/api/v1/message/${RECEIVED}" | jq -r '.Size // 0')
[[ "$BODY_SIZE" -gt 100 ]] \
  || fail "verify-email message body suspiciously small (${BODY_SIZE} bytes) — template likely degraded"
ok "verify-email round-trip: created user, send-verify-email 204, mailpit received ${BODY_SIZE}B message (FreeMarker template renders cleanly)"

echo "PASS"
