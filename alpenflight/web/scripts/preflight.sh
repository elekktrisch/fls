#!/usr/bin/env bash
#
# preflight — run the FULL local CI-equivalent in sequence, locally.
#
# WHY (T-43): the dev box is Alpine/musl. Playwright's bundled chromium can't
# launch under musl, so e2e/gallery checks were CI-only → slow serial round-
# trips (push → wait for CI → red → fix → push …). With the apk system chromium
# installed (`apk add chromium nss freetype harfbuzz ttf-freefont`) the same
# checks run locally. This script runs the comprehensive set — NOT a focused
# subset — failing on the FIRST red but exercising the whole suite, so a worker
# verifies locally before reporting `done` instead of round-tripping CI.
#
# It mirrors what the gate actually checks, and deliberately runs the WHOLE
# server suite (`./gradlew test`) — the focused-run blind spot that has hidden
# whole-module guards (LeakageSweepIT, arch-guards) that only fire on the full
# build.
#
# USAGE
#   scripts/preflight.sh                 # full run: backend + web + gallery + e2e
#   scripts/preflight.sh --web-only      # skip the heavy backend (fast web loop)
#   scripts/preflight.sh --no-e2e        # everything except the chromium e2e suite
#   scripts/preflight.sh --backend-only  # only ./gradlew test
#
# e2e runs ONLY if a launchable chromium is resolvable (env override or an apk
# system chromium at a probed path); otherwise the e2e stage is SKIPPED with a
# note (it completes after the operator's `apk add chromium`).
#
# Fails on the first red (set -e). Each stage prints a banner so the failing
# stage is obvious in the log.

set -euo pipefail

# ── locate the dirs (script lives in alpenflight/web/scripts/) ───────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVER_DIR="$(cd "${WEB_DIR}/../server" && pwd)"

# ── flags ────────────────────────────────────────────────────────────────────
RUN_BACKEND=1
RUN_WEB=1
RUN_GALLERY=1
RUN_E2E=1

for arg in "$@"; do
  case "$arg" in
    --web-only)     RUN_BACKEND=0 ;;
    --backend-only) RUN_WEB=0; RUN_GALLERY=0; RUN_E2E=0 ;;
    --no-e2e)       RUN_E2E=0 ;;
    -h|--help)      sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "preflight: unknown flag '$arg' (see --help)" >&2; exit 2 ;;
  esac
done

banner() { printf '\n\033[1;36m━━ preflight: %s ━━\033[0m\n' "$1"; }
note()   { printf '\033[0;33m   %s\033[0m\n' "$1"; }

# ── 1. backend — the WHOLE server suite ──────────────────────────────────────
if [ "$RUN_BACKEND" = 1 ]; then
  banner "backend — ./gradlew test (whole server suite)"
  # Source DATASOURCE_* per the repo convention (JPA-booting tasks need them).
  # shellcheck disable=SC1090
  [ -f "${HOME}/.bashrc" ] && source "${HOME}/.bashrc" || true
  ( cd "${SERVER_DIR}" && ./gradlew test --no-daemon --console=plain )
fi

# ── 2. web — lint + tsc + build + generate-api drift ─────────────────────────
if [ "$RUN_WEB" = 1 ]; then
  banner "web — lint"
  ( cd "${WEB_DIR}" && pnpm lint )

  banner "web — tsc (typecheck, no emit)"
  ( cd "${WEB_DIR}" && pnpm exec tsc -p tsconfig.json --noEmit )

  banner "web — build"
  ( cd "${WEB_DIR}" && pnpm build )

  banner "web — generate-api + drift check"
  ( cd "${WEB_DIR}" && pnpm generate-api )
  ( cd "${WEB_DIR}" && git diff --exit-code src/app/api/generated/ ) || {
    note "generated API client drifted — commit the regenerated client."
    exit 1
  }
fi

# ── 3. proof-gallery — generator unit tests + browserless link check ──────────
if [ "$RUN_GALLERY" = 1 ]; then
  banner "proof-gallery — generator unit tests (vitest)"
  ( cd "${WEB_DIR}" && pnpm test:scripts )

  banner "proof-gallery — browserless link check (proof-gallery-links project)"
  ( cd "${WEB_DIR}" && GALLERY_LINKS_ONLY=1 pnpm exec playwright test \
      --config=e2e/playwright.config.ts --project=proof-gallery-links )
fi

# ── 4. e2e — mock-auth chromium suite (only if a chromium is launchable) ──────
if [ "$RUN_E2E" = 1 ]; then
  banner "e2e — mock-auth chromium suite"
  CHROMIUM_PATH=""
  if [ -n "${PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH:-}" ]; then
    CHROMIUM_PATH="${PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}"
  else
    for p in /usr/lib/chromium/chromium /usr/bin/chromium /usr/bin/chromium-browser; do
      [ -x "$p" ] && { CHROMIUM_PATH="$p"; break; }
    done
  fi
  if [ -z "$CHROMIUM_PATH" ]; then
    note "SKIPPED: no launchable chromium found (apk add chromium … pending)."
    note "Set PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH or install the apk package."
  else
    note "using chromium: ${CHROMIUM_PATH}"
    ( cd "${WEB_DIR}" && pnpm e2e )
  fi
fi

banner "ALL GREEN"
