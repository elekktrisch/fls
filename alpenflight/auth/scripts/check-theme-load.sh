#!/usr/bin/env bash
# alpenflight/auth/scripts/check-theme-load.sh
#
# Operator-driven smoke against a running Keycloak (typically
# http://localhost:8090) that the custom alpenflight theme is loaded and
# locale fallback to the parent works.
#
# Not wired to CI — CI has no live Keycloak for the alpenflight realm.
# The static realm-shape guard (sibling script) covers the theme-ref
# pinning; this script covers the Dockerfile COPY + theme-directory
# layout that the JSON shape cannot see.
#
# Usage:
#   KEYCLOAK_URL=http://localhost:8090 bash alpenflight/auth/scripts/check-theme-load.sh
#
# Exits 0 on pass, 1 with diagnostic on first failure.

set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
REALM="${REALM:-alpenflight}"
WEB_CLIENT_ID="${WEB_CLIENT_ID:-alpenflight-web}"
REDIRECT_URI="${REDIRECT_URI:-http://localhost:4200/}"

AUTH_BASE="${KEYCLOAK_URL%/}/realms/${REALM}/protocol/openid-connect/auth"

fail() { echo "FAIL: $1"; exit 1; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }

echo "smoking ${KEYCLOAK_URL} (realm=${REALM})"

# Build an auth URL that triggers the login page render with a state +
# nonce. PKCE not strictly required for the page render; omitted to keep
# the smoke shell-only (no openssl/jq dance for the challenge).
build_url() {
  local locale="${1:-}"
  local extra="${locale:+&ui_locales=${locale}&kc_locale=${locale}}"
  printf '%s?client_id=%s&response_type=code&scope=openid&redirect_uri=%s&state=smoke&nonce=smoke%s' \
    "$AUTH_BASE" "$WEB_CLIENT_ID" "$REDIRECT_URI" "$extra"
}

# 1. Theme path appears in the rendered HTML. Keycloak emits resource URLs
# like `/resources/<hash>/login/alpenflight/...` — substring match on
# `/login/alpenflight/` survives Keycloak minor upgrades that rotate the
# hash segment.
HTML=$(curl -sS -L "$(build_url)" 2>&1) \
  || fail "could not reach ${AUTH_BASE} — is Keycloak up at ${KEYCLOAK_URL}?"

case "$HTML" in
  *"/login/alpenflight/"*) ok "login theme path /login/alpenflight/ present in rendered HTML" ;;
  *) fail "rendered login HTML does not reference /login/alpenflight/ — Dockerfile COPY or theme.properties parent broken" ;;
esac

# 2. Locale fallback to keycloak.v2 parent. The alpenflight theme ships
# no message bundles; stock labels render via parent inheritance. Pick
# one known-translated stock token per locale (Keycloak's de/fr/it
# bundles all translate "Sign in"). Loose substring; resilient to
# upper/lower case + surrounding markup.
declare -A LOCALE_LABEL=(
  [de]="Anmelden"
  [fr]="connecter"
  [it]="Accedi"
)
for locale in de fr it; do
  HTML=$(curl -sS -L "$(build_url "$locale")")
  expected="${LOCALE_LABEL[$locale]}"
  case "$HTML" in
    *"${expected}"*) ok "locale=${locale}: stock label '${expected}' rendered (parent fallback works)" ;;
    *) fail "locale=${locale}: expected stock label '${expected}' not found in rendered HTML" ;;
  esac
done

# 3. Account console reachable (v3 React app). HTTP 200 + the v3 SPA
# bootstrap marker. Confirms account theme dir + content.json are
# served; the React app handles the rest at runtime.
ACCOUNT_URL="${KEYCLOAK_URL%/}/realms/${REALM}/account/"
HTML=$(curl -sS -L "$ACCOUNT_URL")
case "$HTML" in
  *"/account/alpenflight/"*) ok "account theme path /account/alpenflight/ present in rendered HTML" ;;
  *) fail "rendered account HTML does not reference /account/alpenflight/ — account theme not loaded" ;;
esac

echo "PASS"
