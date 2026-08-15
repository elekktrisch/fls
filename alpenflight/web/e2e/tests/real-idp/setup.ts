import { test as setup, expect } from '@playwright/test';
import { randomBytes } from 'node:crypto';

import { assertLocalhostIssuer, ensureUser } from './_helpers/keycloak-admin';
import { runProbes } from './_helpers/probes';
import { E2E_OCCUPIED_EMAIL, E2E_CANNED_PASSWORD } from './_helpers/test-user';


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
