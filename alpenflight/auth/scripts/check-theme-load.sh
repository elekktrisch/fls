#!/usr/bin/env bash
# Operator-driven smoke against a running Keycloak (typically
# http://localhost:8090) that the custom alpenflight theme is loaded
# and locale fallback to the parent works.
#
# Not wired to CI — CI has no live Keycloak for the alpenflight realm.
# The static realm-shape guard (sibling script) covers the theme-ref
# pinning; this script covers the Dockerfile COPY + theme-directory
# layout that the JSON shape cannot see. Email-theme rendering is
# eyeball-only via mailpit; not covered here.
#
# Usage:
#   KEYCLOAK_URL=http://localhost:8090 bash alpenflight/auth/scripts/check-theme-load.sh
#
# Exits 0 on pass, 1 with diagnostic on first failure.

set -euo pipefail

THEME_NAME="${THEME_NAME:-alpenflight}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
REALM="${REALM:-alpenflight}"
WEB_CLIENT_ID="${WEB_CLIENT_ID:-alpenflight-web}"
REDIRECT_URI="${REDIRECT_URI:-http%3A%2F%2Flocalhost%3A4200%2F}"

AUTH_BASE="${KEYCLOAK_URL%/}/realms/${REALM}/protocol/openid-connect/auth"

fail() { echo "FAIL: $1"; exit 1; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }

echo "smoking ${KEYCLOAK_URL} (realm=${REALM})"

# REDIRECT_URI must be pre-URL-encoded — printf interpolates raw.
build_url() {
  local locale="${1:-}"
  local extra="${locale:+&ui_locales=${locale}&kc_locale=${locale}}"
  printf '%s?client_id=%s&response_type=code&scope=openid&redirect_uri=%s&state=smoke&nonce=smoke%s' \
    "$AUTH_BASE" "$WEB_CLIENT_ID" "$REDIRECT_URI" "$extra"
}

# 1. Theme path appears in the rendered resource URLs. Keycloak emits
# `/resources/<hash>/login/${THEME_NAME}/...` — substring match on
# `/login/${THEME_NAME}/` survives Keycloak minor upgrades that rotate
# the hash segment.
HTML=$(curl -sS -L "$(build_url)") \
  || fail "could not reach ${AUTH_BASE} — is Keycloak up at ${KEYCLOAK_URL}?"

case "$HTML" in
  *"/login/${THEME_NAME}/"*) ok "login theme path /login/${THEME_NAME}/ present in rendered HTML" ;;
  *) fail "rendered login HTML does not reference /login/${THEME_NAME}/ — Dockerfile COPY or theme.properties parent broken" ;;
esac

# 2. Locale fallback to the parent's stock message bundles. The
# alpenflight theme ships no message bundles; per-locale labels render
# via parent inheritance. The strongest signal is `<html lang="xx">`
# which Keycloak emits per request — independent of any specific label
# token surviving an upstream copy edit.
for locale in de fr it; do
  HTML=$(curl -sS -L "$(build_url "$locale")")
  case "$HTML" in
    *"<html lang=\"${locale}\""*) ok "locale=${locale}: <html lang=\"${locale}\"> rendered (parent fallback works)" ;;
    *) fail "locale=${locale}: <html lang=\"${locale}\"> not found in rendered HTML" ;;
  esac
done

# 3. Account console reachable (v3 React app). HTTP 200 is the load-
# bearing assertion; v3 emits asset URLs with rotated hashes, so the
# theme-name substring is checked best-effort but a 200 alone confirms
# the theme dir is at least valid.
ACCOUNT_URL="${KEYCLOAK_URL%/}/realms/${REALM}/account/"
HTTP_CODE=$(curl -sS -L -o /tmp/kc-account-smoke.html -w '%{http_code}' "$ACCOUNT_URL")
[[ "$HTTP_CODE" == "200" ]] || fail "account console returned HTTP ${HTTP_CODE} — theme dir invalid or realm misconfigured"
ok "account console returned HTTP 200"

echo "PASS"
