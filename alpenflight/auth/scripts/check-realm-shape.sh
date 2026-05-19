#!/usr/bin/env bash
# alpenflight/auth/scripts/check-realm-shape.sh
#
# CI / pre-commit guard for alpenflight/auth/realm-export.json. Asserts the
# load-bearing security invariants S-019 ships:
#
#   - alpenflight-web is PKCE-S256 public; standardFlow only.
#   - alpenflight-backend is bearer-only.
#   - alpenflight-proffix is service-accounts-only (no interactive flows).
#   - 7 realm roles present (SYSTEM_ADMINISTRATOR, CLUB_ADMINISTRATOR, ...).
#   - 3 seed users (sysadmin, clubadmin1, pilot1) with expected role + clubId.
#   - clubId protocol mapper present.
#   - No private signing key in committed export.
#   - No real-domain emails (only example.com / .org / .net / .test).
#   - Redirect URIs are explicit localhost paths (no `*`).
#
# Exit 0 on pass, exit 1 with diagnostic on first failure.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
EXPORT="${REPO_ROOT}/alpenflight/auth/realm-export.json"
[[ -f "$EXPORT" ]] || { echo "FAIL: $EXPORT missing"; exit 1; }

fail() { echo "FAIL: $1"; exit 1; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }

echo "checking $EXPORT"

# --- clients ---
WEB=$(jq '.clients[] | select(.clientId=="alpenflight-web")' "$EXPORT")
[[ -n "$WEB" ]] || fail "alpenflight-web client missing"
[[ $(jq -r '.publicClient' <<<"$WEB") == "true" ]] || fail "alpenflight-web must be publicClient=true"
[[ $(jq -r '.standardFlowEnabled' <<<"$WEB") == "true" ]] || fail "alpenflight-web must have standardFlowEnabled=true"
[[ $(jq -r '.directAccessGrantsEnabled' <<<"$WEB") == "false" ]] || fail "alpenflight-web must have directAccessGrantsEnabled=false"
[[ $(jq -r '.implicitFlowEnabled' <<<"$WEB") == "false" ]] || fail "alpenflight-web must have implicitFlowEnabled=false"
[[ $(jq -r '.attributes["pkce.code.challenge.method"]' <<<"$WEB") == "S256" ]] || fail "alpenflight-web must enforce PKCE-S256"
ok "alpenflight-web: public + PKCE-S256 + standardFlow only"

BACKEND=$(jq '.clients[] | select(.clientId=="alpenflight-backend")' "$EXPORT")
[[ $(jq -r '.bearerOnly' <<<"$BACKEND") == "true" ]] || fail "alpenflight-backend must be bearerOnly=true"
ok "alpenflight-backend: bearer-only"

PROFFIX=$(jq '.clients[] | select(.clientId=="alpenflight-proffix")' "$EXPORT")
[[ $(jq -r '.serviceAccountsEnabled' <<<"$PROFFIX") == "true" ]] || fail "alpenflight-proffix must have serviceAccountsEnabled=true"
[[ $(jq -r '.standardFlowEnabled' <<<"$PROFFIX") == "false" ]] || fail "alpenflight-proffix must have standardFlowEnabled=false"
[[ $(jq -r '.directAccessGrantsEnabled' <<<"$PROFFIX") == "false" ]] || fail "alpenflight-proffix must have directAccessGrantsEnabled=false"
ok "alpenflight-proffix: service-accounts only"

# --- roles ---
EXPECTED_ROLES="CLUB_ADMINISTRATOR FLIGHT_OPERATOR GUEST OFFICE_USER PILOT SYSTEM_ADMINISTRATOR proffix-sync"
ACTUAL=$(jq -r '[.roles.realm[].name] | map(select(. as $r | ["CLUB_ADMINISTRATOR","FLIGHT_OPERATOR","GUEST","OFFICE_USER","PILOT","SYSTEM_ADMINISTRATOR","proffix-sync"] | index($r))) | sort | join(" ")' "$EXPORT")
[[ "$ACTUAL" == "$EXPECTED_ROLES" ]] || fail "realm roles drift: have [$ACTUAL], want [$EXPECTED_ROLES]"
ok "realm roles: ${EXPECTED_ROLES}"

# --- seed users ---
for u in sysadmin clubadmin1 pilot1; do
  jq -e --arg u "$u" '.users[] | select(.username==$u)' "$EXPORT" >/dev/null || fail "seed user $u missing"
done
ok "seed users: sysadmin, clubadmin1, pilot1"

# clubId on the right users
[[ $(jq -r '.users[] | select(.username=="clubadmin1") | .attributes.clubId[0]' "$EXPORT") == "club-1" ]] || fail "clubadmin1.clubId must be club-1"
[[ $(jq -r '.users[] | select(.username=="pilot1")     | .attributes.clubId[0]' "$EXPORT") == "club-1" ]] || fail "pilot1.clubId must be club-1"
SYSADMIN_CLUB=$(jq -r '.users[] | select(.username=="sysadmin") | .attributes.clubId // "<unset>"' "$EXPORT")
[[ "$SYSADMIN_CLUB" == "<unset>" ]] || fail "sysadmin must NOT carry clubId (cross-tenant principal)"
ok "clubId attribute: club-1 on clubadmin1/pilot1, unset on sysadmin"

# --- clubId protocol mapper ---
MAPPER=$(jq '[.clientScopes[] | select(.name=="clubId") | .protocolMappers[]? | select(.protocolMapper=="oidc-usermodel-attribute-mapper")] | length' "$EXPORT")
[[ "$MAPPER" -ge 1 ]] || fail "clubId protocol mapper missing"
ok "clubId protocol mapper present"

# --- private key absence (CRITICAL) ---
PRIV=$(jq '[.components["org.keycloak.keys.KeyProvider"][]?.config | (.privateKey // .privateKeyPem) // empty] | length' "$EXPORT")
[[ "$PRIV" == "0" ]] || fail "private signing key present in committed export ($PRIV occurrences)"
ok "no private signing key committed"

# --- redirect URI hygiene ---
WILDCARDS=$(jq '[.clients[]?.redirectUris[]? | select(.=="*")] | length' "$EXPORT")
[[ "$WILDCARDS" == "0" ]] || fail "wildcard ('*') redirect URI present"
ok "no wildcard redirect URIs"

# --- PII hygiene: only test-domain emails ---
BAD_EMAILS=$(jq -r '[.users[]?.email // empty | select(test("@(example\\.(com|org|net)|test)$") | not)] | join(",")' "$EXPORT")
[[ -z "$BAD_EMAILS" ]] || fail "non-test-domain email(s) in seed users: $BAD_EMAILS"
ok "seed user emails use test domains only"

# --- token policy (ADR 0007) ---
[[ $(jq -r '.accessTokenLifespan'        "$EXPORT") == "900"     ]] || fail "accessTokenLifespan must be 900 (got $(jq -r .accessTokenLifespan "$EXPORT"))"
[[ $(jq -r '.ssoSessionIdleTimeout'      "$EXPORT") == "2592000" ]] || fail "ssoSessionIdleTimeout must be 2592000 (30d)"
[[ $(jq -r '.ssoSessionMaxLifespan'      "$EXPORT") == "7776000" ]] || fail "ssoSessionMaxLifespan must be 7776000 (90d)"
[[ $(jq -r '.revokeRefreshToken'         "$EXPORT") == "true"    ]] || fail "revokeRefreshToken must be true (rotation enforcement)"
[[ $(jq -r '.refreshTokenMaxReuse'       "$EXPORT") == "0"       ]] || fail "refreshTokenMaxReuse must be 0 (no reuse)"
ok "ADR 0007 token policy: 15min access, 30d/90d refresh, rotation + no reuse"

# --- clubId user-profile permission (tenant-escalation gate) ---
# The user-profile config is a JSON string nested inside the realm export at
# .components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"][0].
# We parse it back out and assert clubId is admin-edit-only — if a future
# admin-UI tweak re-enables user-edit on clubId, a pilot could rewrite their
# own tenant assignment via the Account console.
CLUBID_EDIT=$(jq -r '
  .components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"][0]
  | fromjson
  | .attributes[] | select(.name == "clubId") | .permissions.edit | sort | join(",")
' "$EXPORT")
[[ "$CLUBID_EDIT" == "admin" ]] || fail "clubId user-profile must be admin-edit-only (got: [$CLUBID_EDIT])"
ok "clubId user-profile: admin-edit-only (tenant-escalation gate)"

# --- realm security hygiene ---
[[ $(jq -r '.registrationAllowed' "$EXPORT")  == "false" ]] || fail "registrationAllowed must be false"
[[ $(jq -r '.bruteForceProtected' "$EXPORT")  == "true"  ]] || fail "bruteForceProtected must be true"
[[ $(jq -r '.eventsEnabled' "$EXPORT")        == "true"  ]] || fail "eventsEnabled must be true"
[[ $(jq -r '.adminEventsEnabled' "$EXPORT")   == "true"  ]] || fail "adminEventsEnabled must be true"
ok "realm hygiene: registration off, bruteforce on, events + admin events on"

echo "PASS"
