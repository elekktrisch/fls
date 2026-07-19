import { type Page, type Request } from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import {
  SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS,
  createUser,
  deleteUser,
  pollUserDisabled,
  setUserEnabled,
  withRealmPatch,
} from './_helpers/keycloak-admin';
import { fillKcLogin } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';

/**
 * Real-IdP token-lifecycle flows. Realm-mutating specs are wrapped in
 * `withRealmPatch` + `describe.serial` — see e2e/README.md § Realm-mutating
 * specs for the contract. All assertions are observable behavior:
 * URL host transitions, `page.on('request')` header presence/absence,
 * authed-shell markers. Never KC copy / internal SPA events / screenshots.
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

  // @quarantine-kc26 — silent refresh red since the KC 26 upgrade (refresh-grant
  // / SSO behavior change); grep-inverted out of the sharded cross-journey real-idp
  // regression so its retries don't burn the step budget. Tracked: _BOYSCOUT.md [KC-26 UPGRADE DRIFT].
  test('@quarantine-kc26 silent refresh — SPA stays authenticated past access-token expiry', async ({
    page,
  }) => {
    await withRealmPatch(
      { accessTokenLifespan: SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS },
      async () => {
        await loginAs(page, SEED_USER, SEED_PASSWORD);
        // Wait past TWO shortened lifespans so at least one full silent
        // rotation is guaranteed to have fired AND settled before we
        // observe. At login the lib schedules a renewal immediately
        // (renewBefore=60s > 30s lifespan, auth.config.ts:48) and then
        // re-renews every ~lifespan; a single-lifespan wait could observe
        // mid-rotation (the refresh-token grant + KC's rotate-and-revoke
        // round-trip, `revokeRefreshToken=true`/`refreshTokenMaxReuse=0`)
        // and read a transient unauthed frame. Two lifespans + the lib's
        // 3s poll/retry (`tokenRefreshInSeconds`/`refreshTokenRetryInSeconds`
        // defaults) removes the race deterministically.
        await page.waitForTimeout((SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS * 2 + 5) * 1000);

        // Navigate to an authed-only route. If silent refresh failed,
        // `SilentRenewFailed` would have fired and the session bridge
        // would have re-authorized → KC URL. Assert the survival POSITIVELY
        // and with web-first retry, not as a single post-`networkidle`
        // snapshot: a `goto` that lands mid-redirect can flash KC's host
        // or the landing CTA for a frame. `waitForURL` polls until the SPA
        // host is stable off-KC; `expect(...).toHaveCount(0)` then retries
        // until the authed shell has rendered.
        await page.goto('/flights');
        await page.waitForURL((url) => url.host !== KC_HOST, { timeout: 15_000 });
        await page.waitForLoadState('networkidle');
        expect(new URL(page.url()).host).not.toBe(KC_HOST);
        await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
      },
    );
  });

  test('hard 401 — disabled user is redirected on next API call', async ({ page }, testInfo) => {
    // The disabled-user path deliberately drives an API call onto a now-invalid
    // session: the refresh-grant rejection / 401 makes the browser log a
    // `Failed to load resource … 401` (and the OIDC bridge logs its renewal
    // failure) before the re-auth redirect. That is the behavior under test, not
    // a regression — allow ONLY the 401 / refresh-failure markers; any other
    // console.error still fails the guard.
    allowConsoleErrors(testInfo, /\b401\b/, /SilentRenewFailed/i);
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
          // Propagation barrier: the PUT returns before the disable is
          // guaranteed visible to KC's refresh-grant handler. Poll the user
          // store until `enabled === false` so the redirect step below can
          // never race an as-yet-unapplied disable.
          await pollUserDisabled(userCtx.userId);

          // Wait past ONE shortened lifespan so the in-flight, offline-valid
          // access token has expired. Past expiry the SPA can only stay
          // authenticated by a refresh-token grant — which KC now denies for
          // the disabled user — so the session is doomed; only the moment of
          // observation remains.
          await page.waitForTimeout((SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS + 5) * 1000);

          // Drive the redirect deterministically instead of waiting on the
          // library's internal renewal timer to fire at the right instant.
          // Each iteration triggers a WARM in-app navigation by clicking a
          // persistent nav-bar RouterLink — no cold `page.goto` reboot, no
          // `clearCookies` (either kills the OIDC session-restore path). The
          // two targets ALTERNATE so every iteration is a real URL change
          // (a same-URL router nav is a no-op that would never re-run the
          // guard). With the access token expired and the refresh grant
          // denied, activating a protected route runs `authGuard` on an
          // unauthenticated session (or the concurrent `SilentRenewFailed`)
          // → `oidc.authorize()` → KC. The redirect may interrupt the in-flight
          // navigation and tear the authed shell down; that is the signal
          // under test, not an error.
          const spaHost = new URL(SPA_BASE_URL).host;
          const isRedirected = (): boolean => {
            const url = new URL(page.url());
            return (
              url.host === KC_HOST ||
              (url.host === spaHost && (url.pathname === '/' || url.pathname.startsWith('/auth/')))
            );
          };
          for (let attempt = 0; attempt < 12 && !isRedirected(); attempt++) {
            // Warm nav: click a nav-bar RouterLink if the authed shell is
            // still mounted; if it has already unmounted (redirect in flight),
            // skip straight to the settle-check. `.catch` swallows the click's
            // own navigation-interrupt when the redirect races it.
            const navTestId =
              attempt % 2 === 0 ? 'af-nav-section-/flights' : 'af-nav-section-/flightreports';
            const navLink = page.getByTestId(navTestId);
            if (await navLink.count()) {
              await navLink.click({ timeout: 2_000 }).catch(() => {
                /* redirect may interrupt the click's navigation */
              });
            }
            // Give the guard / silent-renew failure a beat to settle the
            // redirect before re-checking / re-driving.
            await page
              .waitForURL(() => isRedirected(), { timeout: 5_000 })
              .catch(() => {
                /* not yet — next iteration re-drives the navigation */
              });
          }

          // Observable: the SPA must end up on KC's login (re-auth) or
          // the public landing — never on an authed route.
          await page.waitForURL(() => isRedirected(), { timeout: 15_000 });
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
  }, testInfo) => {
    context.on('page', (p) => watchConsoleErrors(p, testInfo));
    // Same Playwright BrowserContext means shared *live* localStorage —
    // that's where the OIDC client persists tokens (the
    // `AbstractSecurityStorage` → `DefaultLocalStorageService` binding in
    // `app.config.ts`). `browser.newContext({ storageState })` would only
    // carry a snapshot, not propagate live storage events.
    const tabA = await context.newPage();
    const tabB = await context.newPage();

    await loginAs(tabA, SEED_USER, SEED_PASSWORD);
    // Tab B picks up the shared localStorage tokens on its first
    // navigation — same context, same storage origin.
    await tabB.goto('/start');
    await tabB.waitForURL((url) => !url.pathname.startsWith('/realms/') && url.pathname !== '/', {
      timeout: 15_000,
    });
    // Confirm tab B is in the authed shell (unauthed CTA absent).
    await expect(tabB.getByTestId('landing-topbar-sign-in')).toHaveCount(0);

    // Tab A: RP-initiated logout via the SPA route. Must be
    // `oidcSecurity.logoff()` (which the /auth/logout page invokes),
    // NOT `logoffLocal()` — otherwise KC's SSO cookie keeps tab B
    // silently re-authenticated on the next checkAuth().
    await tabA.goto('/auth/logout');
    await tabA.waitForURL((url) => url.pathname === '/' && !url.searchParams.has('code'), {
      timeout: 15_000,
    });
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
    //
    // Deterministic wait for the SPECIFIC signal under test — a Bearer-carrying
    // /api/v1/* request — instead of `networkidle`, which is inherently racy on
    // a busy real-idp stack (a stray late XHR / SSE keeps the network non-idle
    // and the 45s budget elapses). Once that response settles, the prefetch
    // batch has fired and the captured `observed` list is populated.
    const apiResponse = page.waitForResponse(
      (r) => {
        const u = new URL(r.url());
        return u.host === new URL(SPA_BASE_URL).host && u.pathname.startsWith('/api/v1/');
      },
      { timeout: 15_000 },
    );
    await page.goto('/start');
    await apiResponse;
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
