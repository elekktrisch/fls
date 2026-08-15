import { test, expect } from '../_helpers/console-guard';

import { mintRealHandshakeToFile } from './_helpers/fan-out-parity-fixture';

test('J-0c: mint migration handshake to file (full-chain only)', async ({
  browser,
  request,
}, testInfo) => {
  const outFile = process.env['J0C_HANDSHAKE_OUT'];
  test.skip(!outFile, 'J0C_HANDSHAKE_OUT not set — handshake mint is full-chain-workflow-only');

  const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  const handshake = await mintRealHandshakeToFile(browser, request, baseURL, outFile!);

  expect(
    handshake.uploadId,
    'handshake must carry an uploadId the export seals the bundle to',
  ).toBeTruthy();
  expect(
    handshake.publicKeyPem,
    'handshake must carry the RSA public key the export encrypts the bundle against',
  ).toContain('PUBLIC KEY');
});
