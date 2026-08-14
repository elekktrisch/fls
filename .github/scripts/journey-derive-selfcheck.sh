#!/usr/bin/env bash
# Assert the journey-file lookup that ci.yml's proof-spec + mock-filter derives
# depend on.
#
# WHY this exists: when the lookup misses, ci.yml does not go red — it falls back
# to the J-0 Locations baseline, proves a DIFFERENT journey, drops the gallery's
# `--journey-under-work` flag and skips the deployed-journey guard, and `required`
# reports GREEN. A silent miss is therefore indistinguishable from a pass by
# looking at the gate, which is exactly how J-17 shipped a baseline proof and an
# "unknown" gallery page under 13/13 green jobs. The rule needs a test that reds.
#
# LOOKUP RULE — keep in sync with .github/workflows/ci.yml, both the
# "Derive journey proof spec" and "Derive journey mock-e2e filter" steps.
set -euo pipefail

# Resolve a journey id to its journey file. Selects by frontmatter `id:` rather
# than glob order, because a sibling scratch doc (e.g. J-6b-oracle.md) also
# matches `${jid}-*.md` and would win `head -1` alphabetically.
# Searches BOTH dirs: /do-ship close-out `git mv`s the file to implemented/ in a
# commit ON THE BRANCH, so a stories/-only lookup blinds every later push.
journey_file() {
  local jid="$1"
  grep -lE "^id:[[:space:]]*${jid}([[:space:]]|$)" \
    docs/modernization/stories/${jid}-*.md \
    docs/modernization/stories/implemented/${jid}-*.md \
    2>/dev/null | head -1 || true
}

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/docs/modernization/stories/implemented"
cd "$fixture"

printf 'id: J-40\nparity_test: e2e/tests/real-idp/in-flight.spec.ts\n' \
  > docs/modernization/stories/J-40-in-flight.md
printf 'id: J-41\nparity_test: e2e/tests/real-idp/closed-out.spec.ts\n' \
  > docs/modernization/stories/implemented/J-41-closed-out.md
# No frontmatter — must never be selected as J-42's journey file.
printf '# scratch notes for J-42\n' \
  > docs/modernization/stories/J-42-a-oracle.md
printf 'id: J-42\nparity_test: e2e/tests/real-idp/scratch-sibling.spec.ts\n' \
  > docs/modernization/stories/J-42-b-journey.md

fail=0
check() { # $1 case  $2 expected  $3 actual
  if [ "$2" = "$3" ]; then
    printf '  ok    %-46s %s\n' "$1" "${3:-<none>}"
  else
    printf '  FAIL  %-46s expected %-42s got %s\n' "$1" "${2:-<none>}" "${3:-<none>}"
    fail=1
  fi
}

check "in-flight journey (stories/)" \
  "docs/modernization/stories/J-40-in-flight.md" "$(journey_file J-40)"

# The J-17 regression: closed out mid-branch, so the file only exists here.
check "closed-out journey (stories/implemented/)" \
  "docs/modernization/stories/implemented/J-41-closed-out.md" "$(journey_file J-41)"

check "scratch sibling never wins on glob order" \
  "docs/modernization/stories/J-42-b-journey.md" "$(journey_file J-42)"

# Underivable must stay EMPTY so ci.yml hard-fails rather than substituting the
# baseline and calling that green.
check "no journey file anywhere" "" "$(journey_file J-99)"

[ "$fail" = "0" ] || { echo "journey-derive-selfcheck: FAILED"; exit 1; }
echo "journey-derive-selfcheck: ok"
