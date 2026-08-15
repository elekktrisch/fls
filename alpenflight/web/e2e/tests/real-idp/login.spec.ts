import { type Page } from '@playwright/test';
import { test, expect } from '../_helpers/console-guard';

import { assertLocalhostIssuer, _testing as kcAdmin } from './_helpers/keycloak-admin';
import { KC_ERROR_SELECTOR, fillKcLogin } from './_helpers/kc-form';

const READ_ONLY_SEED_USER = 'pilot1@example.com';
const READ_ONLY_SEED_PASSWORD = 'pilot1-dev-2026!';

async function startLogin(page: Page, path = '/'): Promise<void> {
  await page.goto(path);
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
}

test.describe('login — real-idp', () => {
  test('happy path — pilot1 leaves Keycloak and the SPA renders authed chrome', async ({
    page,
  }) => {
    await startLogin(page);
    await fillKcLogin(page, READ_ONLY_SEED_USER, READ_ONLY_SEED_PASSWORD);

    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
    expect(new URL(page.url()).pathname).not.toMatch(/^\/realms\//);
    await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
  });

  test('wrong password — KC inline error, SPA stays unauthed', async ({ page }) => {
    await startLogin(page);
    await fillKcLogin(page, READ_ONLY_SEED_USER, 'wrong-password-2026!');

    await expect(page).toHaveURL(/\/realms\/alpenflight\/login-actions\/authenticate/);
    await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
  });

  test('logout → re-login — no auto-relogin from stale refresh token', async ({
    page,
    context,
  }) => {
    await startLogin(page);
    await fillKcLogin(page, READ_ONLY_SEED_USER, READ_ONLY_SEED_PASSWORD);
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });

    await page.goto('/auth/logout');
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'));
    await expect(page.getByTestId('landing-topbar-sign-in')).toBeVisible();

    await context.clearCookies();

    await page.goto('/');
    await expect(page.getByTestId('landing-topbar-sign-in')).toBeVisible();
    await page.getByTestId('landing-topbar-sign-in').click();
    await page.waitForURL(/\/realms\/alpenflight\//);
    await expect(page.locator('#username')).toBeVisible();
  });

  test('@quarantine-kc26 locale ?ui_locales=fr — <html lang="fr"> on KC chrome', async ({
    page,
  }) => {
    assertLocalhostIssuer();
    const baseUrl = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
    const kcAuthorizeUrlBypassingTheSpaLocale =
      `${kcAdmin.ISSUER}/protocol/openid-connect/auth?` +
      new URLSearchParams({
        client_id: 'alpenflight-web',
        response_type: 'code',
        scope: 'openid',
        redirect_uri: `${baseUrl}/`,
        state: 'locale-smoke',
        ui_locales: 'fr',
      }).toString();
    await page.goto(kcAuthorizeUrlBypassingTheSpaLocale);
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });
});
