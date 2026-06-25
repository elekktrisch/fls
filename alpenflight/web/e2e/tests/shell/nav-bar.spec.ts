import { expect, test } from '../_helpers/console-guard';

/**
 * Nav-bar visibility + responsive behavior + post-auth lang picker (S-097).
 *
 * Auth: the SPA boots under the `mock-auth` angular configuration that
 * seeds `SessionStore` with a synthetic SYSTEM_ADMINISTRATOR principal
 * (see `app.config.mock.ts`). The nav-bar therefore renders on any
 * route that opts in via `data: { showNavBar: true }`; routes that
 * keep the default (or opt out explicitly) stay chrome-less.
 *
 * The hidden-on-public-routes assertion is the structural close-out of
 * R12 (the `||` tautology bug in legacy `index.js:50`).
 */

test.use({ baseURL: 'http://localhost:4200' });

const HIDDEN_ROUTES = ['/', '/discovery-flight', '/scenic-flight'] as const;
const VISIBLE_ROUTE = '/clubs';

// `bootstrapPrefetch()` fires `refData.loadAll()` on every route under
// mock-auth. Stub the calls so they don't ECONNREFUSED-flood the dev
// server (which, with concurrent specs, can starve the SPA's bundle
// download and make unrelated locators flake).
test.beforeEach(async ({ page }) => {
  for (const endpoint of ['countries', 'club-states', 'location-types']) {
    await page.route(`**/api/v1/${endpoint}**`, (route) => route.fulfill({ json: [] }));
  }
});

test.describe('nav-bar visibility by route', () => {
  for (const path of HIDDEN_ROUTES) {
    test(`hidden on ${path}`, async ({ page }) => {
      await page.goto(`${path}?lang=de`);
      await expect(page.locator('html')).toHaveAttribute('lang', 'de');
      await expect(page.locator('af-nav-bar')).toHaveCount(0);
    });
  }

  test(`visible on ${VISIBLE_ROUTE} (post-auth)`, async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);
    await expect(page.locator('af-nav-bar')).toBeVisible();
  });
});

test.describe('AC-DIR-1 — responsive nav-bar', () => {
  test('inline section tabs at >=md', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);
    await expect(page.getByTestId('af-nav-section-/clubs')).toBeVisible();
    await expect(page.getByTestId('af-nav-burger')).toHaveCount(0);
  });

  test('collapses to hamburger at <md (360x640)', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    // Two pending join requests so the live badge resolves to a non-zero count,
    // proving the badge survives the collapse + reopen (S-178). Layered last so
    // it wins over the console-guard `**/api/v1/**` floor's empty-array default.
    await page.route(/\/api\/v1\/join-requests(\?|$)/, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[{},{}]' }),
    );
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);
    await expect(page.getByTestId('af-nav-burger')).toBeVisible();

    // The section tabs collapse INTO the drawer (ng-zorro portals it to <body>,
    // offscreen via translateX(-100%) while closed — `toBeHidden()` can't see
    // the transform). The collapse proof is that no section is presented in an
    // OPEN drawer: the closed drawer carries no `.ant-drawer-open`.
    await expect(page.locator('.ant-drawer-open').getByTestId('af-nav-section-/clubs')).toHaveCount(
      0,
    );

    // Reopening the burger must restore reachability: the section returns and the
    // live join-request badge rolls up onto the collapsed Masterdata trigger, so
    // a club admin still sees the pending count on mobile without expanding.
    await page.getByTestId('af-nav-burger').click();
    const drawer = page.locator('.ant-drawer-open');
    await expect(drawer.getByTestId('af-nav-section-/clubs')).toBeVisible();
    const badge = drawer.getByTestId('nav-join-requests-badge');
    await expect(badge).toBeVisible();
    await expect(badge).toHaveText('2');
  });
});

test.describe('AC-DIR-4 — post-auth language picker in nav-bar', () => {
  test('user-menu reveals the af-lang-picker molecule (>=md)', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);

    await page.getByTestId('af-nav-user').click();

    const pickerInMenu = page.locator('.ant-dropdown af-lang-picker');
    await expect(pickerInMenu).toBeVisible();

    // Picker switches locale through LocaleService — same path as the
    // landing's inline picker.
    await pickerInMenu.getByTestId('af-lang-fr').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });

  test('drawer (overflow menu) renders the picker at <md', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);

    await page.getByTestId('af-nav-burger').click();
    const pickerInDrawer = page.locator('.ant-drawer-open af-lang-picker');
    await expect(pickerInDrawer).toBeVisible();
  });
});
