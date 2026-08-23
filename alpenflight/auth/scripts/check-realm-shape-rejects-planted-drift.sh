#!/usr/bin/env bash

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="${SCRIPTS_DIR}/check-realm-shape.sh"
COMMITTED_EXPORT="${SCRIPTS_DIR}/../realm-export.json"

[[ -f "$GUARD" ]] || { echo "FAIL: $GUARD missing"; exit 1; }
[[ -f "$COMMITTED_EXPORT" ]] || { echo "FAIL: $COMMITTED_EXPORT missing"; exit 1; }

PLANTED_REALMS_DIR="$(mktemp -d)"
trap 'rm -rf "$PLANTED_REALMS_DIR"' EXIT

selftest_failed=0

expect_guard_rejects_planted_drift() {
  local case_name="$1" planted_realm="$2" expected_failure_message="$3"
  local guard_output guard_exit=0
  guard_output="$(bash "$GUARD" "$planted_realm" 2>&1)" || guard_exit=$?
  if [[ "$guard_exit" -eq 0 ]]; then
    printf '  FAIL  %-42s the guard exited 0 on a planted drift\n' "$case_name"
    selftest_failed=1
    return
  fi
  if [[ "$guard_output" != *"$expected_failure_message"* ]]; then
    printf '  FAIL  %-42s the guard exited %s for the wrong reason:\n%s\n' \
      "$case_name" "$guard_exit" "$guard_output"
    selftest_failed=1
    return
  fi
  printf '  ok    %-42s the guard exited %s and named the drift\n' "$case_name" "$guard_exit"
}

expect_guard_accepts_committed_realm() {
  local guard_output guard_exit=0
  guard_output="$(bash "$GUARD" "$COMMITTED_EXPORT" 2>&1)" || guard_exit=$?
  if [[ "$guard_exit" -ne 0 ]]; then
    printf '  FAIL  %-42s the guard exited %s on the committed export:\n%s\n' \
      "committed realm-export.json" "$guard_exit" "$guard_output"
    selftest_failed=1
    return
  fi
  printf '  ok    %-42s the guard exited 0\n' "committed realm-export.json"
}

PASSWORD_NO_DEV_SEED_USER_MAY_CARRY='Tr0ub4dor&3-outside-the-allow-set'
CLIENT_SECRET_NO_DEV_CLIENT_MAY_CARRY='sk-live-outside-the-allow-set'

REALM_WITH_FOREIGN_USER_PASSWORD="${PLANTED_REALMS_DIR}/realm-with-foreign-user-password.json"
REALM_WITH_FOREIGN_CLIENT_SECRET="${PLANTED_REALMS_DIR}/realm-with-foreign-client-secret.json"
REALM_WITH_MASKED_CLIENT_SECRET="${PLANTED_REALMS_DIR}/realm-with-masked-client-secret.json"
REALM_WITH_PASSWORD_RECOVERY_OFF="${PLANTED_REALMS_DIR}/realm-with-password-recovery-off.json"
REALM_WITH_SEAT_IDENTITY_FIELD_NULLED="${PLANTED_REALMS_DIR}/realm-with-seat-identity-field-nulled.json"
REALM_WITH_SEAT_ON_A_FOREIGN_CLUB="${PLANTED_REALMS_DIR}/realm-with-seat-on-a-foreign-club.json"
REALM_WITH_A_SEAT_PRINCIPAL_DROPPED="${PLANTED_REALMS_DIR}/realm-with-a-seat-principal-dropped.json"
REALM_WITH_A_SEAT_ROLE_REVOKED="${PLANTED_REALMS_DIR}/realm-with-a-seat-role-revoked.json"

jq --arg planted "$PASSWORD_NO_DEV_SEED_USER_MAY_CARRY" '
  .users |= map(
    if .username == "pilot1"
    then .credentials = [{ "temporary": false, "type": "password", "value": $planted }]
    else . end
  )
' "$COMMITTED_EXPORT" > "$REALM_WITH_FOREIGN_USER_PASSWORD"

jq --arg planted "$CLIENT_SECRET_NO_DEV_CLIENT_MAY_CARRY" '
  .clients |= map(if .clientId == "alpenflight-proffix" then .secret = $planted else . end)
' "$COMMITTED_EXPORT" > "$REALM_WITH_FOREIGN_CLIENT_SECRET"

jq '
  .clients |= map(if .clientId == "alpenflight-proffix" then .secret = "**********" else . end)
' "$COMMITTED_EXPORT" > "$REALM_WITH_MASKED_CLIENT_SECRET"

jq '.resetPasswordAllowed = false' "$COMMITTED_EXPORT" > "$REALM_WITH_PASSWORD_RECOVERY_OFF"

jq '
  .users |= map(if .username == "demo3" then .firstName = "" else . end)
' "$COMMITTED_EXPORT" > "$REALM_WITH_SEAT_IDENTITY_FIELD_NULLED"

jq '
  (.users[] | select(.username == "demo5") | .attributes.clubId) as $foreign
  | .users |= map(if .username == "demo4" then .attributes.clubId = $foreign else . end)
' "$COMMITTED_EXPORT" > "$REALM_WITH_SEAT_ON_A_FOREIGN_CLUB"

jq '.users |= map(select(.username != "demo7"))' "$COMMITTED_EXPORT" \
  > "$REALM_WITH_A_SEAT_PRINCIPAL_DROPPED"

jq '
  .users |= map(
    if .username == "demo8"
    then .realmRoles = ["default-roles-alpenflight"]
    else . end
  )
' "$COMMITTED_EXPORT" > "$REALM_WITH_A_SEAT_ROLE_REVOKED"

echo "checking that check-realm-shape.sh rejects a planted drift in the committed realm export"

expect_guard_rejects_planted_drift \
  "planted user password" \
  "$REALM_WITH_FOREIGN_USER_PASSWORD" \
  "user password outside the dev allow-set"

expect_guard_rejects_planted_drift \
  "planted client secret" \
  "$REALM_WITH_FOREIGN_CLIENT_SECRET" \
  "client secret outside the dev allow-set"

expect_guard_rejects_planted_drift \
  "export-masked client secret" \
  "$REALM_WITH_MASKED_CLIENT_SECRET" \
  "client secret outside the dev allow-set"

expect_guard_rejects_planted_drift \
  "password recovery switched off" \
  "$REALM_WITH_PASSWORD_RECOVERY_OFF" \
  "resetPasswordAllowed must be true"

expect_guard_rejects_planted_drift \
  "demo seat identity field nulled" \
  "$REALM_WITH_SEAT_IDENTITY_FIELD_NULLED" \
  "demo3.firstName is empty"

expect_guard_rejects_planted_drift \
  "demo seat bound to a foreign club" \
  "$REALM_WITH_SEAT_ON_A_FOREIGN_CLUB" \
  "but V62__demo_seat_pool.sql binds seat 4 to club"

expect_guard_rejects_planted_drift \
  "demo seat principal dropped" \
  "$REALM_WITH_A_SEAT_PRINCIPAL_DROPPED" \
  "demo seat principals, but V62__demo_seat_pool.sql provisions"

expect_guard_rejects_planted_drift \
  "demo seat role revoked" \
  "$REALM_WITH_A_SEAT_ROLE_REVOKED" \
  "demo8 does not carry the realm role CLUB_ADMINISTRATOR"

expect_guard_accepts_committed_realm

[[ "$selftest_failed" -eq 0 ]] || {
  echo "FAIL: check-realm-shape.sh does not reject every planted drift"
  exit 1
}
echo "PASS"
