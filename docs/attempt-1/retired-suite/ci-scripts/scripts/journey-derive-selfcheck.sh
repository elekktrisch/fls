#!/usr/bin/env bash
set -euo pipefail

DERIVE_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/derive-journey-lane.sh"

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/docs/modernization/stories/implemented" \
  "$fixture/alpenflight/web/e2e/tests/real-idp" \
  "$fixture/alpenflight/web/e2e/tests/profile" \
  "$fixture/alpenflight/web/e2e/tests/landing" \
  "$fixture/alpenflight/web/e2e/tests/shell" \
  "$fixture/alpenflight/web/e2e/tests/stubbed"
cd "$fixture"

active_spec() { printf "test('proves something', async () => {});\n" > "$1"; }
stub_spec() { printf "test.fixme('not thickened yet', async () => {});\n" > "$1"; }

active_spec alpenflight/web/e2e/tests/real-idp/a.spec.ts
active_spec alpenflight/web/e2e/tests/real-idp/b.spec.ts
active_spec alpenflight/web/e2e/tests/real-idp/c.spec.ts
stub_spec alpenflight/web/e2e/tests/real-idp/not-thickened.spec.ts
active_spec alpenflight/web/e2e/tests/profile/self-edit.spec.ts
active_spec alpenflight/web/e2e/tests/landing/landing.spec.ts
active_spec alpenflight/web/e2e/tests/shell/nav-bar.spec.ts
stub_spec alpenflight/web/e2e/tests/stubbed/stubbed.spec.ts

journey() { # $1 id  $2 frontmatter body
  printf 'id: %s\n%s\n' "$1" "$2" > "docs/modernization/stories/$1-fixture.md"
}

journey J-40 'parity_test: alpenflight/web/e2e/tests/real-idp/a.spec.ts'
journey J-41 'parity_test: alpenflight/web/e2e/tests/real-idp/a.spec.ts + alpenflight/web/e2e/tests/real-idp/b.spec.ts'
journey J-42 'parity_test: e2e/tests/real-idp/a.spec.ts + e2e/tests/real-idp/b.spec.ts, e2e/tests/real-idp/c.spec.ts'
journey J-43 'parity_test: e2e/tests/real-idp/a.spec.ts, e2e/tests/real-idp/b.spec.ts'
journey J-44 'parity_test: e2e/tests/real-idp/a.spec.ts; e2e/tests/real-idp/b.spec.ts'
printf 'id: J-45\nparity_test:\n  - e2e/tests/real-idp/a.spec.ts\n  - e2e/tests/real-idp/b.spec.ts\nadr_refs: [0022]\n' \
  > docs/modernization/stories/J-45-fixture.md
journey J-46 'parity_test: e2e/tests/real-idp/a.spec.ts + e2e/tests/profile/self-edit.spec.ts'
journey J-47 'parity_test: e2e/tests/profile/self-edit.spec.ts + e2e/tests/real-idp/a.spec.ts'
journey J-48 'parity_test: e2e/tests/real-idp/a.spec.ts + e2e/tests/real-idp/not-thickened.spec.ts'
journey J-49 'parity_test: e2e/tests/profile/self-edit.spec.ts'
journey J-50 'parity_test: none'
journey J-51 'parity_test: e2e/tests/real-idp/a.spec.ts (thickened at T-35, pairs with e2e/tests/real-idp/gone.spec.ts)'
journey J-52 'parity_test: e2e/tests/real-idp/a.spec.ts   # also e2e/tests/real-idp/b.spec.ts'
journey J-53 'parity_test: alpenflight/web/e2e/tests/real-idp/{a,b}.spec.ts'
journey J-54 'parity_test: e2e/tests/real-idp/never-authored.spec.ts'

journey J-60 'mock_test: alpenflight/web/e2e/tests/landing/'
journey J-61 'mock_test: alpenflight/web/e2e/tests/landing/ + alpenflight/web/e2e/tests/shell/'
journey J-62 'mock_test: alpenflight/web/e2e/tests/landing/, alpenflight/web/e2e/tests/shell/'
journey J-63 'mock_test: alpenflight/web/e2e/tests/(landing/landing|shell/nav-bar)'
journey J-64 'mock_test: alpenflight/web/e2e/tests/landing/ + alpenflight/web/e2e/tests/no-such-dir/'
journey J-65 'mock_test: alpenflight/web/e2e/tests/stubbed/'
journey J-66 'mock_test:   # real-idp-only journey, owns no mock-auth screen'
journey J-67 'mock_test: alpenflight/web/e2e/tests/(landing/landing)'
journey J-68 'mock_test: alpenflight/web/e2e/tests/(landing/landing|no-such-feature/spec)'

printf 'id: J-70\nparity_test: e2e/tests/real-idp/a.spec.ts\n' \
  > docs/modernization/stories/implemented/J-70-closed-out.md
printf '# scratch notes for J-71, mentions e2e/tests/real-idp/b.spec.ts\n' \
  > docs/modernization/stories/J-71-a-oracle.md
printf 'id: J-71\nparity_test: e2e/tests/real-idp/a.spec.ts\n' \
  > docs/modernization/stories/J-71-b-journey.md

fail=0
derive_status=0
derive_log=''
derive_outputs=''

run_derive() { # $1 lane  $2 branch
  derive_outputs="$(mktemp)"
  derive_log="$(mktemp)"
  set +e
  GITHUB_OUTPUT="$derive_outputs" bash "$DERIVE_SCRIPT" "$1" "$2" > "$derive_log" 2>&1
  derive_status=$?
  set -e
}

output_value_of() { sed -nE "s/^$1=(.*)$/\1/p" "$derive_outputs" | tail -1; }

check() { # $1 case  $2 expected  $3 actual
  if [ "$2" = "$3" ]; then
    printf '  ok    %-58s %s\n' "$1" "${3:-<none>}"
  else
    printf '  FAIL  %-58s expected [%s] got [%s]\n' "$1" "$2" "$3"
    fail=1
  fi
}

check_proof() { # $1 case  $2 journey id  $3 expected spec list  $4 expected is_baseline
  run_derive proof "integration/$2"
  check "$1" "$3|$4" "$(output_value_of spec)|$(output_value_of is_baseline)"
}

check_mock() { # $1 case  $2 journey id  $3 expected filter  $4 expected is_full
  run_derive mock "integration/$2"
  check "$1" "$3|$4" "$(output_value_of filter)|$(output_value_of is_full)"
}

echo "── proof lane: every declared runnable spec reaches the per-push proof job ──"
check_proof 'one spec' J-40 'e2e/tests/real-idp/a.spec.ts' false
check_proof 'two specs separated by a plus' J-41 'e2e/tests/real-idp/a.spec.ts e2e/tests/real-idp/b.spec.ts' false
check_proof 'three specs, plus and comma mixed' J-42 'e2e/tests/real-idp/a.spec.ts e2e/tests/real-idp/b.spec.ts e2e/tests/real-idp/c.spec.ts' false
check_proof 'two specs separated by a comma' J-43 'e2e/tests/real-idp/a.spec.ts e2e/tests/real-idp/b.spec.ts' false
check_proof 'two specs separated by a semicolon' J-44 'e2e/tests/real-idp/a.spec.ts e2e/tests/real-idp/b.spec.ts' false
check_proof 'two specs on indented continuation lines' J-45 'e2e/tests/real-idp/a.spec.ts e2e/tests/real-idp/b.spec.ts' false
check_proof 'runnable first, then a non-clean-seed spec' J-46 'e2e/tests/real-idp/a.spec.ts' false
check_proof 'non-clean-seed first, then a runnable spec' J-47 'e2e/tests/real-idp/a.spec.ts' false
check_proof 'runnable plus a still-stubbed spec' J-48 'e2e/tests/real-idp/a.spec.ts' false
check_proof 'every declared spec is non-clean-seed' J-49 'e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts' true
check_proof 'parity_test declares no spec' J-50 'e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts' true
check_proof 'prose in parentheses declares no spec' J-51 'e2e/tests/real-idp/a.spec.ts' false
check_proof 'a # comment declares no spec' J-52 'e2e/tests/real-idp/a.spec.ts' false
check_proof 'a brace expansion declares no spec' J-53 'e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts' true
check_proof 'a declared spec that is not on disk' J-54 'e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts' true
check_proof 'journey file closed out to implemented/' J-70 'e2e/tests/real-idp/a.spec.ts' false
check_proof 'a scratch sibling never wins on glob order' J-71 'e2e/tests/real-idp/a.spec.ts' false

run_derive proof 'main'
check 'a non-integration branch takes the J-0 baseline' \
  'e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts|J-0|true' \
  "$(output_value_of spec)|$(output_value_of journey)|$(output_value_of is_baseline)"

run_derive proof 'integration/J-99'
check 'no journey file is a hard failure, never the baseline' '1|' \
  "${derive_status}|$(output_value_of spec)"

echo "── proof lane: a dropped declaration is visible, and it names the limit ──"
run_derive proof 'integration/J-46'
if grep -q '::warning title=Declared proof spec dropped' "$derive_log" \
  && grep -q 'e2e/tests/profile/self-edit.spec.ts' "$derive_log" \
  && grep -q 'keeps only a token that ends in .spec.ts' "$derive_log"; then
  printf '  ok    %-58s %s\n' 'dropped spec warns and states the read limit' 'warning present'
else
  printf '  FAIL  %-58s %s\n' 'dropped spec warns and states the read limit' 'no warning naming the limit'
  fail=1
fi

echo "── mock lane: every declared filter token reaches the per-push mock job ──"
check_mock 'one directory token' J-60 'e2e/tests/landing/' false
check_mock 'two directory tokens separated by a plus' J-61 'e2e/tests/landing/ e2e/tests/shell/' false
check_mock 'two directory tokens separated by a comma' J-62 'e2e/tests/landing/ e2e/tests/shell/' false
check_mock 'an alternation regex stays one token' J-63 'e2e/tests/(landing/landing|shell/nav-bar)' false
check_mock 'one token resolves, one does not' J-64 '' true
check_mock 'every selected spec is still stubbed' J-65 '' true
check_mock 'mock_test declares no token' J-66 '' true
check_mock 'an alternation with one branch keeps that branch' J-67 'e2e/tests/(landing/landing)' false
check_mock 'the LAST alternation branch is read too' J-68 '' true

run_derive mock 'main'
check 'a non-integration branch runs the full chromium suite' '|true' \
  "$(output_value_of filter)|$(output_value_of is_full)"

[ "$fail" = "0" ] || { echo "journey-derive-selfcheck: FAILED"; exit 1; }
echo "journey-derive-selfcheck: ok"
