import {
  assertLocalhostIssuer,
  restoreCanonicalAccessTokenLifespan,
  sweepE2eUsers,
} from './_helpers/keycloak-admin';

/**
 * Top-level globalTeardown — runs even on suite-abort where per-project
 * teardown wouldn't. Two safety nets, in order:
 *
 *  1. Restore `accessTokenLifespan` to the canonical value if a
 *     realm-mutating spec (S-175) crashed past `withRealmPatch`'s
 *     `finally` block (worker SIGKILL, Playwright wall-clock timeout).
 *  2. Sweep any `e2e-*@example.com` user the suite may have leaked
 *     (per-test afterEach is the primary cleanup; this is the safety
 *     net for SIGKILL'd workers).
 *
 * No-op on mock-auth-only runs: localhost guard + admin token fail-fast
 * if Keycloak isn't reachable. We swallow those so a `pnpm e2e` run
 * doesn't fail at teardown when there's no Keycloak.
 */
export default async function globalTeardown(): Promise<void> {
  try {
    assertLocalhostIssuer();
    const restored = await restoreCanonicalAccessTokenLifespan();
    if (restored) {
      // eslint-disable-next-line no-console
      console.warn(
        'real-idp teardown: accessTokenLifespan had drifted from the canonical ' +
          'value — restored. A realm-mutating spec likely crashed past its ' +
          'withRealmPatch finally block (SIGKILL or wall-clock timeout).',
      );
    }
    const deleted = await sweepE2eUsers();
    if (deleted > 0) {
      // eslint-disable-next-line no-console
      console.info(`real-idp teardown: swept ${deleted} leaked e2e-*@example.com user(s)`);
    }
  } catch (err) {
    // Swallow on mock-auth runs (Keycloak unreachable). Surface on
    // real-idp runs would mask the underlying suite failure — sweep is
    // best-effort by definition.
    if (process.env['E2E_REAL_IDP'] === '1') {
      // eslint-disable-next-line no-console
      console.warn(`real-idp teardown: cleanup failed — ${(err as Error).message}`);
    }
  }
}
