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
 *     realm-mutating spec crashed past `withRealmPatch`'s `finally` block
 *     (worker SIGKILL, Playwright wall-clock timeout). Load-bearing: a
 *     persisted 30s lifespan would poison subsequent specs / dev runs.
 *  2. Sweep any `e2e-*@example.com` user the suite may have leaked
 *     (per-test afterEach is the primary cleanup; this is the safety
 *     net for SIGKILL'd workers).
 *
 * No-op on mock-auth-only runs: localhost guard + admin token fail-fast
 * if Keycloak isn't reachable. We swallow those so a `pnpm e2e` run
 * doesn't fail at teardown when there's no Keycloak. On real-idp runs,
 * a failed lifespan restore is fatal — the sweep step is best-effort.
 */
export default async function globalTeardown(): Promise<void> {
  const realIdpRun = process.env['E2E_REAL_IDP'] === '1';
  try {
    assertLocalhostIssuer();
  } catch (err) {
    if (realIdpRun) throw err;
    return;
  }

  try {
    const restored = await restoreCanonicalAccessTokenLifespan();
    if (restored) {
      // eslint-disable-next-line no-console
      console.warn(
        'real-idp teardown: accessTokenLifespan had drifted from the canonical ' +
          'value — restored. A realm-mutating spec likely crashed past its ' +
          'withRealmPatch finally block (SIGKILL or wall-clock timeout).',
      );
    }
  } catch (err) {
    if (realIdpRun) {
      // Lifespan restore is load-bearing — fail loud so the next run
      // doesn't inherit a poisoned realm.
      throw new Error(
        `real-idp teardown: accessTokenLifespan restore failed — ${(err as Error).message}. ` +
          'Manually verify alpenflight realm `accessTokenLifespan` is 900s before re-running.',
      );
    }
    return;
  }

  try {
    const deleted = await sweepE2eUsers();
    if (deleted > 0) {
      // eslint-disable-next-line no-console
      console.info(`real-idp teardown: swept ${deleted} leaked e2e-*@example.com user(s)`);
    }
  } catch (err) {
    if (realIdpRun) {
      // eslint-disable-next-line no-console
      console.warn(`real-idp teardown: user sweep failed — ${(err as Error).message}`);
    }
  }
}
