import { type Request } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';
import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

/**
 * Public routes stay public against the live IdP.
 *
 * Each route flagged `data.publicAccess: true` in the SPA route tree
 * MUST render anonymously without (a) redirecting to Keycloak's authorize
 * endpoint, (b) triggering any `/api/v1/*` call — `session.store.ts`'s
 * `bootstrapPrefetch()` is gated on `isAuthenticated()`; a regression
 * there would surface here — and (c) showing the app-shell nav-bar, which
 * every one of these routes suppresses via `data.showNavBar: false`
 * (the structural close-out of the legacy `||` tautology, R12). The nav
 * appearing on a public route is exactly that regression.
 *
 * Hardcoded list, not runtime-derived: coupling the e2e build to the app
 * build would invert the dependency direction. When a new public route
 * lands, add it here in the same PR — the deliberate friction is the
 * regression net.
 */

const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const KC_HOST = 'localhost:8090';

// pilot1 is seeded in the clean-seed DB (V8 dev-user seed) bound to
// seed-club-1, so its `tenantRequiredGuard` admits and it lands on the
// nav-bearing `/start` shell — the post-auth counterpart to the nav-hidden
// public routes. Read-only seed user; never mutate.
const SEED_USER = 'pilot1@example.com';
const SEED_PASSWORD = 'pilot1-dev-2026!';

// publicAccess: true routes from the SPA tree (excludes /dev/primitives,
// which is opt-in tooling and not a production surface). `/auth/callback`
// is included as a bare GET — the OIDC library only processes the
// callback when it sees `?code=&state=`, so an anonymous bare visit
// renders the static "Signing in…" placeholder without side effects.
const PUBLIC_ROUTES = [
  '/',
  '/signup',
  '/scenic-flight',
  '/discovery-flight',
  '/auth/callback',
  '/auth/logout',
];

test.describe('public routes stay public — real-idp', () => {
  for (const path of PUBLIC_ROUTES) {
    test(`${path} renders without KC redirect or /api/v1/* calls`, async ({
      context,
    }, testInfo) => {
      const page = await context.newPage();
      watchConsoleErrors(page, testInfo);
      const apiCalls: string[] = [];
      page.on('request', (req: Request) => {
        const u = new URL(req.url());
        if (u.host === new URL(SPA_BASE_URL).host && u.pathname.startsWith('/api/v1/')) {
          apiCalls.push(req.url());
        }
      });

      await page.goto(path);
      await page.waitForLoadState('networkidle');

      // (a) No KC redirect. The SPA may have settled on the same path or
      // a redirected-to public path (e.g. /auth/logout → /), but never on
      // the KC host.
      expect(new URL(page.url()).host).not.toBe(KC_HOST);

      // (b) No /api/v1/* calls. Public routes don't have an authenticated
      // session so the Bearer interceptor would have nothing to attach,
      // but the gate that matters here is the prefetch suppression. Any
      // /api/v1/* call from a public-route navigation is the regression.
      expect(apiCalls, `unexpected /api/v1/* call from public route ${path}`).toEqual([]);

      // (c) App-shell nav-bar hidden. Every route here is `showNavBar: false`,
      // so `AppComponent`'s NavigationEnd handler keeps the chrome off. The
      // nav appearing is the R12 tautology regressing.
      await expect(
        page.locator('af-nav-bar'),
        `nav-bar leaked onto public route ${path}`,
      ).toHaveCount(0);
    });
  }
});

// (c) counterpart: the nav-bar IS present once a tenant-bearing principal is
// authenticated on a `showNavBar: true` route. Proves the mechanism toggles
// BOTH ways under the real IdP — the public-route count-0 above would pass
// vacuously if the nav simply never rendered.
//
// pilot1 is tenant-bound (seed-club-1), so `oidc-session-bridge` warm-navigates
// it to the DEFAULT_POST_LOGIN_ROUTE `/start` on callback and `tenantRequiredGuard`
// admits it. We wait for that in-app landing rather than a cold `page.goto('/start')`
// — a cold navigation mid-test restarts the SPA before `checkAuth()` restores the
// session, so `/start`'s guard would bounce back to the KC authorize endpoint.
test.describe('nav-bar visible on a post-auth route — real-idp', () => {
  test('/start shows af-nav-bar after pilot1 login', async ({ page }, testInfo) => {
    watchConsoleErrors(page, testInfo);

    await page.goto('/');
    await page.getByTestId('landing-topbar-sign-in').click();
    await page.waitForURL(/\/realms\/alpenflight\//);
    await fillKcLogin(page, SEED_USER, SEED_PASSWORD);

    await page.waitForURL(/\/start(\?|$|\/)/, { timeout: 30_000 });
    await expect(page.locator('af-nav-bar')).toBeVisible();
  });
});

// The J-16 proof capture — one AlpenFlight-only (migration N/A) landing pass
// video + fullPage screenshot, tagged `journey:J-16` so the operator's
// single-bookmark gallery renders THIS journey from task 1 and accumulates as
// T-08 thickens the landing surface. Own recorded context: the video only
// flushes to disk after `ctx.close()`, so `proofVideo` runs in `finally` AFTER
// the close (see _helpers/proof-video.ts).
test.describe('landing renders on the public front door — J-16 proof', () => {
  test('[happy] the public landing renders anonymously at / under the real IdP', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    try {
      await page.goto('/');
      await page.waitForLoadState('networkidle');

      // Anonymous render, no KC redirect.
      expect(new URL(page.url()).host).not.toBe(KC_HOST);
      await expect(page.getByTestId('landing')).toBeVisible();
      await expect(page.getByTestId('landing-topbar')).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-landing.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-16',
        caption:
          'J-16 · public front door · the AlpenFlight landing renders anonymously at / under the ' +
          'real IdP — wordmark topbar + hero — with no redirect to Keycloak',
        acTag: 'happy',
      });
    }
  });
});
