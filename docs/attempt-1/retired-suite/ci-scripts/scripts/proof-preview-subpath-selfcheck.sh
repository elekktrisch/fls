#!/usr/bin/env bash
# J-25 T-03 (AC5) — assert the ref→preview-subpath derivation is correct.
#
# Runs the SAME sanitize rule that ci.yml's `Compute proof-preview destination`
# step (id: preview) and proof-preview-reap.yml's reaper use, against sample
# refs, and asserts the expected subpath. This exists so AC5 (reaping the right
# subpath) isn't only exercised by a real PR-close round-trip — and so any drift
# in the sed rule is caught in CI rather than silently deleting the wrong dir.
#
# SANITIZE RULE — keep in sync with:
#   - .github/workflows/ci.yml  (preview step)
#   - .github/workflows/proof-preview-reap.yml  (reap step)
# All three sed expressions MUST stay byte-for-byte identical.
set -euo pipefail

# Derive the preview subpath from a head ref — identical to the workflow steps.
subpath() {
  local head_ref="$1"
  local sanitized
  sanitized="$(printf '%s' "$head_ref" | sed -E 's#[^A-Za-z0-9._-]#-#g')"
  printf 'alpenflight/proof-preview/%s' "$sanitized"
}

# Mirror of the reaper's blast-radius guard (proof-preview-reap.yml "Remove
# preview subpath + push" step). Given a candidate subdir, return 0 (REAPABLE)
# only if it's alpenflight/proof-preview/<safe-single-component>, else non-zero
# (REFUSED). Kept byte-for-byte in sync with the workflow's guard logic so this
# self-check actually asserts the protection rather than a private copy of it.
reapable() {
  local subdir="$1"
  local prefix="alpenflight/proof-preview/"
  local component
  case "$subdir" in
    "${prefix}"*) component="${subdir#"${prefix}"}" ;;
    *) return 1 ;;
  esac
  if [ "$component" = "." ] || [ "$component" = ".." ] \
     || [ -z "$component" ] \
     || ! printf '%s' "$component" | grep -Eq '^[A-Za-z0-9._-]+$' \
     || printf '%s' "$component" | grep -q '\.\.' \
     || ! printf '%s' "$component" | grep -Eq '[A-Za-z0-9]'; then
    return 1
  fi
  return 0
}

# sample-ref  expected-subpath
samples=(
  "integration/J-25|alpenflight/proof-preview/integration-J-25"
  "feature/foo bar|alpenflight/proof-preview/feature-foo-bar"
  "main|alpenflight/proof-preview/main"
  "release/v1.2.3|alpenflight/proof-preview/release-v1.2.3"
)

fail=0
for s in "${samples[@]}"; do
  ref="${s%%|*}"
  expected="${s##*|}"
  actual="$(subpath "$ref")"
  if [ "$actual" = "$expected" ]; then
    printf '  ok   %-22s -> %s\n' "$ref" "$actual"
  else
    printf '  FAIL %-22s -> %s (expected %s)\n' "$ref" "$actual" "$expected"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "::error::proof-preview subpath derivation self-check failed (sed rule drift?)" >&2
  exit 1
fi
echo "proof-preview subpath derivation self-check passed (${#samples[@]} sample refs)."

# --- Blast-radius guard: dangerous inputs MUST be refused -------------------
# The reaper's `git rm -r` runs against the LIVE published gh-pages site, so a
# subdir that resolves outside alpenflight/proof-preview/<component> (e.g. `..`
# resolving to the parent alpenflight/) would wipe the canonical gallery. These
# inputs are unreachable via real branch names (git rejects `..`/`.`), but the
# guard must not depend on that external invariant. Assert each dangerous input
# is REFUSED — both fed through the full sanitize→subpath pipeline AND as a
# hand-crafted subdir straight into the guard.

# Raw refs that must NOT produce a deletable subpath after derivation + guard.
danger_refs=(
  ".."        # parent-dir escape: `.` is in the allowed sed set, survives sanitize
  "."         # current-dir / cwd (would rm the checkout root)
  ""          # empty ref → empty component
  "/"         # `/`-only ref
  "../.."     # double escape
  "a/../b"    # embedded traversal
)

# Hand-crafted candidate subdirs (bypassing the sanitizer) that the guard alone
# must refuse — proving the protection isn't merely the sanitizer's side effect.
danger_subdirs=(
  "alpenflight/proof-preview/.."
  "alpenflight/proof-preview/."
  "alpenflight/proof-preview/"
  "alpenflight/proof-preview"
  "alpenflight/proof-preview/../.."
  "alpenflight/proof-preview/a/../b"
  "alpenflight"
  "alpenflight/proof"
  "/"
  ".."
)

danger_fail=0
for ref in "${danger_refs[@]}"; do
  cand="$(subpath "$ref")"
  if reapable "$cand"; then
    printf '  FAIL danger ref %-10q -> %s (REAPABLE — guard bypassed!)\n' "$ref" "$cand"
    danger_fail=1
  else
    printf '  ok   danger ref %-10q -> %s (refused)\n' "$ref" "$cand"
  fi
done
for sd in "${danger_subdirs[@]}"; do
  if reapable "$sd"; then
    printf '  FAIL danger subdir %-32q (REAPABLE — guard bypassed!)\n' "$sd"
    danger_fail=1
  else
    printf '  ok   danger subdir %-32q (refused)\n' "$sd"
  fi
done

if [ "$danger_fail" -ne 0 ]; then
  echo "::error::proof-preview blast-radius guard would let a dangerous input through" >&2
  exit 1
fi
echo "proof-preview blast-radius guard refused all ${#danger_refs[@]} danger refs + ${#danger_subdirs[@]} danger subdirs."

# Sanity: a normal sanitized subpath MUST remain reapable (guard not too strict).
for s in "${samples[@]}"; do
  expected="${s##*|}"
  if ! reapable "$expected"; then
    echo "::error::guard wrongly refused a legitimate preview subpath: ${expected}" >&2
    exit 1
  fi
done
echo "proof-preview blast-radius guard kept all ${#samples[@]} legitimate subpaths reapable."
