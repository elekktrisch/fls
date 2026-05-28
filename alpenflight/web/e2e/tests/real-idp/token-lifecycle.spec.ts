import { test, expect, type Page, type Request } from '@playwright/test';

import {
  SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS,
  createUser,
  deleteUser,
  setUserEnabled,
  withRealmPatch,
} from './_helpers/keycloak-admin';
import { fillKcLogin } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';

/**
 * S-175 — real-IdP token-lifecycle flows.
 *
 * Two `describe.serial` blocks for clarity of cleanup contracts:
 *
 *  (a) realm-mutating: silent refresh + hard 401, both wrapped in
 *      `withRealmPatch({ accessTokenLifespan: SHORTENED })` so KC issues
 *      tokens that expire inside the test window. `try/finally` restores;
 *      globalTeardown is the safety net for SIGKILL.
 *
 *  (b) per-test ephemeral user / no realm mutation: multi-tab logout +
 *      Bearer scoping. Uses the read-only seed user `pilot1` where it can
 *      (no cleanup needed).
 *
 * Assertions are observable behavior only: URL host transitions,
 * `page.on('request')` header presence/absence, HTTP status codes. Never
 * KC copy / internal SPA events / screenshot diffs.
 *
 * KC user-disable does NOT instantly invalidate already-issued JWTs
 * (offline JWT validation per ADR 0007). The 401 surfaces on next refresh
 * rotation OR token expiry; the shortened lifespan forces renewal inside
 * the test window — the assertion accepts either path (silent-refresh-fail
 * redirect OR direct API-401 redirect) via URL-transition check.
 */

const SEED_USER = 'pilot1@example.com';
const SEED_PASSWORD = 'pilot1-dev-2026!';
const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const KC_HOST = 'localhost:8090';

async function loginAs(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, username, password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
}

async function loginAsEphemeral(page: Page): Promise<{ user: TestUser; userId: string }> {
  const user = freshTestUser();
  const userId = await createUser(user);
  await loginAs(page, user.email, user.password);
  return { user, userId };
}

test.describe('token-lifecycle — realm-mutating', () => {
  test.describe.configure({ mode: 'serial' });
  // `withRealmPatch` waits the shortened lifespan + asserts post-renewal;
  // the multi-step flow needs more than the project's 60s default.
  test.setTimeout(120_000);

  test('silent refresh — SPA stays authenticated past access-token expiry', async ({ page }) => {
    await withRealmPatch(
      { accessTokenLifespan: SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS },
      async () => {
        await loginAs(page, SEED_USER, SEED_PASSWORD);
        // Wait past the shortened lifespan + the SPA's pre-expiry renewal
        // window (60s per auth.config.ts:48). At login, the lib schedules
        // a silent renewal immediately because renewBefore > lifespan;
        // we just need to give the rotation enough time to land.
        await page.waitForTimeout((SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS + 5) * 1000);

        // Navigate to an authed-only route. If silent refresh failed,
        // `SilentRenewFailed` would have fired and the session bridge
        // would have re-authorized → KC URL. Authed-only route renders
        // = silent refresh succeeded.
        await page.goto('/flights');
        await page.waitForLoadState('networkidle');
        const pathname = new URL(page.url()).pathname;
        expect(pathname).toMatch(/^\/(flights|start|clubs)/);
        expect(pathname).not.toMatch(/^\/realms\//);
        // Unauthed-chrome marker absent confirms the session is still live.
        await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
      },
    );
  });

  test('hard 401 — disabled user is redirected on next API call', async ({ page }) => {
    let userCtx: { user: TestUser; userId: string } | undefined;
    try {
      await withRealmPatch(
        { accessTokenLifespan: SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS },
        async () => {
          userCtx = await loginAsEphemeral(page);
          // Sanity: we landed authed.
          await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);

          // Disable the user via Admin REST. Offline-validated JWTs in
          // flight stay valid; the next refresh rotation will be denied
          // (refresh-grant rejects disabled users) → SilentRenewFailed →
          // session bridge re-authorizes. Either silent-refresh-fail OR
          // direct API-401 lands the SPA on KC's login URL.
          await setUserEnabled(userCtx.userId, false, userCtx.user.email);

          // Trigger periodic API activity by navigating + waiting past
          // the shortened lifespan window. The renewal failure path is
          // the deterministic trigger inside the test window.
          await page.goto('/flights');
          await page.waitForTimeout((SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS + 10) * 1000);
          // Force another navigation to give the guard / interceptor a
          // chance to run with the now-invalid session.
          await page.goto('/start').catch(() => {
            /* may already have redirected mid-navigation */
          });

          // Observable: the SPA must end up on KC's login (re-auth) or
          // the public landing — never on an authed route.
          await page.waitForURL(
            (url) =>
              url.host === KC_HOST ||
              (url.host === new URL(SPA_BASE_URL).host &&
                (url.pathname === '/' || url.pathname.startsWith('/auth/'))),
            { timeout: 30_000 },
          );
        },
      );
    } finally {
      // Cleanup: predicate-guarded delete. No re-enable.
      if (userCtx) {
        await deleteUser(userCtx.userId, userCtx.user.email).catch(() => {
          /* sweep is the safety net */
        });
      }
    }
  });
});

test.describe('token-lifecycle — non-mutating', () => {
  test.describe.configure({ mode: 'serial' });

  test('multi-tab logout — tab A logout invalidates tab B on next navigation', async ({
    context,
  }) => {
    // Same Playwright BrowserContext means shared *live* localStorage —
    // that's where the OIDC client persists tokens (auth.config.ts:34 +
    // app.config.ts's DefaultLocalStorageService). `browser.newContext({
    // storageState })` would only carry a snapshot.
    const tabA = await context.newPage();
    const tabB = await context.newPage();

    await loginAs(tabA, SEED_USER, SEED_PASSWORD);
    // Tab B picks up the shared localStorage tokens on its first
    // navigation — same context, same storage origin.
    await tabB.goto('/start');
    await tabB.waitForURL(
      (url) => !url.pathname.startsWith('/realms/') && url.pathname !== '/',
      { timeout: 15_000 },
    );
    // Confirm tab B is in the authed shell (unauthed CTA absent).
    await expect(tabB.getByTestId('landing-topbar-sign-in')).toHaveCount(0);

    // Tab A: RP-initiated logout via the SPA route. Must be
    // `oidcSecurity.logoff()` (which the /auth/logout page invokes),
    // NOT `logoffLocal()` — otherwise KC's SSO cookie keeps tab B
    // silently re-authenticated on the next checkAuth().
    await tabA.goto('/auth/logout');
    await tabA.waitForURL(
      (url) => url.pathname === '/' && !url.searchParams.has('code'),
      { timeout: 15_000 },
    );
    await expect(tabA.getByTestId('landing-topbar-sign-in')).toBeVisible();

    // Tab B's next route navigation must detect the missing tokens and
    // redirect to KC (authGuard → authorize) or to the public landing.
    // Don't assert on the internal session bridge — only the observable
    // URL transition.
    await tabB.goto('/clubs');
    await tabB.waitForURL(
      (url) => url.host === KC_HOST || url.pathname === '/' || url.pathname.startsWith('/auth/'),
      { timeout: 15_000 },
    );
    const finalPath = new URL(tabB.url()).pathname;
    expect(finalPath).not.toMatch(/^\/clubs/);
  });

  test('Bearer scoping — only /api/v1/* carries Authorization', async ({ page }) => {
    const observed: Array<{ url: string; hasBearer: boolean }> = [];
    const onRequest = (req: Request): void => {
      // Per-request snapshot — late `.headers()` re-reads can race with
      // request mutation in Playwright; capture into the closure now.
      const auth = req.headers()['authorization'];
      observed.push({
        url: req.url(),
        hasBearer: typeof auth === 'string' && /^Bearer /i.test(auth),
      });
    };
    page.on('request', onRequest);

    await loginAs(page, SEED_USER, SEED_PASSWORD);
    // Drive some authenticated navigation so the Bearer interceptor fires
    // on at least one /api/v1/* call. The prefetch on SessionStore.login
    // hits /api/v1/ref-data + /api/v1/me; both are observable here.
    await page.goto('/start');
    await page.waitForLoadState('networkidle');
    page.off('request', onRequest);

    const apiCalls = observed.filter((r) => {
      const u = new URL(r.url);
      return u.host === new URL(SPA_BASE_URL).host && u.pathname.startsWith('/api/v1/');
    });
    const kcCalls = observed.filter((r) => new URL(r.url).host === KC_HOST);

    // Partition assertions. Empty observed-list per partition would be a
    // spec bug, not a pass — assert both partitions are populated AND
    // that the Bearer contract holds within each.
    expect(apiCalls.length, 'no /api/v1/* calls observed — spec bug').toBeGreaterThan(0);
    expect(kcCalls.length, 'no Keycloak calls observed — spec bug').toBeGreaterThan(0);
    for (const r of apiCalls) {
      expect(r.hasBearer, `expected Bearer on ${r.url}`).toBe(true);
    }
    for (const r of kcCalls) {
      expect(r.hasBearer, `unexpected Bearer on Keycloak request ${r.url}`).toBe(false);
    }
  });
});
