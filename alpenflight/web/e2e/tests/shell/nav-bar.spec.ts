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
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);
    await expect(page.getByTestId('af-nav-burger')).toBeVisible();
    await expect(page.getByTestId('af-nav-section-/clubs')).toHaveCount(0);
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
