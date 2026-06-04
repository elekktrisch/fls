import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

import { fillKcLogin } from './_helpers/kc-form';

/**
 * J-3 — Dashboard / home (`/start`) role variants + live updates, REAL-IDP family.
 *
 * STRUCTURE-ONLY STUB (T-01). This file commits the SCREEN SHAPE for the J-3
 * dashboard: stable `data-testid` selectors for the three role variants, the
 * "Pilot view" admin→pilot fallback toggle, the club-admin + sysadmin MVP tiles,
 * the SSE live-update tile, and the SSE Bearer-auth reject. Assertions here are
 * deliberately THIN (`toBeVisible` placeholders + `.skip`-guarded flows where the
 * feature isn't built yet) — T-16 thickens every case to full real assertions
 * against the showcase seed (counts non-zero, ≥2 clubs in the sysadmin aggregate,
 * the live tile changing without reload). The login flow + selectors are REAL so
 * the feature tasks (T-07..T-11) and T-16 wire to exactly these anchors.
 *
 * Why a NEW file rather than extending `tests/start/start.spec.ts`: that spec
 * lives under `tests/start/` which the Playwright config routes to the `chromium`
 * (mock-auth) project — `tests/!(real-idp)/**`. Its S-165 pilot assertions are
 * mock-stubbed (`page.route`) and must stay there, green and untouched. The J-3
 * variants are a REAL-IDP family (role-switched login against live Keycloak), so
 * they belong under `tests/real-idp/**` (the `real-idp` project) alongside the
 * other real-chain proof specs and their login harness. T-16 lands the proof
 * video here via the `proofVideo()` helper.
 *
 * Login harness: mirrors the bearer-capture login in `_helpers/flight-parity-
 * fixture.ts` / `_helpers/two-club-fixture.ts` — drive the seeded principal
 * through the SPA's real Keycloak redirect-login, then land on `/start`. Uses the
 * Flyway+realm-seeded principals (NOT a freshly-registered `e2e-…` user) so each
 * role variant is reachable deterministically without provisioning:
 *   - PILOT                → `pilot1@example.com`
 *   - CLUB_ADMINISTRATOR   → `clubadmin4@example.com` (clubadmin1/2/3 are reserved
 *     as the disjoint J-0c/J-1/J-2 migration principals; clubadmin4 is free for J-3)
 *   - SYSTEM_ADMINISTRATOR → `sysadmin@example.com`
 */

interface SeededPrincipal {
  username: string;
  password: string;
}

const PILOT: SeededPrincipal = {
  username: 'pilot1@example.com',
  password: 'pilot1-dev-2026!',
};
const CLUB_ADMIN: SeededPrincipal = {
  username: 'clubadmin4@example.com',
  password: 'clubadmin4-dev-2026!',
};
const SYSADMIN: SeededPrincipal = {
  username: 'sysadmin@example.com',
  password: 'sysadmin-dev-2026!',
};

/**
 * Log a seeded principal in through the real Keycloak redirect flow and land on
 * `/start`. Returns the authenticated page. Mirrors the redirect-login dance in
 * the J-2 flight-parity fixture: click the landing sign-in, fill the KC form,
 * wait for the post-login redirect back to the SPA, then navigate to `/start`.
 *
 * (Thin for T-01: it logs in and routes to `/start`. T-16 may capture the Bearer
 * here — as `bearerFromFlightsList` does — to drive the SSE-auth `request.get`
 * and the second-context flight-create that fires the live `flight.created`.)
 */
async function loginAsRole(
  browser: Browser,
  baseURL: string,
  principal: SeededPrincipal,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, principal.username, principal.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await page.goto('/start?lang=en');
  return { context, page };
}

test.describe('J-3 dashboard (/start) — role variants [structure stub]', () => {
  // ── Pilot variant (S-165 baseline, re-asserted on the real chain) ─────────
  test('pilot principal renders the pilot variant container', async ({ browser, baseURL }) => {
    const { context, page } = await loginAsRole(browser, baseURL!, PILOT);
    try {
      // Anchor: the pilot variant container the role-switch shell (T-07) renders
      // by default. T-16 thickens to greeting + populated last-flight card + the
      // quick-action routes (the S-165 assertions, now on the real chain).
      await expect(page.getByTestId('start-variant-pilot')).toBeVisible();
      await expect(page.getByTestId('start-greeting')).toBeVisible();
      // A pilot must NOT see admin/sysadmin tiles (role-gated FE, AC6).
      await expect(page.getByTestId('start-variant-clubadmin')).toHaveCount(0);
      await expect(page.getByTestId('start-variant-sysadmin')).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  // ── Club-admin variant (S-166) ────────────────────────────────────────────
  test('club-admin principal renders the club-admin variant + its two MVP tiles', async ({
    browser,
    baseURL,
  }) => {
    const { context, page } = await loginAsRole(browser, baseURL!, CLUB_ADMIN);
    try {
      // Anchor: the club-admin variant container (T-07 shell → T-09 component).
      await expect(page.getByTestId('start-variant-clubadmin')).toBeVisible();
      // The two MVP tiles (T-08 endpoints → T-09 tiles). T-16 thickens to the
      // tenant-scoped counts being non-zero from the showcase seed.
      await expect(page.getByTestId('start-tile-today-flights')).toBeVisible();
      await expect(page.getByTestId('start-tile-pending-validation')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  test('admin "Pilot view" toggle falls back to the pilot variant', async ({
    browser,
    baseURL,
  }) => {
    const { context, page } = await loginAsRole(browser, baseURL!, CLUB_ADMIN);
    try {
      await expect(page.getByTestId('start-variant-clubadmin')).toBeVisible();
      // The admin→pilot fallback control (T-07). Clicking it swaps the rendered
      // variant to the pilot hero without leaving `/start`.
      const toggle = page.getByTestId('start-pilot-view-toggle');
      await expect(toggle).toBeVisible();
      await toggle.click();
      await expect(page.getByTestId('start-variant-pilot')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  // ── Sysadmin variant (S-167) ────────────────────────────────────────────────
  test('sysadmin principal renders the sysadmin variant + cross-tenant tiles + tenant-enter', async ({
    browser,
    baseURL,
  }) => {
    const { context, page } = await loginAsRole(browser, baseURL!, SYSADMIN);
    try {
      // Anchor: the sysadmin variant container (T-07 shell → T-11 component).
      await expect(page.getByTestId('start-variant-sysadmin')).toBeVisible();
      // Cross-tenant aggregate tiles (T-10 endpoint → T-11 tiles). T-16 thickens
      // to the aggregate spanning ≥2 showcase clubs.
      await expect(page.getByTestId('start-tile-total-clubs')).toBeVisible();
      await expect(page.getByTestId('start-tile-total-users')).toBeVisible();
      await expect(page.getByTestId('start-tile-total-flights')).toBeVisible();
      // The control that enters a tenant context (T-11).
      await expect(page.getByTestId('start-tenant-enter')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  // ── SSE live update (S-176) ──────────────────────────────────────────────────
  // The today-flights tile carries the live-update testid so T-16 can assert it
  // changes without a reload. STUB flow: open the dashboard in one context, create
  // a flight in a SECOND context (firing `flight.created` on the principal's
  // `/api/v1/me/events` channel), and wait for the tile to change in the first.
  // Skipped until the channel (T-04/T-05/T-06) + the live tile (T-09) exist.
  test.skip('today-flights tile updates live on flight.created without reload', async ({
    browser,
    baseURL,
  }) => {
    const { context: adminCtx, page: adminPage } = await loginAsRole(browser, baseURL!, CLUB_ADMIN);
    try {
      const tile = adminPage.getByTestId('start-tile-today-flights');
      await expect(tile).toBeVisible();
      const before = (await tile.textContent())?.trim() ?? '';

      // T-16: in a SECOND context, create a flight for the same club (the publish
      // point, T-05, fires `flight.created` to the principal's bus connections).
      // Then assert the first context's tile changes WITHOUT a page reload — the
      // DOM changes, not a network poll (AC4).
      const { context: actorCtx, page: actorPage } = await loginAsRole(
        browser,
        baseURL!,
        CLUB_ADMIN,
      );
      try {
        // …create-flight flow lands here (drive the /flights/new wizard or POST
        // /api/v1/flights with the captured Bearer)…
        await expect(actorPage).toHaveURL(/\/start/);
      } finally {
        await actorCtx.close();
      }

      await expect(tile).not.toHaveText(before);
    } finally {
      await adminCtx.close();
    }
  });

  // ── SSE Bearer auth (S-176, key-error) ────────────────────────────────────────
  // The stream requires a valid Bearer — an anonymous (no-token) connection is
  // rejected (401, no anonymous stream). Real `request.get` with NO auth header
  // against the live endpoint; this case is real (not skipped) the moment T-04
  // ships the endpoint. Until then the endpoint 404s, which still proves "no
  // anonymous stream is served" — so accept either 401 or 404 here; T-16 tightens
  // to exactly 401 once T-04 has landed.
  test('GET /api/v1/me/events with no token is rejected (no anonymous stream)', async ({
    request,
  }) => {
    const res = await request.get('/api/v1/me/events', {
      headers: { accept: 'text/event-stream' },
    });
    expect(res.ok()).toBeFalsy();
    expect([401, 403, 404]).toContain(res.status());
  });
});
