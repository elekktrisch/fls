import { expect, test } from '../_helpers/console-guard';

test.use({ baseURL: 'http://localhost:4200' });

const PUBLIC_ROUTES_WITHOUT_NAV_BAR = [
  '/',
  '/discovery-flight',
  '/scenic-flight',
  '/signup',
] as const;

const POST_AUTH_ROUTE = '/start';
const NAV_BEARING_ROUTE = '/clubs';

const TWO_PENDING_JOIN_REQUESTS_JSON = '[{},{}]';

const REF_DATA_PREFETCHED_ON_EVERY_ROUTE = ['countries', 'club-states', 'location-types'];

test.beforeEach(async ({ page }) => {
  for (const endpoint of REF_DATA_PREFETCHED_ON_EVERY_ROUTE) {
    await page.route(`**/api/v1/${endpoint}**`, (route) => route.fulfill({ json: [] }));
  }
});

test.describe('nav-bar visibility by route', () => {
  for (const path of PUBLIC_ROUTES_WITHOUT_NAV_BAR) {
    test(`hidden on ${path}`, async ({ page }) => {
      await page.goto(`${path}?lang=de`);
      await expect(page.locator('html')).toHaveAttribute('lang', 'de');
      await expect(page.locator('af-nav-bar')).toHaveCount(0);
    });
  }

  test(`visible on ${POST_AUTH_ROUTE} (post-auth)`, async ({ page }) => {
    await page.goto(`${POST_AUTH_ROUTE}?lang=de`);
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
    await expect(page.locator('af-nav-bar')).toBeVisible();
  });
});

test.describe('AC-DIR-1 — responsive nav-bar', () => {
  test('inline section tabs at >=md', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(`${NAV_BEARING_ROUTE}?lang=de`);
    await expect(page.getByTestId('af-nav-section-/clubs')).toBeVisible();
    await expect(page.getByTestId('af-nav-burger')).toHaveCount(0);
  });

  test('collapses to hamburger at <md (360x640)', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.route(/\/api\/v1\/join-requests(\?|$)/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: TWO_PENDING_JOIN_REQUESTS_JSON,
      }),
    );
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto(`${NAV_BEARING_ROUTE}?lang=de`);
    await expect(page.getByTestId('af-nav-burger')).toBeVisible();

    const openDrawer = page.locator('.ant-drawer-open');
    const sectionInsideOpenDrawer = openDrawer.getByTestId('af-nav-section-/clubs');
    await expect(sectionInsideOpenDrawer).toHaveCount(0);

    await page.getByTestId('af-nav-burger').click();
    await expect(sectionInsideOpenDrawer).toBeVisible();
    const badge = openDrawer.getByTestId('nav-join-requests-badge');
    await expect(badge).toBeVisible();
    await expect(badge).toHaveText('2');
  });
});

test.describe('AC-DIR-4 — post-auth language picker in nav-bar', () => {
  test('user-menu reveals the af-lang-picker molecule (>=md)', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(`${NAV_BEARING_ROUTE}?lang=de`);

    await page.getByTestId('af-nav-user').click();

    const pickerInMenu = page.locator('.ant-dropdown af-lang-picker');
    await expect(pickerInMenu).toBeVisible();

    await pickerInMenu.getByTestId('af-lang-fr').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });

  test('drawer (overflow menu) renders the picker at <md', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto(`${NAV_BEARING_ROUTE}?lang=de`);

    await page.getByTestId('af-nav-burger').click();
    const pickerInDrawer = page.locator('.ant-drawer-open af-lang-picker');
    await expect(pickerInDrawer).toBeVisible();
  });
});
