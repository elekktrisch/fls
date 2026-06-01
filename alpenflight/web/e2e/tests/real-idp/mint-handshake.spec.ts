import { test, expect } from '@playwright/test';

import { mintRealHandshakeToFile } from './_helpers/fan-out-parity-fixture';

/**
 * J-0c T-05 — full-chain handshake mint (run ONLY by the dedicated proof
 * workflow `alpenflight-proof-fanout.yml`, never in the inner loop).
 *
 * The full chain needs the migration handshake BEFORE `alpenflight-export`
 * runs (the export seals the bundle to the handshake's RSA public key +
 * `uploadId`). The SPA `alpenflight-web` client has no direct-access grant, so
 * a curl-based principal token isn't available — driving the SPA login is the
 * only path to a verified-email principal JWT. This spec does exactly that:
 * log in the seeded principal, `POST /api/v1/migrations/handshake`, and write
 * the `{ uploadId, publicKeyPem }` JSON to `J0C_HANDSHAKE_OUT` so the workflow
 * can `--handshake-file` it into the export step, then forward it back to the
 * parity spec as `J0C_REAL_HANDSHAKE_FILE`.
 *
 * Gated on `J0C_HANDSHAKE_OUT`: when the env var is absent (every inner-loop /
 * nightly real-idp run) the spec skips — it is workflow-only scaffolding, not
 * a parity assertion, so it must never gate ordinary real-idp runs.
 */
test('J-0c: mint migration handshake to file (full-chain only)', async ({
  browser,
  request,
}, testInfo) => {
  const outFile = process.env['J0C_HANDSHAKE_OUT'];
  test.skip(!outFile, 'J0C_HANDSHAKE_OUT not set — handshake mint is full-chain-workflow-only');

  const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  const handshake = await mintRealHandshakeToFile(browser, request, baseURL, outFile!);

  expect(handshake.uploadId, 'handshake must carry an uploadId the export seals the bundle to').toBeTruthy();
  expect(
    handshake.publicKeyPem,
    'handshake must carry the RSA public key the export encrypts the bundle against',
  ).toContain('PUBLIC KEY');
});
