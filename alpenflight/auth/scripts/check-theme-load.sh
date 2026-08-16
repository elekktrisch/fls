#!/usr/bin/env bash

set -euo pipefail

THEME_NAME="${THEME_NAME:-alpenflight}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
REALM="${REALM:-alpenflight}"
WEB_CLIENT_ID="${WEB_CLIENT_ID:-alpenflight-web}"
REDIRECT_URI_URL_ENCODED="${REDIRECT_URI_URL_ENCODED:-http%3A%2F%2Flocalhost%3A4200%2F}"

AUTH_BASE="${KEYCLOAK_URL%/}/realms/${REALM}/protocol/openid-connect/auth"

fail() { echo "FAIL: $1"; exit 1; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }

echo "smoking ${KEYCLOAK_URL} (realm=${REALM})"

to_base64url() { openssl base64 | tr '+/' '-_' | tr -d '=\n'; }

pkce_challenge_without_which_the_web_client_rejects_the_authorize_request() {
  local verifier
  verifier=$(openssl rand 32 | to_base64url)
  printf '%s' "$verifier" | openssl dgst -sha256 -binary | to_base64url
}

build_url() {
  local locale="${1:-}"
  local extra="${locale:+&ui_locales=${locale}}"
  printf '%s?client_id=%s&response_type=code&scope=openid&redirect_uri=%s&state=smoke&nonce=smoke&code_challenge=%s&code_challenge_method=S256%s' \
    "$AUTH_BASE" "$WEB_CLIENT_ID" "$REDIRECT_URI_URL_ENCODED" \
    "$(pkce_challenge_without_which_the_web_client_rejects_the_authorize_request)" "$extra"
}

HTML=$(curl -sS -L "$(build_url)") \
  || fail "could not reach ${AUTH_BASE} — is Keycloak up at ${KEYCLOAK_URL}?"

case "$HTML" in
  *"/login/${THEME_NAME}/"*) ok "login theme path /login/${THEME_NAME}/ present in rendered HTML" ;;
  *) fail "rendered login HTML does not reference /login/${THEME_NAME}/ — Dockerfile COPY or theme.properties parent broken" ;;
esac

for locale in de fr it; do
  HTML=$(curl -sS -L "$(build_url "$locale")")
  ROOT_ELEMENT=$(printf '%s' "$HTML" | grep -o '<html[^>]*>' | head -1 || true)
  case "$ROOT_ELEMENT" in
    *"lang=\"${locale}\""*) ok "locale=${locale}: <html lang=\"${locale}\"> rendered (parent fallback works)" ;;
    *) fail "locale=${locale}: the rendered root element is '${ROOT_ELEMENT}', which carries no lang=\"${locale}\"" ;;
  esac
done

ACCOUNT_URL="${KEYCLOAK_URL%/}/realms/${REALM}/account/"
HTTP_CODE=$(curl -sS -L -o /tmp/kc-account-smoke.html -w '%{http_code}' "$ACCOUNT_URL")
[[ "$HTTP_CODE" == "200" ]] || fail "account console returned HTTP ${HTTP_CODE} — theme dir invalid or realm misconfigured"
ok "account console returned HTTP 200"

echo "PASS"
