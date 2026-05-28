import { test as setup, expect } from '@playwright/test';
import { randomBytes } from 'node:crypto';

import { assertLocalhostIssuer, ensureUser } from './_helpers/keycloak-admin';
import { runProbes } from './_helpers/probes';
import { E2E_OCCUPIED_EMAIL, E2E_CANNED_PASSWORD } from './_helpers/test-user';

/**
 * real-idp-setup: bootstrap the suite before any spec runs.
 *
 *  1. Refuse to talk to a non-localhost issuer (the committed dev secret
 *     boundary).
 *  2. Stamp a 6-hex `E2E_RUN_ID` so per-test email factories produce
 *     run-disambiguatable addresses (`e2e-${runId}-${uuid8}@example.com`).
 *  3. Run the four HTTP probes; on any failure, surface ALL failures + a
 *     "run dev-up-full.sh first" pointer before failing the test.
 *  4. Idempotently provision `e2e-occupied@example.com` for the
 *     email-in-use reject spec. Never torn down.
 */

setup('real-idp probes + run-id + occupied fixture', async () => {
  assertLocalhostIssuer();

  if (!process.env['E2E_RUN_ID']) {
    process.env['E2E_RUN_ID'] = randomBytes(3).toString('hex');
  }

  const { ok, failures } = await runProbes();
  if (!ok) {
    const message =
      'real-idp pre-flight probes failed:\n' +
      failures.join('\n') +
      '\n\nRun: bash alpenflight/ops/dev-up-full.sh';
    throw new Error(message);
  }

  await ensureUser({
    email: E2E_OCCUPIED_EMAIL,
    password: E2E_CANNED_PASSWORD,
    firstName: 'E2e',
    lastName: 'Occupied',
  });

  expect(process.env['E2E_RUN_ID']).toMatch(/^[0-9a-f]{6}$/);
});
