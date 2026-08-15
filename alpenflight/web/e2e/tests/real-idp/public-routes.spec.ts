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

const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const KC_HOST = 'localhost:8090';

const SEED_USER = 'pilot1@example.com';
const SEED_PASSWORD = 'pilot1-dev-2026!';

interface PublicRoute {
  readonly path: string;
  readonly renders?: string;
  readonly anonymousReads?: readonly string[];
  readonly expectedConsoleErrors?: RegExp;
}

const REGISTRATION_CLOSED_SEED_CLUB_SLUG = 'seed-club-1';

const PUBLIC_ROUTES: readonly PublicRoute[] = [
  { path: '/' },
  { path: '/signup' },
  {
    path: scenicFlightPath(REGISTRATION_CLOSED_SEED_CLUB_SLUG),
    renders: testId.scenicPage,
    anonymousReads: [publicApi.club(REGISTRATION_CLOSED_SEED_CLUB_SLUG)],
    expectedConsoleErrors: /\b403\b/,
  },
  {
    path: discoveryFlightPath(REGISTRATION_CLOSED_SEED_CLUB_SLUG),
    renders: testId.discoveryPage,
    anonymousReads: [
      publicApi.club(REGISTRATION_CLOSED_SEED_CLUB_SLUG),
      publicApi.discoveryDays(REGISTRATION_CLOSED_SEED_CLUB_SLUG),
    ],
    expectedConsoleErrors: /\b403\b/,
  },
  { path: '/auth/callback' },
  { path: '/auth/logout' },
];

test.describe('public routes stay public — real-idp', () => {
  for (const route of PUBLIC_ROUTES) {
    test(`${route.path} renders anonymously — no KC redirect, no undeclared or bearer-carrying /api/v1 call, no nav-bar`, async ({
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

      expect(new URL(page.url()).host).not.toBe(KC_HOST);

      if (route.renders !== undefined) {
        await expect(
          page.getByTestId(route.renders),
          `public route ${route.path} rendered nothing`,
        ).toBeVisible();
      }

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
      expect(bearerCalls, `public route ${route.path} attached an Authorization header`).toEqual(
        [],
      );

      await expect(
        page.locator('af-nav-bar'),
        `nav-bar leaked onto public route ${route.path}`,
      ).toHaveCount(0);
    });
  }
});

test.describe('nav-bar visible on a post-auth route — the positive control for the public-route nav-absence above (real-idp)', () => {
  test('/start shows af-nav-bar after pilot1 login, so the public-route count-0 cannot pass vacuously', async ({
    page,
  }, testInfo) => {
    watchConsoleErrors(page, testInfo);

    await page.goto('/');
    await page.getByTestId('landing-topbar-sign-in').click();
    await page.waitForURL(/\/realms\/alpenflight\//);
    await fillKcLogin(page, SEED_USER, SEED_PASSWORD);

    await page.waitForURL(/\/start(\?|$|\/)/, { timeout: 30_000 });
    await expect(page.locator('af-nav-bar')).toBeVisible();
  });
});

test.describe("landing renders on the public front door — J-31's proof over J-16's screen", () => {
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
        journey: 'J-31',
        caption:
          'J-31 · comment sweep · the public landing still renders anonymously at / under the ' +
          'real IdP — wordmark topbar + hero, no redirect to Keycloak, no console errors — over a ' +
          'tree whose every human-written comment was deleted and whose understanding now lives ' +
          'in the names',
        acTag: 'happy',
      });
    }
  });
});
