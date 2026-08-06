import { type Request } from '@playwright/test';
import { test, expect, allowConsoleErrors, watchConsoleErrors } from '../_helpers/console-guard';
import {
  discoveryFlightPath,
  publicApi,
  scenicFlightPath,
  testId,
} from '../public-registration/_helpers/public-registration-form';
import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

/**
 * Public routes stay public against the live IdP.
 *
 * Each route flagged `data.publicAccess: true` in the SPA route tree
 * MUST render anonymously without (a) redirecting to Keycloak's authorize
 * endpoint, (b) making any `/api/v1` call it has not DECLARED as an anonymous
 * public read — `session.store.ts`'s `bootstrapPrefetch()` is gated on
 * `isAuthenticated()`, and no route may declare a non-public path, so an
 * authenticated prefetch is a regression on every entry — and (c) showing the
 * app-shell nav-bar, which every one of these routes suppresses via
 * `data.showNavBar: false` (the structural close-out of the legacy `||`
 * tautology, R12). The nav appearing on a public route is exactly that
 * regression.
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

interface PublicRoute {
  readonly path: string;
  /**
   * A testid the route must render. The three checks below are all negative —
   * without this they pass on a route that mounted nothing at all.
   */
  readonly renders?: string;
  /**
   * The EXACT `/api/v1/public/**` pathnames this route legitimately reads while
   * anonymous. Declared per route and matched exactly, never as a suite-wide
   * `/api/v1/public/` exemption: the default is none, so every other entry
   * still fails on ANY `/api/v1` call, and no entry can declare a path outside
   * the anonymous prefix. A declared read that stops firing fails too, so an
   * exemption cannot outlive the call it was granted for.
   */
  readonly anonymousReads?: readonly string[];
  /** Browser errors this route's own anonymous read legitimately produces. */
  readonly expectedConsoleErrors?: RegExp;
}

/**
 * The clean seed's only slugged club (`V5__clubs_walking_skeleton.sql:30`). Its
 * `public_registration_enabled` is false, so both forms resolve it to the
 * unavailable panel — which is beside the point here: what this spec pins is
 * that the ROUTE is anonymously reachable and chrome-free. Whether the club
 * behind the slug accepts registrations is `public-registration-parity.spec.ts`.
 */
const SEED_CLUB_SLUG = 'seed-club-1';

// publicAccess: true routes from the SPA tree (excludes /dev/primitives,
// which is opt-in tooling and not a production surface). `/auth/callback`
// is included as a bare GET — the OIDC library only processes the
// callback when it sees `?code=&state=`, so an anonymous bare visit
// renders the static "Signing in…" placeholder without side effects.
//
// The registration routes are listed in their SLUGGED form because that is the
// form carrying `publicAccess: true`. Bare `/discovery-flight` and
// `/scenic-flight` are `redirectTo` entries that land on `/` — already the first
// entry here, so listing them would re-assert the landing page while reading as
// coverage of the forms; the redirect itself is asserted in
// `public-registration-parity.spec.ts`.
const PUBLIC_ROUTES: readonly PublicRoute[] = [
  { path: '/' },
  { path: '/signup' },
  {
    path: scenicFlightPath(SEED_CLUB_SLUG),
    renders: testId.scenicPage,
    anonymousReads: [publicApi.club(SEED_CLUB_SLUG)],
    expectedConsoleErrors: /\b403\b/,
  },
  {
    path: discoveryFlightPath(SEED_CLUB_SLUG),
    renders: testId.discoveryPage,
    anonymousReads: [publicApi.club(SEED_CLUB_SLUG), publicApi.discoveryDays(SEED_CLUB_SLUG)],
    expectedConsoleErrors: /\b403\b/,
  },
  { path: '/auth/callback' },
  { path: '/auth/logout' },
];

test.describe('public routes stay public — real-idp', () => {
  for (const route of PUBLIC_ROUTES) {
    test(`${route.path} renders without KC redirect or an authenticated prefetch`, async ({
      context,
    }, testInfo) => {
      const page = await context.newPage();
      watchConsoleErrors(page, testInfo);
      if (route.expectedConsoleErrors) {
        allowConsoleErrors(testInfo, route.expectedConsoleErrors);
      }
      const apiCalls: string[] = [];
      const bearerCalls: string[] = [];
      page.on('request', (req: Request) => {
        const u = new URL(req.url());
        if (u.host !== new URL(SPA_BASE_URL).host || !u.pathname.startsWith('/api/v1/')) {
          return;
        }
        apiCalls.push(u.pathname);
        if (req.headers()['authorization'] !== undefined) {
          bearerCalls.push(u.pathname);
        }
      });

      await page.goto(route.path);
      await page.waitForLoadState('networkidle');

      // (a) No KC redirect. The SPA may have settled on the same path or
      // a redirected-to public path (e.g. /auth/logout → /), but never on
      // the KC host.
      expect(new URL(page.url()).host).not.toBe(KC_HOST);

      if (route.renders !== undefined) {
        await expect(
          page.getByTestId(route.renders),
          `public route ${route.path} rendered nothing`,
        ).toBeVisible();
      }

      // (b) No prefetch. The gate is `bootstrapPrefetch()` suppression, not
      // silence: a route may read the anonymous `/api/v1/public/**` surface it
      // declared above, and nothing else. Every session-scoped catalog is
      // outside that prefix, so the prefetch regression fails here regardless
      // of which route it fires on.
      const declared = route.anonymousReads ?? [];
      expect(
        apiCalls.filter((p) => !declared.includes(p)),
        `undeclared /api/v1 call from public route ${route.path}`,
      ).toEqual([]);
      for (const read of declared) {
        expect(
          apiCalls,
          `declared anonymous read no longer fires on ${route.path} — stale exemption`,
        ).toContain(read);
      }
      // No principal means nothing to attach: a Bearer on a public route is the
      // same prefetch regression seen from the request side.
      expect(bearerCalls, `public route ${route.path} attached an Authorization header`).toEqual(
        [],
      );

      // (c) App-shell nav-bar hidden. Every route here is `showNavBar: false`,
      // so `AppComponent`'s NavigationEnd handler keeps the chrome off. The
      // nav appearing is the R12 tautology regressing.
      await expect(
        page.locator('af-nav-bar'),
        `nav-bar leaked onto public route ${route.path}`,
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
