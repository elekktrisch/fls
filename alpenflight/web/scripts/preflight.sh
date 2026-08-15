#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVER_DIR="$(cd "${WEB_DIR}/../server" && pwd)"

usage() {
  cat <<'USAGE'
preflight — the local CI-equivalent, stage by stage, failing on the first red.

  scripts/preflight.sh                 backend + web + gallery + e2e
  scripts/preflight.sh --web-only      skip ./gradlew test
  scripts/preflight.sh --no-e2e        everything except the chromium e2e suite
  scripts/preflight.sh --backend-only  only ./gradlew test

The e2e stage is skipped (not failed) when no launchable chromium is resolvable
via PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH or a probed apk system chromium.
USAGE
}

RUN_BACKEND=1
RUN_WEB=1
RUN_GALLERY=1
RUN_E2E=1

for arg in "$@"; do
  case "$arg" in
    --web-only)     RUN_BACKEND=0 ;;
    --backend-only) RUN_WEB=0; RUN_GALLERY=0; RUN_E2E=0 ;;
    --no-e2e)       RUN_E2E=0 ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "preflight: unknown flag '$arg' (see --help)" >&2; exit 2 ;;
  esac
done

banner() { printf '\n\033[1;36m━━ preflight: %s ━━\033[0m\n' "$1"; }
note()   { printf '\033[0;33m   %s\033[0m\n' "$1"; }

source_datasource_env_the_jpa_booting_tasks_need() {
  # shellcheck disable=SC1090
  [ -f "${HOME}/.bashrc" ] && source "${HOME}/.bashrc" || true
}

if [ "$RUN_BACKEND" = 1 ]; then
  banner "backend — ./gradlew test (whole server suite)"
  source_datasource_env_the_jpa_booting_tasks_need
  ( cd "${SERVER_DIR}" && ./gradlew test --no-daemon --console=plain )
fi

if [ "$RUN_WEB" = 1 ]; then
  banner "web — lint"
  ( cd "${WEB_DIR}" && pnpm lint )

  banner "web — tsc (typecheck, no emit)"
  ( cd "${WEB_DIR}" && pnpm exec tsc -p tsconfig.json --noEmit )

  banner "web — unit tests (vitest)"
  ( cd "${WEB_DIR}" && pnpm test )

  banner "web — build"
  ( cd "${WEB_DIR}" && pnpm build )

  banner "web — generate-api + drift check"
  ( cd "${WEB_DIR}" && pnpm generate-api )
  ( cd "${WEB_DIR}" && git diff --exit-code src/app/api/generated/ ) || {
    note "generated API client drifted — commit the regenerated client."
    exit 1
  }
fi

if [ "$RUN_GALLERY" = 1 ]; then
  banner "proof-gallery — generator unit tests (vitest)"
  ( cd "${WEB_DIR}" && pnpm test:scripts )

  banner "proof-gallery — browserless link check (proof-gallery-links project)"
  ( cd "${WEB_DIR}" && GALLERY_LINKS_ONLY=1 pnpm exec playwright test \
      --config=e2e/playwright.config.ts --project=proof-gallery-links )
fi

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
