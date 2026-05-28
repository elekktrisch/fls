import { test, expect, type Page } from '@playwright/test';

/**
 * S-174 — login flows against live Keycloak.
 *
 * Coverage:
 *   - Happy path: pilot1 → landed on authed root (clubId-visible assertion
 *     is deferred to a new linking-UI story per refinement grill).
 *   - Wrong-password: KC inline error, SPA stays unauthed.
 *   - Logout → re-login: end_session_endpoint + clearCookies → cold
 *     checkAuth() returns unauthenticated.
 *   - Locale `?kc_locale=fr`: <html lang="fr"> on KC form.
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

async function fillKcLogin(page: Page, username: string, password: string): Promise<void> {
  // Stock keycloak.v2 login form — stable across the S-171 visual theme.
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('input[type="submit"], button[type="submit"]').click();
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
    const errorRegion = page.locator(
      '.pf-v5-c-form__helper-text, .pf-v5-c-helper-text__item-text, .alert-error, #input-error',
    );
    await expect(errorRegion.first()).toBeVisible();
  });

  test('logout → re-login — no auto-relogin from stale refresh token', async ({ page, context }) => {
    await startLogin(page);
    await fillKcLogin(page, SEED_USER, SEED_PASSWORD);
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });

    // RP-initiated logout via the SPA route owned by S-021's
    // oidc-session-bridge. The route triggers KC's end_session_endpoint.
    await page.goto('/auth/logout');
    // After logout the user lands on a public route. Wait for either
    // the landing page or an /auth/* terminal state.
    await page.waitForURL(
      (url) => !url.pathname.startsWith('/realms/') && url.pathname !== '/auth/logout',
      { timeout: 30_000 },
    );

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

  test('locale ?kc_locale=fr — <html lang="fr"> on KC chrome', async ({ page }) => {
    // Bypass the SPA's authorize() (which sends `ui_locales=<spa locale>`)
    // and hit KC directly with `kc_locale=fr`. This exercises the
    // parent message-bundle fallthrough that S-171's check-theme-load.sh
    // validates locally; mock-auth can't.
    const issuer = process.env['E2E_KC_ISSUER'] ?? 'http://localhost:8090/realms/alpenflight';
    const authorize =
      `${issuer}/protocol/openid-connect/auth?` +
      new URLSearchParams({
        client_id: 'alpenflight-web',
        response_type: 'code',
        scope: 'openid',
        redirect_uri: `${process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201'}/`,
        state: 'locale-smoke',
        kc_locale: 'fr',
      }).toString();
    await page.goto(authorize);
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });
});
