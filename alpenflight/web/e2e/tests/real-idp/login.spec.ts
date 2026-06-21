import { type Page } from '@playwright/test';
import { test, expect } from '../_helpers/console-guard';

import { assertLocalhostIssuer, _testing as kcAdmin } from './_helpers/keycloak-admin';
import { KC_ERROR_SELECTOR, fillKcLogin } from './_helpers/kc-form';

/**
 * S-174 — login flows against live Keycloak.
 *
 * Coverage:
 *   - Happy path: pilot1 → landed on authed root (clubId-visible assertion
 *     is deferred to a new linking-UI story per refinement grill).
 *   - Wrong-password: KC inline error, SPA stays unauthed.
 *   - Logout → re-login: end_session_endpoint + clearCookies → cold
 *     checkAuth() returns unauthenticated.
 *   - Locale `?ui_locales=fr`: <html lang="fr"> on KC form.
 *
 * Seed user `pilot1` is read-only — never re-create or mutate; the
 * password is `pilot1-dev-2026!` per auth/README.md.
 */

const SEED_USER = 'pilot1@example.com';
const SEED_PASSWORD = 'pilot1-dev-2026!';

async function startLogin(page: Page, path = '/'): Promise<void> {
  await page.goto(path);
  // Landing page sign-in CTA; routes through OidcSecurityService.authorize.
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
}

test.describe('login — real-idp', () => {
  test('happy path — pilot1 lands on authed root', async ({ page }) => {
    await startLogin(page);
    await fillKcLogin(page, SEED_USER, SEED_PASSWORD);

    // Post-auth landing per S-021 contract: first-time login from `/`
    // lands on `/clubs` or `/start`. The exact destination depends on
    // the deep-link stamp; we assert the SPA is back (host = baseURL)
    // and NOT on Keycloak.
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
    expect(new URL(page.url()).pathname).not.toMatch(/^\/realms\//);
    // SessionStore should be populated. The `clubId=club-1` claim
    // assertion is **deferred** to the new linking-UI story (per
    // refinement Open design questions); here we only assert that the
    // app rendered authed chrome — landing's signin button is gone,
    // replaced by the nav bar.
    await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
  });

  test('wrong password — KC inline error, SPA stays unauthed', async ({ page }) => {
    await startLogin(page);
    await fillKcLogin(page, SEED_USER, 'wrong-password-2026!');

    // Stays on the KC login URL with an inline error.
    await expect(page).toHaveURL(/\/realms\/alpenflight\/login-actions\/authenticate/);
    await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
  });

  test('logout → re-login — no auto-relogin from stale refresh token', async ({
    page,
    context,
  }) => {
    await startLogin(page);
    await fillKcLogin(page, SEED_USER, SEED_PASSWORD);
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });

    // RP-initiated logout via the SPA route owned by S-021's
    // oidc-session-bridge. The route triggers KC's end_session_endpoint.
    await page.goto('/auth/logout');
    // Positive assertion: landing-topbar-sign-in is the unauthed-chrome
    // marker. Surfacing it means the SPA reached an idle public state,
    // not just that the URL stopped being /auth/logout.
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'));
    await expect(page.getByTestId('landing-topbar-sign-in')).toBeVisible();

    // Belt-and-braces: explicitly clear cookies so any stale KC SSO
    // cookie can't auto-resume the session.
    await context.clearCookies();

    // Re-login should require credentials again (no silent SSO from a
    // leftover refresh token). Visit the landing page; the sign-in CTA
    // is back; clicking it sends us to KC's login form, not directly
    // to the authed root.
    await page.goto('/');
    await expect(page.getByTestId('landing-topbar-sign-in')).toBeVisible();
    await page.getByTestId('landing-topbar-sign-in').click();
    await page.waitForURL(/\/realms\/alpenflight\//);
    // KC username field exists → SSO didn't bypass the form.
    await expect(page.locator('#username')).toBeVisible();
  });

  test('locale ?ui_locales=fr — <html lang="fr"> on KC chrome', async ({ page }) => {
    // Bypass the SPA's authorize() (which sends `ui_locales=<spa locale>`)
    // and hit KC directly with `ui_locales=fr`. This exercises the
    // parent message-bundle fallthrough that S-171's check-theme-load.sh
    // validates locally; mock-auth can't.
    //
    // `ui_locales` is the OIDC-standard authorization-request param Keycloak
    // honours for login-UI language selection (server_admin §"User locale
    // selection": client-provided locales via `ui_locales`). The legacy
    // `kc_locale` query param was DROPPED in Keycloak 26.x (stack pins
    // quay.io/keycloak/keycloak:26.5), so it no longer flips the chrome and
    // the realm falls back to its `defaultLocale` (de) → was rendering `en`
    // on the resolved chain. The SPA itself already uses `ui_locales`
    // exclusively (auth.config.ts:58, landing/signup authorize() calls);
    // this raw-goto case is single-sourced onto the same param.
    //
    // Issuer comes from the same module-scoped constant the admin helper
    // uses, so the localhost-issuer boundary is single-sourced. client_id
    // + redirect_uri match what the realm-export registers for
    // alpenflight-web (the SPA's public client, per
    // alpenflight/auth/README.md § Clients).
    assertLocalhostIssuer();
    const baseUrl = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
    const authorize =
      `${kcAdmin.ISSUER}/protocol/openid-connect/auth?` +
      new URLSearchParams({
        client_id: 'alpenflight-web',
        response_type: 'code',
        scope: 'openid',
        redirect_uri: `${baseUrl}/`,
        state: 'locale-smoke',
        ui_locales: 'fr',
      }).toString();
    await page.goto(authorize);
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });
});
