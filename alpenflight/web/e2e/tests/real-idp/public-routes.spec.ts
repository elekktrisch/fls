import { type Request } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';
import { proofVideo } from './_helpers/proof-video';

/**
 * Public routes stay public against the live IdP.
 *
 * Each route flagged `data.publicAccess: true` in the SPA route tree
 * MUST render anonymously without (a) redirecting to Keycloak's authorize
 * endpoint, and (b) triggering any `/api/v1/*` call — `session.store.ts`'s
 * `bootstrapPrefetch()` is gated on `isAuthenticated()`; a regression
 * there would surface here.
 *
 * Hardcoded list, not runtime-derived: coupling the e2e build to the app
 * build would invert the dependency direction. When a new public route
 * lands, add it here in the same PR — the deliberate friction is the
 * regression net.
 */

const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const KC_HOST = 'localhost:8090';

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
    });
  }
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
