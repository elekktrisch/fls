#!/bin/sh
# alpenflight/auth/entrypoint.sh — Keycloak container entrypoint wrapper.
#
# Why this wrapper exists: Keycloak's `${env:VAR}` substitution at realm-import
# is applied to SMTP + IdentityProvider config, but NOT to `client.baseUrl`.
# The URL validator runs against baseUrl BEFORE substitution would happen, so
# a `${env:ALPENFLIGHT_WEB_BASE_URL}` placeholder lands as literal text and
# import fails with "Invalid client alpenflight-web: Base URL is not a valid URL".
#
# Workaround: keep a sed-substituted `${ALPENFLIGHT_WEB_BASE_URL}` marker in
# the committed realm-export.json (deliberately NOT the `${env:...}` syntax so
# it's obvious which layer owns the substitution), substitute it here before
# kc.sh runs.
#
# Boot-cycle note: `--import-realm` with the IGNORE_EXISTING strategy (H2
# default) only fires on a fresh DB. Rotating ALPENFLIGHT_WEB_BASE_URL in
# `.env` requires `rebuild-keycloak.sh` (or `down -v`) to re-import — same
# constraint that applies to the existing `${env:KEYCLOAK_GOOGLE_*}` markers.

set -eu

REALM_EXPORT="/opt/keycloak/data/import/realm-export.json"
BASE_URL="${ALPENFLIGHT_WEB_BASE_URL:-http://localhost:4200/}"

if [ -f "$REALM_EXPORT" ]; then
  sed -i "s|\${ALPENFLIGHT_WEB_BASE_URL}|${BASE_URL}|g" "$REALM_EXPORT"
fi

exec /opt/keycloak/bin/kc.sh "$@"
