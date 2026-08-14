#!/usr/bin/env bash

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

case "$KEYCLOAK_URL" in
  http://localhost:*|http://127.0.0.1:*|http://keycloak:*) ;;
  *) fail "refusing to run against non-localhost KEYCLOAK_URL=${KEYCLOAK_URL} (this script creates users + sends verify-email)" ;;
esac

echo "integration probe: ${KEYCLOAK_URL} (realm=${REALM}) + ${MAILPIT_URL}"


admin_token() {
  curl -fsS -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=password' \
    -d 'client_id=admin-cli' \
    -d "username=${ADMIN_USER}" \
    -d "password=${ADMIN_PASS}" \
    | jq -r '.access_token'
}

urlencode() {
  python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$1"
}

pkce_challenge() {
  openssl rand -hex 32 \
    | openssl dgst -sha256 -binary \
    | openssl base64 \
    | tr -d '=\n' \
    | tr '/+' '_-'
}


TOKEN=$(admin_token) || fail "could not acquire admin token — KC_BOOTSTRAP_ADMIN_* not seeded?"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || fail "admin token empty"

ADMIN_BASE_URL=$(curl -fsS -G "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "clientId=${WEB_CLIENT_ID}" \
  | jq -r '.[0].baseUrl // ""')
[[ "$ADMIN_BASE_URL" == "$EXPECTED_BASE_URL" ]] \
  || fail "alpenflight-web.baseUrl in H2 = '${ADMIN_BASE_URL}' — expected '${EXPECTED_BASE_URL}'. Build-arg substitution failed?"
ok "alpenflight-web.baseUrl in H2 = ${ADMIN_BASE_URL} (build-arg substitution resolved)"

REALM_CFG=$(curl -fsS "${KEYCLOAK_URL}/admin/realms/${REALM}" -H "Authorization: Bearer ${TOKEN}")
SMTP_FROM=$(jq -r '.smtpServer.from // ""' <<<"$REALM_CFG")
SMTP_HOST=$(jq -r '.smtpServer.host // ""' <<<"$REALM_CFG")
GOOGLE_CLIENT_ID=$(jq -r '[.identityProviders[]? | select(.alias=="google") | .config.clientId][0] // ""' <<<"$REALM_CFG")
[[ "$SMTP_FROM" == *@*.* ]] \
  || fail "smtpServer.from in H2 looks unresolved (got '$SMTP_FROM'). Realm-import env-substitution likely failed — check docker-compose env_file ordering."
[[ "$SMTP_HOST" == "mailpit" ]] \
  || fail "smtpServer.host in H2 = '$SMTP_HOST' — expected 'mailpit'. env-substitution failure or wrong override?"
[[ "$GOOGLE_CLIENT_ID" != "KEYCLOAK_GOOGLE_CLIENT_ID" ]] \
  || fail "Google IdP clientId in H2 = literal 'KEYCLOAK_GOOGLE_CLIENT_ID' — the KEYCLOAK_GOOGLE_CLIENT_ID env var was unset at import time. After fixing the .env, drop H2 (rebuild-keycloak.sh) to re-import."
ok "realm SMTP + Google-IdP env-substitutions resolved (from=${SMTP_FROM}, host=${SMTP_HOST}, google clientId != literal)"

CODE_CHALLENGE=$(pkce_challenge)
REDIRECT=$(urlencode "${EXPECTED_BASE_URL}")
LOGIN_URL="${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/auth?client_id=${WEB_CLIENT_ID}&response_type=code&scope=openid&redirect_uri=${REDIRECT}&state=probe&nonce=probe&code_challenge=${CODE_CHALLENGE}&code_challenge_method=S256"
HTML=$(curl -fsS -L -c /tmp/kc-cookies -b /tmp/kc-cookies "$LOGIN_URL") \
  || fail "auth endpoint did not return login HTML (PKCE present; check Keycloak logs for the redirect cause)"

FOOTER_HREF=$(printf '%s' "$HTML" \
  | python3 -c "
import re, sys
m = re.search(r'<a[^>]*class=\"af-back-to-landing\"[^>]*href=\"([^\"]+)\"', sys.stdin.read())
print(m.group(1) if m else '')
")

[[ -n "$FOOTER_HREF" ]] || fail "footer.ftl 'af-back-to-landing' link not found in login HTML (S-171 footer macro broken?)"
[[ "$FOOTER_HREF" == "$EXPECTED_BASE_URL" ]] \
  || fail "footer href mismatch — expected '${EXPECTED_BASE_URL}', got '${FOOTER_HREF}'. footer.ftl is rendering against a stale or wrong baseUrl."
ok "footer 'Back to Start' href = ${FOOTER_HREF} (FreeMarker render exercised end-to-end)"


TEST_USER="verify-email-probe"
TEST_EMAIL="verify-email-probe@example.org"

PREEXIST_ID=$(curl -fsS -G "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "username=${TEST_USER}" \
  --data-urlencode "exact=true" | jq -r '.[0].id // empty')
if [[ -n "$PREEXIST_ID" ]]; then
  curl -fsS -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${PREEXIST_ID}" \
    -H "Authorization: Bearer ${TOKEN}"
fi

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
  local admin_token_reacquired_because_mailpit_polling_outlives_the_first_one
  admin_token_reacquired_because_mailpit_polling_outlives_the_first_one=$(admin_token) || return 0
  curl -fsS -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}" \
    -H "Authorization: Bearer ${admin_token_reacquired_because_mailpit_polling_outlives_the_first_one}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

REDIRECT=$(urlencode "${EXPECTED_BASE_URL}")
HTTP=$(curl -fsS -o /tmp/kc-verify.out -w '%{http_code}' \
  -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}/send-verify-email?client_id=${WEB_CLIENT_ID}&redirect_uri=${REDIRECT}" \
  -H "Authorization: Bearer ${TOKEN}")
[[ "$HTTP" == "204" ]] || fail "send-verify-email HTTP ${HTTP}: $(cat /tmp/kc-verify.out) — FreeMarker template failure (Failed to template email) surfaces as 500 here"

RECEIVED=""
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  RECEIVED=$(curl -fsS -G "${MAILPIT_URL}/api/v1/search" \
    --data-urlencode "query=to:\"${TEST_EMAIL}\"" \
    | jq -r '.messages[0].ID // empty')
  if [[ -n "$RECEIVED" ]]; then break; fi
  sleep 1
done
[[ -n "$RECEIVED" ]] \
  || fail "verify-email never reached mailpit (kc.log will show 'SEND_VERIFY_EMAIL_ERROR' if FreeMarker / SMTP failed — check the realm's smtpServer + email theme)"

MSG_RAW=$(curl -fsS "${MAILPIT_URL}/api/v1/message/${RECEIVED}/raw")
case "$MSG_RAW" in
  *"/realms/${REALM}/login-actions/action-token"*) ;;
  *) fail "verify-email message body missing the expected /realms/${REALM}/login-actions/action-token verification link — FreeMarker template degraded?" ;;
esac
ok "verify-email round-trip: created user, send-verify-email 204, mailpit received message with realm action-token link (FreeMarker template renders cleanly)"

echo "PASS"
