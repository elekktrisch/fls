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
