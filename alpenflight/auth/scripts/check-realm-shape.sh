#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
EXPORT="${1:-${REPO_ROOT}/alpenflight/auth/realm-export.json}"
[[ -f "$EXPORT" ]] || { echo "FAIL: $EXPORT missing"; exit 1; }

fail() { echo "FAIL: $1"; exit 1; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }

echo "checking $EXPORT"

WEB=$(jq '.clients[] | select(.clientId=="alpenflight-web")' "$EXPORT")
[[ -n "$WEB" ]] || fail "alpenflight-web client missing"
[[ $(jq -r '.publicClient' <<<"$WEB") == "true" ]] || fail "alpenflight-web must be publicClient=true"
[[ $(jq -r '.standardFlowEnabled' <<<"$WEB") == "true" ]] || fail "alpenflight-web must have standardFlowEnabled=true"
[[ $(jq -r '.directAccessGrantsEnabled' <<<"$WEB") == "false" ]] || fail "alpenflight-web must have directAccessGrantsEnabled=false"
[[ $(jq -r '.implicitFlowEnabled' <<<"$WEB") == "false" ]] || fail "alpenflight-web must have implicitFlowEnabled=false"
[[ $(jq -r '.attributes["pkce.code.challenge.method"]' <<<"$WEB") == "S256" ]] || fail "alpenflight-web must enforce PKCE-S256"

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

ADMIN_SA_ROLES=$(jq -r '
  .users[]
  | select(.serviceAccountClientId == "alpenflight-backend-admin")
  | .clientRoles["realm-management"]
  | sort
  | join(",")
' "$EXPORT")
EXPECTED_SA_ROLES="manage-groups,manage-realm,manage-users,query-users,view-realm,view-users"
[[ "$ADMIN_SA_ROLES" == "$EXPECTED_SA_ROLES" ]] \
  || fail "alpenflight-backend-admin realm-management role grant drifted: have [$ADMIN_SA_ROLES], want [$EXPECTED_SA_ROLES]"

[[ $(jq -r '.clients[] | select(.clientId=="alpenflight-backend-admin") | .fullScopeAllowed' "$EXPORT") == "true" ]] \
  || fail "alpenflight-backend-admin must have fullScopeAllowed=true (else realm-management roles are stripped from the SA token → admin REST 403s)"
ok "alpenflight-backend-admin: confidential, service-accounts only, full-scope, manage-groups/realm/users + query-users + view-realm/users"

EXPECTED_ROLES="CLUB_ADMINISTRATOR FLIGHT_OPERATOR GUEST OFFICE_USER PILOT SYSTEM_ADMINISTRATOR proffix-sync"
ACTUAL=$(jq -r '[.roles.realm[].name] | map(select(. as $r | ["CLUB_ADMINISTRATOR","FLIGHT_OPERATOR","GUEST","OFFICE_USER","PILOT","SYSTEM_ADMINISTRATOR","proffix-sync"] | index($r))) | sort | join(" ")' "$EXPORT")
[[ "$ACTUAL" == "$EXPECTED_ROLES" ]] || fail "realm roles drift: have [$ACTUAL], want [$EXPECTED_ROLES]"
ok "realm roles: ${EXPECTED_ROLES}"

for u in sysadmin clubadmin1 pilot1; do
  jq -e --arg u "$u" '.users[] | select(.username==$u)' "$EXPORT" >/dev/null || fail "seed user $u missing"
done
ok "seed users: sysadmin, clubadmin1, pilot1"

[[ $(jq -r '.users[] | select(.username=="clubadmin1") | .attributes.clubId[0]' "$EXPORT") == "club-1" ]] || fail "clubadmin1.clubId must be club-1"
[[ $(jq -r '.users[] | select(.username=="pilot1")     | .attributes.clubId[0]' "$EXPORT") == "club-1" ]] || fail "pilot1.clubId must be club-1"
SYSADMIN_CLUB=$(jq -r '.users[] | select(.username=="sysadmin") | .attributes.clubId // "<unset>"' "$EXPORT")
[[ "$SYSADMIN_CLUB" == "<unset>" ]] || fail "sysadmin must NOT carry clubId (cross-tenant principal)"
ok "clubId attribute: club-1 on clubadmin1/pilot1, unset on sysadmin"

MAPPER=$(jq '[.clientScopes[] | select(.name=="clubId") | .protocolMappers[]? | select(.protocolMapper=="oidc-usermodel-attribute-mapper")] | length' "$EXPORT")
[[ "$MAPPER" -ge 1 ]] || fail "clubId protocol mapper missing"
ok "clubId protocol mapper present"

DEMO_SEAT_POOL_MIGRATION="${REPO_ROOT}/alpenflight/server/src/main/resources/db/migration/V62__demo_seat_pool.sql"
[[ -f "$DEMO_SEAT_POOL_MIGRATION" ]] || fail "V62__demo_seat_pool.sql missing — the demo seat pool has no club rows to bind to"

SEAT_COUNT=$(grep -oE 'generate_series\(1, [0-9]+\)' "$DEMO_SEAT_POOL_MIGRATION" \
  | grep -oE '[0-9]+\)$' | tr -d ')' | sort -u)
[[ "$SEAT_COUNT" =~ ^[0-9]+$ ]] \
  || fail "V62__demo_seat_pool.sql declares more than one seat count ($(tr '\n' ' ' <<<"$SEAT_COUNT")) — the club rows and the t_demo_seat rows must use the same generate_series bound"

SEAT_UUID_PREFIXES=$(grep -oE "'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]+' \|\| lpad\(seat\.number::text, 3, '0'\)" \
  "$DEMO_SEAT_POOL_MIGRATION" | grep -oE "^'[^']+'" | tr -d "'")
SEAT_CLUB_ID_PREFIX=$(sort <<<"$SEAT_UUID_PREFIXES" | uniq -c | sort -rn | head -1 | awk '{print $2}')
SEAT_CLUB_ID_PREFIX_USES=$(grep -c -F "'${SEAT_CLUB_ID_PREFIX}' || lpad" "$DEMO_SEAT_POOL_MIGRATION")
[[ "$SEAT_CLUB_ID_PREFIX_USES" == "2" ]] \
  || fail "V62__demo_seat_pool.sql builds the seat club id '${SEAT_CLUB_ID_PREFIX}…' at ${SEAT_CLUB_ID_PREFIX_USES} places, expected 2 (the t_club insert and the t_demo_seat.club_id insert). The guard cannot tell the club-id prefix from the seat-id prefix any more — re-derive it."

grep -qF "'demo' || seat.number" "$DEMO_SEAT_POOL_MIGRATION" \
  || fail "V62__demo_seat_pool.sql no longer names the seat principals 'demo' || seat.number — t_demo_seat.keycloak_username and the realm export have drifted apart"

SEAT_USERNAMES_IN_EXPORT=$(jq -r '[.users[]? | select(.username | test("^demo[0-9]+$")) | .username] | length' "$EXPORT")
[[ "$SEAT_USERNAMES_IN_EXPORT" == "$SEAT_COUNT" ]] \
  || fail "the realm export holds ${SEAT_USERNAMES_IN_EXPORT} demo seat principals, but V62__demo_seat_pool.sql provisions ${SEAT_COUNT} seat clubs. A seat without its principal leases to a visitor that cannot sign in."

for ((seat = 1; seat <= SEAT_COUNT; seat++)); do
  SEAT_USER="demo${seat}"
  SEAT_JSON=$(jq --arg u "$SEAT_USER" '.users[] | select(.username==$u)' "$EXPORT")
  [[ -n "$SEAT_JSON" ]] || fail "demo seat principal ${SEAT_USER} missing from the realm export"

  EXPECTED_SEAT_CLUB=$(printf '%s%03d' "$SEAT_CLUB_ID_PREFIX" "$seat")
  ACTUAL_SEAT_CLUB=$(jq -r '.attributes.clubId[0] // "<unset>"' <<<"$SEAT_JSON")
  [[ "$ACTUAL_SEAT_CLUB" == "$EXPECTED_SEAT_CLUB" ]] \
    || fail "${SEAT_USER}.clubId = '${ACTUAL_SEAT_CLUB}', but V62__demo_seat_pool.sql binds seat ${seat} to club '${EXPECTED_SEAT_CLUB}'. A seat principal on the wrong club reads another visitor's sandbox."

  jq -e '.realmRoles | index("CLUB_ADMINISTRATOR")' <<<"$SEAT_JSON" >/dev/null \
    || fail "${SEAT_USER} does not carry the realm role CLUB_ADMINISTRATOR — every read the demo screens make is role-gated"

  for identity_field in email firstName lastName; do
    IDENTITY_VALUE=$(jq -r --arg f "$identity_field" '.[$f] // ""' <<<"$SEAT_JSON")
    [[ -n "$IDENTITY_VALUE" ]] \
      || fail "${SEAT_USER}.${identity_field} is empty.
    Warning: an attributes-only PUT /users/{id} to Keycloak is field-selective. It NULLs email,
    firstName and lastName, and the principal then vanishes from every email lookup. It also
    starves the just-in-time user materializer, which refuses a token without preferred_username,
    given_name and email, so the seat gets no t_user row and reads nothing.
    Read the full representation, merge the attributes, and write the full representation back."
  done

  [[ $(jq -r '.enabled' <<<"$SEAT_JSON") == "true" ]] || fail "${SEAT_USER} is disabled"
  [[ $(jq -r '.emailVerified' <<<"$SEAT_JSON") == "true" ]] \
    || fail "${SEAT_USER}.emailVerified is false — the realm sets verifyEmail, so the seat principal cannot complete a login"
  [[ $(jq -r '.requiredActions | length' <<<"$SEAT_JSON") == "0" ]] \
    || fail "${SEAT_USER} carries a required action — a demo visitor gets an account-update form instead of the sandbox"
done

ok "demo seat pool: ${SEAT_COUNT} principals, each CLUB_ADMINISTRATOR on its own V62 seat club, identity fields intact"

PRIV=$(jq '[.components["org.keycloak.keys.KeyProvider"][]?.config | (.privateKey // .privateKeyPem) // empty] | length' "$EXPORT")
[[ "$PRIV" == "0" ]] || fail "private signing key present in committed export ($PRIV occurrences)"
ok "no private signing key committed"

WILDCARDS=$(jq '[.clients[]?.redirectUris[]? | select(.=="*")] | length' "$EXPORT")
[[ "$WILDCARDS" == "0" ]] || fail "wildcard ('*') redirect URI present"
ok "no wildcard redirect URIs"

BAD_EMAILS=$(jq -r '[.users[]?.email // empty | select(test("@(example\\.(com|org|net)|test)$") | not)] | join(",")' "$EXPORT")
[[ -z "$BAD_EMAILS" ]] || fail "non-test-domain email(s) in seed users: $BAD_EMAILS"
ok "seed user emails use test domains only"

THEME_NAME="alpenflight"
for theme_key in loginTheme accountTheme emailTheme; do
  VAL=$(jq -r --arg k "$theme_key" '.[$k] // ""' "$EXPORT")
  [[ "$VAL" == "$THEME_NAME" ]] \
    || fail "$theme_key must be \"$THEME_NAME\" (got: '$VAL')"
done
ok "theme refs: loginTheme/accountTheme/emailTheme = $THEME_NAME"

[[ $(jq -r '.accessTokenLifespan'        "$EXPORT") == "900"     ]] || fail "accessTokenLifespan must be 900 (got $(jq -r .accessTokenLifespan "$EXPORT"))"
[[ $(jq -r '.ssoSessionIdleTimeout'      "$EXPORT") == "2592000" ]] || fail "ssoSessionIdleTimeout must be 2592000 (30d)"
[[ $(jq -r '.ssoSessionMaxLifespan'      "$EXPORT") == "7776000" ]] || fail "ssoSessionMaxLifespan must be 7776000 (90d)"
[[ $(jq -r '.revokeRefreshToken'         "$EXPORT") == "true"    ]] || fail "revokeRefreshToken must be true (rotation enforcement)"
[[ $(jq -r '.refreshTokenMaxReuse'       "$EXPORT") == "0"       ]] || fail "refreshTokenMaxReuse must be 0 (no reuse)"
ok "ADR 0007 token policy: 15min access, 30d/90d refresh, rotation + no reuse"

CLUBID_EDIT=$(jq -r '
  .components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"][0]
  | fromjson
  | .attributes[] | select(.name == "clubId") | .permissions.edit | sort | join(",")
' "$EXPORT")
[[ "$CLUBID_EDIT" == "admin" ]] || fail "clubId user-profile must be admin-edit-only (got: [$CLUBID_EDIT])"
ok "clubId user-profile: admin-edit-only (tenant-escalation gate)"

[[ $(jq -r '.registrationAllowed' "$EXPORT")  == "true"  ]] || fail "registrationAllowed must be true (S-134)"
[[ $(jq -r '.verifyEmail' "$EXPORT")          == "true"  ]] || fail "verifyEmail must be true (S-134 signup verification)"
[[ $(jq -r '.resetPasswordAllowed' "$EXPORT") == "true"  ]] || fail "resetPasswordAllowed must be true (J-19 AC-1 to AC-4). This flag alone puts the forgot-password link on the Keycloak login page and opens the reset-credentials flow. With it off, /lostpassword hands the member to a page that offers no recovery, and the whole reset chain dies."
[[ $(jq -r '.bruteForceProtected' "$EXPORT")  == "true"  ]] || fail "bruteForceProtected must be true"
[[ $(jq -r '.eventsEnabled' "$EXPORT")        == "true"  ]] || fail "eventsEnabled must be true"
[[ $(jq -r '.adminEventsEnabled' "$EXPORT")   == "true"  ]] || fail "adminEventsEnabled must be true"
ok "realm hygiene: registration on + verifyEmail on + resetPasswordAllowed on, bruteforce on, events + admin events on"

PWPOL=$(jq -r '.passwordPolicy // ""' "$EXPORT")
for rx in 'length\(12\)' 'notUsername(\(|[^a-zA-Z])' 'notEmail(\(|[^a-zA-Z])' 'specialChars\(1\)'; do
  [[ "$PWPOL" =~ $rx ]] || fail "passwordPolicy missing rule matching /$rx/ (got: '$PWPOL')"
done
ok "password policy: length(12) + notUsername + notEmail + specialChars(1)"

DEV_ONLY_USER_PASSWORDS_ALLOWED_IN_THE_COMMITTED_EXPORT=(
  'sysadmin-dev-2026!'
  'clubadmin1-dev-2026!'
  'clubadmin2-dev-2026!'
  'clubadmin3-dev-2026!'
  'clubadmin4-dev-2026!'
  'clubadmin-c2-dev-2026!'
  'pilot1-dev-2026!'
  'pilot-c2-dev-2026!'
  'pilot-empty1-dev-2026!'
  'alpenflight-demo-seat-dev-2026!'
)
DEV_ONLY_CLIENT_SECRETS_ALLOWED_IN_THE_COMMITTED_EXPORT=(
  'alpenflight-backend-admin-dev-secret'
  'alpenflight-proffix-dev-secret'
)

ALLOWED_PASSWORDS_JSON=$(jq -n --args '$ARGS.positional' "${DEV_ONLY_USER_PASSWORDS_ALLOWED_IN_THE_COMMITTED_EXPORT[@]}")
FOREIGN_USER_PASSWORDS=$(jq -r --argjson allowed "$ALLOWED_PASSWORDS_JSON" '
  [ .users[]? as $u
    | ($u.credentials // [])[]
    | select((.value // "") | IN($allowed[]) | not)
    | $u.username
  ] | join(", ")
' "$EXPORT")
[[ -z "$FOREIGN_USER_PASSWORDS" ]] || fail "user password outside the dev allow-set, on seed user(s): $FOREIGN_USER_PASSWORDS.
    This message hides the value, because a build log is not a safe place for a credential.
    Warning: a real password must never enter the committed realm export. Every seed credential in
    this file is a dev-only placeholder that the realm password policy accepts.
    Rotate the value back to a dev-only placeholder, or add the new placeholder to
    DEV_ONLY_USER_PASSWORDS_ALLOWED_IN_THE_COMMITTED_EXPORT in check-realm-shape.sh.
    Add the value only after you confirm that no real system accepts it."
ok "user passwords: ${#DEV_ONLY_USER_PASSWORDS_ALLOWED_IN_THE_COMMITTED_EXPORT[@]} dev-only placeholders, no value outside the allow-set"

ALLOWED_CLIENT_SECRETS_JSON=$(jq -n --args '$ARGS.positional' "${DEV_ONLY_CLIENT_SECRETS_ALLOWED_IN_THE_COMMITTED_EXPORT[@]}")
FOREIGN_CLIENT_SECRETS=$(jq -r --argjson allowed "$ALLOWED_CLIENT_SECRETS_JSON" '
  [ .clients[]?
    | select(.secret != null)
    | select(.secret | IN($allowed[]) | not)
    | .clientId
  ] | join(", ")
' "$EXPORT")
[[ -z "$FOREIGN_CLIENT_SECRETS" ]] || fail "client secret outside the dev allow-set, on client(s): $FOREIGN_CLIENT_SECRETS.
    This message hides the value, because a build log is not a safe place for a credential.
    Warning: a real client secret must never enter the committed realm export. A Keycloak export masks
    an unknown secret as a row of asterisks, so a masked value also fails here.
    Restore the dev-only placeholder in normalize-realm-export.sh, or add the new placeholder to
    DEV_ONLY_CLIENT_SECRETS_ALLOWED_IN_THE_COMMITTED_EXPORT in check-realm-shape.sh.
    Add the value only after you confirm that no real system accepts it."
ok "client secrets: ${#DEV_ONLY_CLIENT_SECRETS_ALLOWED_IN_THE_COMMITTED_EXPORT[@]} dev-only placeholders, no value outside the allow-set"

[[ $(jq -e '.smtpServer | length > 0' "$EXPORT") == "true" ]] || fail "smtpServer block must be non-empty (S-134 verify-email)"
for key in host port from user password auth starttls; do
  VAL=$(jq -r --arg k "$key" '.smtpServer[$k] // ""' "$EXPORT")
  [[ "$VAL" == '${KEYCLOAK_'*'}' ]] || fail "smtpServer.$key must be a \${KEYCLOAK_...} substitution (got: '$VAL') — no real SMTP secrets in source"
done
ok "smtpServer: env-substituted host/port/from/user/password/auth/starttls"

GOOGLE=$(jq '.identityProviders[]? | select(.alias=="google")' "$EXPORT")
[[ -n "$GOOGLE" ]] || fail "Google identity provider missing (alias=google)"
[[ $(jq -r '.providerId' <<<"$GOOGLE") == "google" ]] || fail "Google IdP providerId must be 'google'"
[[ $(jq -r '.enabled' <<<"$GOOGLE") == "true" ]] || fail "Google IdP must be enabled"
[[ $(jq -r '.trustEmail' <<<"$GOOGLE") == "false" ]] || fail "Google IdP trustEmail must be false (S-134 hijack-vector guard: verify-mail challenge stays in the flow)"
[[ $(jq -r '.firstBrokerLoginFlowAlias' <<<"$GOOGLE") == "first broker login" ]] || fail "Google IdP must reference the stock 'first broker login' flow (no accidental custom-flow swap)"

G_CLIENT_ID=$(jq -r '.config.clientId // ""' <<<"$GOOGLE")
G_CLIENT_SECRET=$(jq -r '.config.clientSecret // ""' <<<"$GOOGLE")
[[ "$G_CLIENT_ID" == '${KEYCLOAK_GOOGLE_CLIENT_ID}' ]] || fail "Google IdP config.clientId must be \${KEYCLOAK_GOOGLE_CLIENT_ID} (got: '$G_CLIENT_ID')"
[[ "$G_CLIENT_SECRET" == '${KEYCLOAK_GOOGLE_CLIENT_SECRET}' ]] || fail "Google IdP config.clientSecret must be \${KEYCLOAK_GOOGLE_CLIENT_SECRET} (got: '$G_CLIENT_SECRET' — looks like a real secret leaked into the export)"

IDP_TOKEN_OVERRIDES=$(jq -r '
  .config
  | to_entries
  | map(select(.key | test("Lifespan|Token|refresh|access"; "i")))
  | length
' <<<"$GOOGLE")
[[ "$IDP_TOKEN_OVERRIDES" == "0" ]] || fail "Google IdP config carries per-IdP token override(s) — token policy must stay realm-level (ADR 0007)"
ok "Google IdP: providerId=google, trustEmail=false, stock first-broker-login, env-substituted secrets, no per-IdP token overrides"

echo "PASS"
