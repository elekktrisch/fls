import { expect, test } from '@playwright/test';

/**
 * S-140 — /migrate/start happy path. Mock-auth bootstrap; the SPA mocks
 * GET /api/v1/migrations/handshake/current with a successful response so
 * the page lands in the "have upload" state with the PEM textarea, the
 * download button, the regenerate button, and the JAR-link panel all
 * visible. Touch-target asserts at the mobile viewport.
 *
 * <p>Backend-side state machine + supersession + expiry-job coverage
 * lives in {@code MigrationHandshakeIT} (server). This spec stops at the
 * SPA's observable contract.
 */
test.describe('migration handshake — /migrate/start (mock-auth)', () => {
  const MOCK_UPLOAD = {
    uploadId: '019e30c3-2c00-7001-8000-000000000001',
    publicKeyPem:
      '-----BEGIN PUBLIC KEY-----\nMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA\n' +
      'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==\n' +
      '-----END PUBLIC KEY-----\n',
    expiresAt: '2026-05-30T10:00:00Z',
  };

  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/migrations/handshake/current', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_UPLOAD),
      });
    });
    await page.route('**/api/v1/migrations/handshake', async (route) => {
      // Regenerate flow: mint a new id so the test can assert the
      // textarea swaps to the fresh PEM.
      const next = {
        ...MOCK_UPLOAD,
        uploadId: '019e30c3-2c00-7001-8000-000000000002',
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(next),
      });
    });
  });

  test('renders public key, expires, download, and JAR-link panel', async ({ page }) => {
    await page.goto('/migrate/start');

    await expect(page.getByTestId('migrate-handshake')).toBeVisible();
    await expect(page.getByTestId('migrate-handshake-headline')).toBeVisible();
    await expect(page.getByTestId('migrate-handshake-tagline')).toBeVisible();

    const pem = page.getByTestId('migrate-handshake-pem');
    await expect(pem).toBeVisible();
    await expect(pem).toHaveValue(MOCK_UPLOAD.publicKeyPem);

    await expect(page.getByTestId('migrate-handshake-expires')).toBeVisible();
    await expect(page.getByTestId('migrate-handshake-download')).toBeVisible();
    await expect(page.getByTestId('migrate-handshake-regenerate')).toBeVisible();

    await expect(page.getByTestId('migrate-handshake-jar-panel')).toBeVisible();
    await expect(page.getByTestId('migrate-handshake-jar-link')).toBeVisible();

    await page.screenshot({
      path: 'screenshots/migration/01-handshake-loaded.png',
      fullPage: true,
    });
  });

  test('regenerate flow shows confirm dialog before issuing a fresh key', async ({ page }) => {
    await page.goto('/migrate/start');
    await expect(page.getByTestId('migrate-handshake-pem')).toHaveValue(MOCK_UPLOAD.publicKeyPem);

    await page.getByTestId('migrate-handshake-regenerate').click();
    await expect(page.getByTestId('regenerate-confirm-title')).toBeVisible();
    await expect(page.getByTestId('regenerate-confirm-message')).toBeVisible();

    await page.screenshot({
      path: 'screenshots/migration/02-regenerate-confirm.png',
      fullPage: true,
    });

    await page.getByTestId('regenerate-confirm-confirm').click();

    // Dialog dismisses + the page settles back to the loaded state. The PEM
    // textarea remains visible (same PEM in the mock; the uploadId swapped
    // — observable via the download filename).
    await expect(page.getByTestId('regenerate-confirm-title')).toHaveCount(0);
    await expect(page.getByTestId('migrate-handshake-pem')).toBeVisible();
  });

  test('regenerate confirm cancel leaves the existing key in place', async ({ page }) => {
    await page.goto('/migrate/start');
    await page.getByTestId('migrate-handshake-regenerate').click();
    await expect(page.getByTestId('regenerate-confirm-title')).toBeVisible();
    await page.getByTestId('regenerate-confirm-cancel').click();
    await expect(page.getByTestId('regenerate-confirm-title')).toHaveCount(0);
    await expect(page.getByTestId('migrate-handshake-pem')).toHaveValue(MOCK_UPLOAD.publicKeyPem);
  });

  test('every primary action hits >= 44 x 44 CSS px at mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto('/migrate/start');
    await expect(page.getByTestId('migrate-handshake-pem')).toBeVisible();

    for (const testId of ['migrate-handshake-download', 'migrate-handshake-regenerate']) {
      const btn = page.getByTestId(testId);
      await expect(btn).toBeVisible();
      const box = await btn.boundingBox();
      expect(box).not.toBeNull();
      if (box) {
        expect(box.width).toBeGreaterThanOrEqualTo(44);
        expect(box.height).toBeGreaterThanOrEqualTo(44);
      }
    }
  });

  test('mount on a fresh visit with no in-flight row falls through to POST', async ({ page }) => {
    let currentCalls = 0;
    let postCalls = 0;
    await page.unroute('**/api/v1/migrations/handshake/current');
    await page.unroute('**/api/v1/migrations/handshake');
    await page.route('**/api/v1/migrations/handshake/current', async (route) => {
      currentCalls += 1;
      await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
    });
    await page.route('**/api/v1/migrations/handshake', async (route) => {
      postCalls += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_UPLOAD),
      });
    });

    await page.goto('/migrate/start');
    await expect(page.getByTestId('migrate-handshake-pem')).toBeVisible();
    expect(currentCalls).toBeGreaterThanOrEqualTo(1);
    expect(postCalls).toBeGreaterThanOrEqualTo(1);
  });
});
