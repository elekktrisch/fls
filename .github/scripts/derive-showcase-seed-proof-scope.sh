#!/usr/bin/env bash
set -euo pipefail

DASHBOARD_SHOWCASE_PROOF_SPEC='e2e/tests/real-idp/start-dashboard.spec.ts'
PROFILE_SHOWCASE_PROOF_SPEC='e2e/tests/profile/self-edit.spec.ts'

journey_id_of_integration_branch() {
  printf '%s' "${1:-}" | sed -nE 's#^integration/(J-[0-9]+[a-z]?)$#\1#p'
}

journey_file_declaring_id() {
  local journey_id="$1"
  grep -lE "^id:[[:space:]]*${journey_id}([[:space:]]|$)" \
    docs/modernization/stories/"${journey_id}"-*.md \
    docs/modernization/stories/implemented/"${journey_id}"-*.md \
    2>/dev/null | head -1 || true
}

parity_spec_of_journey_file() {
  local journey_file="$1"
  local raw
  raw="$(grep -m1 -E '^parity_test:' "$journey_file" 2>/dev/null || true)"
  printf '%s' "$raw" \
    | sed -E 's/^parity_test:[[:space:]]*//' \
    | sed -E 's/[[:space:](;].*$//' \
    | sed -E 's#^alpenflight/web/##'
}

emit_scope() {
  local dashboard_runs="$1" profile_runs="$2"
  printf 'dashboard_showcase_proof_runs_per_push=%s\n' "$dashboard_runs"
  printf 'profile_showcase_proof_runs_per_push=%s\n' "$profile_runs"
  printf '── showcase-seed real-idp per-push scope: dashboard=%s profile=%s ──\n' \
    "$dashboard_runs" "$profile_runs" >&2
}

showcase_seed_proof_scope_for_branch() {
  local branch="${1:-}"
  local journey_id journey_file parity_spec
  journey_id="$(journey_id_of_integration_branch "$branch")"
  if [ -z "$journey_id" ]; then
    printf 'branch %s names no journey-under-work, so every showcase-seed real-idp proof runs\n' \
      "'${branch}'" >&2
    emit_scope true true
    return 0
  fi
  journey_file="$(journey_file_declaring_id "$journey_id")"
  if [ -z "$journey_file" ] || [ ! -f "$journey_file" ]; then
    printf 'no journey file declares id %s, so every showcase-seed real-idp proof runs (fail-safe)\n' \
      "$journey_id" >&2
    emit_scope true true
    return 0
  fi
  parity_spec="$(parity_spec_of_journey_file "$journey_file")"
  printf '%s parity_test first token → %s\n' "$journey_id" "'${parity_spec}'" >&2
  local dashboard_runs=false profile_runs=false
  if [ "$parity_spec" = "$DASHBOARD_SHOWCASE_PROOF_SPEC" ]; then
    dashboard_runs=true
  fi
  if [ "$parity_spec" = "$PROFILE_SHOWCASE_PROOF_SPEC" ]; then
    profile_runs=true
  fi
  if [ "$dashboard_runs" = false ] && [ "$profile_runs" = false ]; then
    printf '%s owns neither showcase-seed proof; both run nightly and at the do-ship gate, never per push\n' \
      "$journey_id" >&2
  fi
  emit_scope "$dashboard_runs" "$profile_runs"
}

selftest() {
  local fixture
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' RETURN
  mkdir -p "$fixture/docs/modernization/stories/implemented"
  cd "$fixture"

  printf 'id: J-70\nparity_test: alpenflight/web/e2e/tests/real-idp/start-dashboard.spec.ts\n' \
    > docs/modernization/stories/J-70-owns-dashboard-showcase.md
  printf 'id: J-71\nparity_test: alpenflight/web/e2e/tests/profile/self-edit.spec.ts   trailing prose\n' \
    > docs/modernization/stories/implemented/J-71-owns-profile-showcase.md
  printf 'id: J-72\nparity_test: alpenflight/web/e2e/tests/real-idp/audit-log-two-club.spec.ts\n' \
    > docs/modernization/stories/J-72-owns-a-clean-seed-spec.md
  printf 'id: J-73\ntitle: no parity_test at all\n' \
    > docs/modernization/stories/J-73-no-parity-test.md

  local fail=0
  check() {
    local case_name="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
      printf '  ok    %-52s %s\n' "$case_name" "$actual"
    else
      printf '  FAIL  %-52s expected %-28s got %s\n' "$case_name" "$expected" "$actual"
      fail=1
    fi
  }
  scope_of() {
    showcase_seed_proof_scope_for_branch "$1" 2>/dev/null \
      | grep -E '^(dashboard|profile)_showcase_proof_runs_per_push=' \
      | sed -E 's/^[a-z_]+=//' \
      | paste -sd' ' -
  }

  check "main push runs both showcase proofs" "true true" "$(scope_of main)"
  check "non-journey branch runs both showcase proofs" "true true" "$(scope_of feature/anything)"
  check "unparseable branch runs both showcase proofs" "true true" "$(scope_of '')"
  check "journey owning the dashboard showcase spec" "true false" "$(scope_of integration/J-70)"
  check "journey owning the profile showcase spec (implemented/)" "false true" "$(scope_of integration/J-71)"
  check "journey owning a clean-seed real-idp spec" "false false" "$(scope_of integration/J-72)"
  check "journey without parity_test owns no showcase proof" "false false" "$(scope_of integration/J-73)"
  check "integration branch without a journey file (fail-safe)" "true true" "$(scope_of integration/J-99)"

  if [ "$fail" != 0 ]; then
    echo "::error::derive-showcase-seed-proof-scope selftest failed" >&2
    return 1
  fi
  echo "derive-showcase-seed-proof-scope selftest: ok"
}

if [ "${1:-}" = '--selftest' ]; then
  selftest
else
  showcase_seed_proof_scope_for_branch "${1:-}"
fi
