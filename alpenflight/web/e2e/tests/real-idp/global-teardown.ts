import { assertLocalhostIssuer, sweepE2eUsers } from './_helpers/keycloak-admin';

/**
 * Top-level globalTeardown — runs even on suite-abort where per-project
 * teardown wouldn't. Sweeps any `e2e-*@example.com` user the suite may
 * have leaked (per-test afterEach is the primary cleanup; this is the
 * safety net for SIGKILL'd workers).
 *
 * No-op on mock-auth-only runs: localhost guard + admin token fail-fast
 * if Keycloak isn't reachable. We swallow those so a `pnpm e2e` run
 * doesn't fail at teardown when there's no Keycloak.
 */
export default async function globalTeardown(): Promise<void> {
  try {
    assertLocalhostIssuer();
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
      console.warn(`real-idp teardown: sweep failed — ${(err as Error).message}`);
    }
  }
}
