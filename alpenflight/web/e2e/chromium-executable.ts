import { existsSync } from 'node:fs';

// T-43 — resolve a launchable Chromium for Playwright at config-eval time.
//
// WHY: the dev box is Alpine/musl. Playwright's BUNDLED chromium is a glibc
// binary that can't launch under musl (missing libnss3/libnspr4), so every
// e2e/gallery check was CI-only → slow serial round-trips. The musl-native
// system chromium (`apk add chromium`) CAN launch — point Playwright at it.
//
// CI is UNAFFECTED: CI runs on glibc runners with Playwright's bundled browser
// (`playwright install --with-deps chromium`) and sets no env var / has no apk
// chromium at the probed paths, so this returns `undefined` and Playwright
// falls back to its bundled browser exactly as before.

/** Probe order for the apk system chromium (Alpine package layout). */
export const SYSTEM_CHROMIUM_PATHS = [
  '/usr/lib/chromium/chromium',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
] as const;

/**
 * Resolve the Chromium executable Playwright should launch:
 *  1. `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` env override (explicit wins);
 *  2. the first existing apk system-chromium path (musl-native);
 *  3. `undefined` → Playwright uses its own bundled browser (CI default).
 *
 * @param env       env map (injectable for tests; defaults to process.env)
 * @param fileExists existence probe (injectable for tests; defaults to fs)
 */
export function resolveChromiumExecutablePath(
  env: NodeJS.ProcessEnv = process.env,
  fileExists: (path: string) => boolean = existsSync,
): string | undefined {
  const override = env['PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH'];
  if (override && override.trim().length > 0) {
    return override;
  }
  for (const candidate of SYSTEM_CHROMIUM_PATHS) {
    if (fileExists(candidate)) {
      return candidate;
    }
  }
  return undefined;
}

/**
 * Launch args for the resolved browser. The system chromium under the LXC/musl
 * box needs `--no-sandbox` (no user-namespace sandbox available); Playwright's
 * bundled browser on CI's glibc runners does NOT, so we only add the flag when
 * we are actually pointing at a resolved (system) executable — keeping CI's
 * sandbox posture unchanged.
 */
export function chromiumLaunchArgs(executablePath: string | undefined): string[] {
  return executablePath ? ['--no-sandbox'] : [];
}
