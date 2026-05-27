#!/usr/bin/env bash
# alpenflight/auth/scripts/check-realm-shape.sh
#
# CI / pre-commit guard for alpenflight/auth/realm-export.json. Asserts the
# load-bearing security invariants S-019 ships:
#
#   - alpenflight-web is PKCE-S256 public; standardFlow only.
#   - alpenflight-backend is bearer-only.
#   - alpenflight-backend-admin is confidential service-accounts-only, scoped
#     to manage-users + view-users + query-users on realm-management (NOT
#     manage-realm / manage-clients / impersonation), with the dev-placeholder
#     secret.
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

# S-173: alpenflight-web.baseUrl is substituted at image-build time by the
# Dockerfile's `RUN sed` against the ALPENFLIGHT_WEB_BASE_URL build-arg.
# Keycloak's realm-import resolver does NOT cover client.baseUrl (URL
# validator runs before substitution) AND its `${env:VAR}` syntax doesn't
# work either — the resolver calls System.getenv on the literal property
# name (e.g. "env:KEYCLOAK_*"), which always returns null, then the
# StringPropertyReplacer colon-fallback substitutes the post-colon literal
# var name. Realm-import substitution actually only works with `${VAR}`
# (no `env:` prefix) — see other shape guards below.
WEB_BASE_URL=$(jq -r '.baseUrl // ""' <<<"$WEB")
[[ -n "$WEB_BASE_URL" ]] || fail "alpenflight-web.baseUrl is empty — substitution marker dropped on round-trip?"
[[ "$WEB_BASE_URL" == '${ALPENFLIGHT_WEB_BASE_URL}' ]] \
  || fail "alpenflight-web.baseUrl must be the literal \${ALPENFLIGHT_WEB_BASE_URL} marker (Dockerfile build-arg substitutes it at image build; normalize-realm-export.sh re-injects on round-trip). Got: '$WEB_BASE_URL' — likely sloppy export-realm.sh round-trip."
ok "alpenflight-web: public + PKCE-S256 + standardFlow only + baseUrl build-arg substituted"

BACKEND=$(jq '.clients[] | select(.clientId=="alpenflight-backend")' "$EXPORT")
[[ $(jq -r '.bearerOnly' <<<"$BACKEND") == "true" ]] || fail "alpenflight-backend must be bearerOnly=true"
ok "alpenflight-backend: bearer-only"

PROFFIX=$(jq '.clients[] | select(.clientId=="alpenflight-proffix")' "$EXPORT")
[[ $(jq -r '.serviceAccountsEnabled' <<<"$PROFFIX") == "true" ]] || fail "alpenflight-proffix must have serviceAccountsEnabled=true"
[[ $(jq -r '.standardFlowEnabled' <<<"$PROFFIX") == "false" ]] || fail "alpenflight-proffix must have standardFlowEnabled=false"
[[ $(jq -r '.directAccessGrantsEnabled' <<<"$PROFFIX") == "false" ]] || fail "alpenflight-proffix must have directAccessGrantsEnabled=false"
ok "alpenflight-proffix: service-accounts only"

ADMIN=$(jq '.clients[] | select(.clientId=="alpenflight-backend-admin")' "$EXPORT")
[[ -n "$ADMIN" ]] || fail "alpenflight-backend-admin client missing"
[[ $(jq -r '.bearerOnly' <<<"$ADMIN") == "false" ]] || fail "alpenflight-backend-admin must NOT be bearerOnly (it needs a service-account token endpoint)"
[[ $(jq -r '.serviceAccountsEnabled' <<<"$ADMIN") == "true" ]] || fail "alpenflight-backend-admin must have serviceAccountsEnabled=true"
[[ $(jq -r '.standardFlowEnabled' <<<"$ADMIN") == "false" ]] || fail "alpenflight-backend-admin must have standardFlowEnabled=false"
[[ $(jq -r '.directAccessGrantsEnabled' <<<"$ADMIN") == "false" ]] || fail "alpenflight-backend-admin must have directAccessGrantsEnabled=false"
[[ $(jq -r '.publicClient' <<<"$ADMIN") == "false" ]] || fail "alpenflight-backend-admin must be confidential"
[[ $(jq -r '.secret' <<<"$ADMIN") == "alpenflight-backend-admin-dev-secret" ]] || fail "alpenflight-backend-admin must carry the dev-placeholder secret in source (rotate at deploy)"

# Service-account role binding: exactly manage-users + view-users + query-users,
# scoped to realm-management. Anything broader (manage-realm / manage-clients /
# impersonation) makes this client a tenant-escalation surface — fail loud.
ADMIN_SA_ROLES=$(jq -r '
  .users[]
  | select(.serviceAccountClientId == "alpenflight-backend-admin")
  | .clientRoles["realm-management"]
  | sort
  | join(",")
' "$EXPORT")
EXPECTED_SA_ROLES="manage-users,query-users,view-users"
[[ "$ADMIN_SA_ROLES" == "$EXPECTED_SA_ROLES" ]] \
  || fail "alpenflight-backend-admin realm-management role grant drifted: have [$ADMIN_SA_ROLES], want [$EXPECTED_SA_ROLES]"
ok "alpenflight-backend-admin: confidential, service-accounts only, manage/view/query-users scope"

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

# --- theme refs ---
# A freshly-imported realm with no theme keys is the default-state catch —
# fail closed on null/missing, not only on wrong-value, so a sloppy admin-UI
# export that drops the keys silently regresses CI rather than slipping
# through. Keep THEME_NAME aligned with normalize-realm-export.sh + the
# alpenflight/auth/themes/<name>/ directory.
THEME_NAME="alpenflight"
for theme_key in loginTheme accountTheme emailTheme; do
  VAL=$(jq -r --arg k "$theme_key" '.[$k] // ""' "$EXPORT")
  [[ "$VAL" == "$THEME_NAME" ]] \
    || fail "$theme_key must be \"$THEME_NAME\" (got: '$VAL')"
done
ok "theme refs: loginTheme/accountTheme/emailTheme = $THEME_NAME"

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
# S-134: self-service signup is ON (was OFF until E-15). Verify-email + brute-force
# protection are the surviving guards; throwaway-account purge + per-IP rate-limit
# are deferred follow-ups (S-038 / S-041).
[[ $(jq -r '.registrationAllowed' "$EXPORT")  == "true"  ]] || fail "registrationAllowed must be true (S-134)"
[[ $(jq -r '.verifyEmail' "$EXPORT")          == "true"  ]] || fail "verifyEmail must be true (S-134 signup verification)"
[[ $(jq -r '.bruteForceProtected' "$EXPORT")  == "true"  ]] || fail "bruteForceProtected must be true"
[[ $(jq -r '.eventsEnabled' "$EXPORT")        == "true"  ]] || fail "eventsEnabled must be true"
[[ $(jq -r '.adminEventsEnabled' "$EXPORT")   == "true"  ]] || fail "adminEventsEnabled must be true"
ok "realm hygiene: registration on + verifyEmail on, bruteforce on, events + admin events on"

# --- password policy (S-134; bot-signup floor) ---
# Anchored matches per rule — substring match would silently pass if a future
# round-trip mutated `notUsername` into `notUsernameLowercase` or similar.
# Keycloak emits parameterized rules as `name(arg)`; non-parameterized as
# `name` (token-boundary).
PWPOL=$(jq -r '.passwordPolicy // ""' "$EXPORT")
for rx in 'length\(12\)' 'notUsername(\(|[^a-zA-Z])' 'notEmail(\(|[^a-zA-Z])' 'specialChars\(1\)'; do
  [[ "$PWPOL" =~ $rx ]] || fail "passwordPolicy missing rule matching /$rx/ (got: '$PWPOL')"
done
ok "password policy: length(12) + notUsername + notEmail + specialChars(1)"

# --- SMTP server (S-134; load-bearing for verify-email) ---
# Realm-import substitution at startup is `${VAR}` (NOT `${env:VAR}` — that
# syntax silently fails: the resolver does System.getenv("env:VAR") which is
# always null, then StringPropertyReplacer's colon-fallback substitutes the
# post-colon literal var name into the realm. Empirically verified in CI on
# S-173 — see https://github.com/keycloak/keycloak/blob/26.5.0/model/storage-services/src/main/java/org/keycloak/exportimport/AbstractFileBasedImportProvider.java).
[[ $(jq -e '.smtpServer | length > 0' "$EXPORT") == "true" ]] || fail "smtpServer block must be non-empty (S-134 verify-email)"
for key in host port from user password auth starttls; do
  VAL=$(jq -r --arg k "$key" '.smtpServer[$k] // ""' "$EXPORT")
  [[ "$VAL" == '${KEYCLOAK_'*'}' ]] || fail "smtpServer.$key must be a \${KEYCLOAK_...} substitution (got: '$VAL') — no real SMTP secrets in source"
done
ok "smtpServer: env-substituted host/port/from/user/password/auth/starttls"

# --- Google identity provider (S-134; federation entry + secret-leak guard) ---
GOOGLE=$(jq '.identityProviders[]? | select(.alias=="google")' "$EXPORT")
[[ -n "$GOOGLE" ]] || fail "Google identity provider missing (alias=google)"
[[ $(jq -r '.providerId' <<<"$GOOGLE") == "google" ]] || fail "Google IdP providerId must be 'google'"
[[ $(jq -r '.enabled' <<<"$GOOGLE") == "true" ]] || fail "Google IdP must be enabled"
[[ $(jq -r '.trustEmail' <<<"$GOOGLE") == "false" ]] || fail "Google IdP trustEmail must be false (S-134 hijack-vector guard: verify-mail challenge stays in the flow)"
[[ $(jq -r '.firstBrokerLoginFlowAlias' <<<"$GOOGLE") == "first broker login" ]] || fail "Google IdP must reference the stock 'first broker login' flow (no accidental custom-flow swap)"

# Secrets are env-substitution placeholders, never literal hex/random strings.
# The substring '${KEYCLOAK_GOOGLE_' anchors the assertion against a sloppy
# export-realm.sh round-trip that pulled a real prod secret into the committed
# file. Realm-import substitution syntax is `${VAR}` (NOT `${env:VAR}` — see
# the SMTP block comment above).
G_CLIENT_ID=$(jq -r '.config.clientId // ""' <<<"$GOOGLE")
G_CLIENT_SECRET=$(jq -r '.config.clientSecret // ""' <<<"$GOOGLE")
[[ "$G_CLIENT_ID" == '${KEYCLOAK_GOOGLE_CLIENT_ID}' ]] || fail "Google IdP config.clientId must be \${KEYCLOAK_GOOGLE_CLIENT_ID} (got: '$G_CLIENT_ID')"
[[ "$G_CLIENT_SECRET" == '${KEYCLOAK_GOOGLE_CLIENT_SECRET}' ]] || fail "Google IdP config.clientSecret must be \${KEYCLOAK_GOOGLE_CLIENT_SECRET} (got: '$G_CLIENT_SECRET' — looks like a real secret leaked into the export)"

# No per-IdP token overrides. ADR 0007 token policy (set at realm-level above) MUST
# apply uniformly to federated sessions; an entry in the IdP config that overrides
# accessTokenLifespan / refreshTokenMaxReuse / etc. is a drift surface.
IDP_TOKEN_OVERRIDES=$(jq -r '
  .config
  | to_entries
  | map(select(.key | test("Lifespan|Token|refresh|access"; "i")))
  | length
' <<<"$GOOGLE")
[[ "$IDP_TOKEN_OVERRIDES" == "0" ]] || fail "Google IdP config carries per-IdP token override(s) — token policy must stay realm-level (ADR 0007)"
ok "Google IdP: providerId=google, trustEmail=false, stock first-broker-login, env-substituted secrets, no per-IdP token overrides"

echo "PASS"
