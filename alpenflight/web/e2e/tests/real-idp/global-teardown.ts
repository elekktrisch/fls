import {
  assertLocalhostIssuer,
  restoreCanonicalAccessTokenLifespan,
  sweepE2eUsers,
} from './_helpers/keycloak-admin';

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
