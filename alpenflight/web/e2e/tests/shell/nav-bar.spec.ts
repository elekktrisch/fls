import { expect, test } from '../_helpers/console-guard';


test.use({ baseURL: 'http://localhost:4200' });

const HIDDEN_ROUTES = ['/', '/discovery-flight', '/scenic-flight', '/signup'] as const;

const POST_AUTH_ROUTE = '/start';
const VISIBLE_ROUTE = '/clubs';

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
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);
    await expect(page.getByTestId('af-nav-section-/clubs')).toBeVisible();
    await expect(page.getByTestId('af-nav-burger')).toHaveCount(0);
  });

  test('collapses to hamburger at <md (360x640)', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await page.route(/\/api\/v1\/join-requests(\?|$)/, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[{},{}]' }),
    );
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto(`${VISIBLE_ROUTE}?lang=de`);
    await expect(page.getByTestId('af-nav-burger')).toBeVisible();

    await expect(page.locator('.ant-drawer-open').getByTestId('af-nav-section-/clubs')).toHaveCount(
      0,
    );

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
