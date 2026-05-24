import { expect, test, type Route } from '@playwright/test';

/**
 * Article CRUD shape. Greenfield spec (no legacy oracle — the legacy
 * AngularJS `master-data/articles` was a thin list view; the new UX is
 * the contract per S-054). Booted under the `mock-auth` Angular
 * configuration; the principal is a mocked SYSTEM_ADMINISTRATOR so the
 * mutation affordances render even though sysadmin would 403 against a
 * live backend (the role gate lives on the server per S-159, not the
 * client). All `/api/v1/*` calls are intercepted via `page.route` — no
 * live backend.
 *
 * Coverage:
 *   - List + seed-row visibility.
 *   - Create round-trip.
 *   - Edit round-trip persists across reload.
 *   - 409 on duplicate articleNumber surfaces inline.
 *   - Soft-delete removes the row.
 *   - Inactive articles are hidden by default; `includeInactive` toggle surfaces them.
 */

interface MockArticleDetail {
  id: string;
  articleNumber: string;
  articleName: string;
  articleInfo?: string;
  description?: string;
  isActive: boolean;
}

interface MockArticleListItem {
  id: string;
  articleNumber: string;
  articleName: string;
  articleInfo?: string;
  isActive: boolean;
}

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const gliderHourSeed: MockArticleDetail = {
  id: 'art-019e30c3-2c00-7001-8000-000000000001',
  articleNumber: 'A-100',
  articleName: 'Glider hour',
  articleInfo: 'per flight',
  isActive: true,
};

const inactiveTowSeed: MockArticleDetail = {
  id: 'art-019e30c3-2c00-7001-8000-000000000002',
  articleNumber: 'A-OLD',
  articleName: 'Retired tow rate',
  isActive: false,
};

const mockClubs = [
  {
    id: CLUB_A_ID,
    name: 'Test Club A',
    slug: 'test-club-a',
    countryId: CH_COUNTRY_ID,
    clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
  },
];

function toListItem(d: MockArticleDetail): MockArticleListItem {
  const item: MockArticleListItem = {
    id: d.id,
    articleNumber: d.articleNumber,
    articleName: d.articleName,
    isActive: d.isActive,
  };
  if (d.articleInfo !== undefined) item.articleInfo = d.articleInfo;
  return item;
}

async function stubReferenceData(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/countries**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: CH_COUNTRY_ID, iso2Code: 'CH', name: 'Switzerland' }]),
    }),
  );
  await page.route('**/api/v1/club-states**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubs),
    }),
  );
}

function setupArticlesBackend(items: MockArticleDetail[]) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/articles\/(art-[^/]+)$/);
    const includeInactive = url.searchParams.get('includeInactive') === 'true';

    if (method === 'GET' && path === '/api/v1/articles') {
      const visible = includeInactive ? items : items.filter((a) => a.isActive);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(visible.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = items.find((a) => a.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/articles') {
      const body = req.postDataJSON() as Omit<MockArticleDetail, 'id'>;
      if (body.articleNumber && items.some((a) => a.articleNumber === body.articleNumber)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      const created: MockArticleDetail = {
        ...body,
        isActive: body.isActive ?? true,
        id: `art-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
      };
      items.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/articles/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as Omit<MockArticleDetail, 'id'>;
      const idx = items.findIndex((a) => a.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      if (
        body.articleNumber &&
        items.some((a, i) => i !== idx && a.articleNumber === body.articleNumber)
      ) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      const prev = items[idx]!;
      items[idx] = { ...prev, ...body, id: prev.id };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items[idx]),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = items.findIndex((a) => a.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      items.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  };
}

test('articles: lists the seeded row at /articles', async ({ page }) => {
  const items: MockArticleDetail[] = [{ ...gliderHourSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/articles**', setupArticlesBackend(items));

  await page.goto('/articles');

  await expect(page.locator('h1')).toHaveText('Articles');
  await expect(page.getByTestId('articles-table')).toBeVisible();
  await expect(page.getByTestId(`articles-row-${gliderHourSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`articles-row-${gliderHourSeed.id}`)).toContainText('A-100');
  await expect(page.getByTestId(`articles-row-${gliderHourSeed.id}`)).toContainText('Glider hour');
});

test('articles: inactive rows are hidden by default and surface with the toggle', async ({
  page,
}) => {
  const items: MockArticleDetail[] = [{ ...gliderHourSeed }, { ...inactiveTowSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/articles**', setupArticlesBackend(items));

  await page.goto('/articles');
  await expect(page.getByTestId(`articles-row-${gliderHourSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`articles-row-${inactiveTowSeed.id}`)).toHaveCount(0);

  await page.getByTestId('articles-include-inactive').check();
  await expect(page.getByTestId(`articles-row-${inactiveTowSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`articles-badge-inactive-${inactiveTowSeed.id}`)).toBeVisible();
});

test('articles: creating a new article appears in the list', async ({ page }) => {
  const items: MockArticleDetail[] = [{ ...gliderHourSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/articles**', setupArticlesBackend(items));

  await page.goto('/articles');
  await page.getByTestId('articles-new-button').locator('button').click();

  await expect(page).toHaveURL('/articles/new');
  await page.locator('#ArticleNumber').fill('A-200');
  await page.locator('#ArticleName').fill('Tow service');
  await page.getByTestId('articles-save-button').locator('button').click();

  await expect(page).toHaveURL('/articles');
  await expect(
    page.locator('[data-testid^="articles-row-"]').filter({ hasText: 'Tow service' }),
  ).toBeVisible();
});

test('articles: editing the seeded row updates the list (UI round-trip)', async ({ page }) => {
  const items: MockArticleDetail[] = [{ ...gliderHourSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/articles**', setupArticlesBackend(items));

  await page.goto('/articles');
  await page.getByTestId(`articles-row-${gliderHourSeed.id}`).click();

  await expect(page).toHaveURL(/\/articles\/.+\/edit$/);
  await page.locator('#ArticleName').fill('Glider hour (renamed)');
  await page.getByTestId('articles-save-button').locator('button').click();

  await expect(page).toHaveURL('/articles');
  await expect(page.getByTestId(`articles-row-${gliderHourSeed.id}`)).toContainText(
    'Glider hour (renamed)',
  );

  await page.reload();
  await page.getByTestId(`articles-row-${gliderHourSeed.id}`).click();
  await expect(page).toHaveURL(/\/articles\/.+\/edit$/);
  await expect(page.locator('#ArticleName')).toHaveValue('Glider hour (renamed)');
});

test('articles: 409 on duplicate articleNumber surfaces inline', async ({ page }) => {
  const items: MockArticleDetail[] = [{ ...gliderHourSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/articles**', setupArticlesBackend(items));

  await page.goto('/articles/new');
  await page.locator('#ArticleNumber').fill(gliderHourSeed.articleNumber);
  await page.locator('#ArticleName').fill('Other glider hour');
  await page.getByTestId('articles-save-button').locator('button').click();

  await expect(page.getByTestId('articles-save-error')).toBeVisible();
  await expect(page.getByTestId('articles-save-error')).toContainText('already in use');
});

test('articles: invalid (blank) number keeps Save disabled', async ({ page }) => {
  const items: MockArticleDetail[] = [{ ...gliderHourSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/articles**', setupArticlesBackend(items));

  await page.goto('/articles/new');
  const number = page.locator('#ArticleNumber');
  await number.fill('');
  await number.blur();

  await expect(page.getByTestId('articles-save-button').locator('button')).toBeDisabled();
});

test('articles: soft-delete removes the row from the rendered list', async ({ page }) => {
  const items: MockArticleDetail[] = [{ ...gliderHourSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/articles**', setupArticlesBackend(items));

  await page.goto('/articles');
  await expect(page.getByTestId(`articles-row-${gliderHourSeed.id}`)).toBeVisible();

  page.once('dialog', (d) => d.accept());
  await page.getByTestId(`articles-kebab-${gliderHourSeed.id}`).click();
  await page.getByTestId(`articles-delete-${gliderHourSeed.id}`).click();

  await expect(page.getByTestId(`articles-row-${gliderHourSeed.id}`)).toHaveCount(0);
});
