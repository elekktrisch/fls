import { expect, test, type Route } from '@playwright/test';

/**
 * S-048 walking-skeleton CRUD shape. Mocks the backend via `page.route` so
 * the spec runs in CI without a live Spring + Postgres stack. Real-backend
 * e2e is verified manually against `./gradlew bootRun --args='--spring.profiles.active=mock-auth'`
 * + the SPA proxy until a compose `next/server` service + dedicated CI job
 * land (see Risks in docs/modernization/stories/S-048-clubs-crud.md).
 *
 * Parity port of legacy `e2e/tests/masterdata/28-club-crud.spec.ts` —
 * observable CRUD behavior only. Role-matrix + login-flow assertions are
 * excluded under mock-auth (frontmatter `parity_excluded:` lists why) and
 * re-port at S-019/S-020/S-021.
 */

interface MockClub {
  id: string;
  name: string;
  slug: string;
  clubKey: string;
  publicRegistrationEnabled: boolean;
}

const seedClub: MockClub = {
  id: '019e30c3-2c00-7001-8000-000000000001',
  name: 'Seed Club',
  slug: 'seed-club-1',
  clubKey: 'SEED',
  publicRegistrationEnabled: false,
};

function setupClubsBackend(clubs: MockClub[]) {
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/clubs\/([^/]+)$/);

    if (method === 'GET' && path === '/api/v1/clubs') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(clubs),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = clubs.find((c) => c.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/clubs') {
      const body = req.postDataJSON() as Omit<MockClub, 'id'>;
      if (clubs.some((c) => c.slug === body.slug)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      const created: MockClub = { ...body, id: 'new-' + Date.now() };
      clubs.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/clubs/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as Omit<MockClub, 'id' | 'clubKey'>;
      const idx = clubs.findIndex((c) => c.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      if (clubs.some((c, i) => i !== idx && c.slug === body.slug)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      clubs[idx] = { ...clubs[idx], ...body } as MockClub;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(clubs[idx]),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = clubs.findIndex((c) => c.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      clubs.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  };
}

test('clubs: lists the seeded row at /clubs', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');

  await expect(page.locator('h1')).toHaveText('Clubs');
  await expect(page.getByTestId('clubs-table')).toBeVisible();
  await expect(page.getByTestId('club-row-seed-club-1')).toBeVisible();
  await expect(page.getByTestId('club-row-seed-club-1')).toHaveText('Seed Club');
});

test('clubs: editing the seeded row updates the list', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  await page.getByTestId('club-row-seed-club-1').click();

  await expect(page).toHaveURL(/\/clubs\/.+\/edit$/);
  await page.locator('#clubName').fill('Mountain Soaring');
  await page.getByTestId('clubs-save-button').click();

  await expect(page).toHaveURL('/clubs');
  await expect(page.getByTestId('club-row-seed-club-1')).toHaveText('Mountain Soaring');

  // Round-trip persistence: navigate back to the edit form and confirm the
  // store re-hydrates from the (mocked) server, not just from optimistic
  // in-memory state. Parity invariant from legacy clubs-crud.spec.ts:86-89.
  await page.getByTestId('club-row-seed-club-1').click();
  await expect(page).toHaveURL(/\/clubs\/.+\/edit$/);
  await expect(page.locator('#clubName')).toHaveValue('Mountain Soaring');
});

test('clubs: creating a new club appears in the list', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  await page.getByRole('button', { name: 'New club' }).click();

  await expect(page).toHaveURL('/clubs/new');
  await page.locator('#clubName').fill('Alps Gliding');
  await page.locator('#clubSlug').fill('alps-gliding');
  await page.locator('#clubKey').fill('ALP');
  await page.getByTestId('clubs-save-button').click();

  await expect(page).toHaveURL('/clubs');
  await expect(page.getByTestId('club-row-alps-gliding')).toHaveText('Alps Gliding');
});

test('clubs: 409 on duplicate slug surfaces as a save error', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs/new');
  await page.locator('#clubName').fill('Conflict Club');
  await page.locator('#clubSlug').fill(seedClub.slug);
  await page.locator('#clubKey').fill('DUP');
  await page.getByTestId('clubs-save-button').click();

  await expect(page.getByTestId('clubs-save-error')).toBeVisible();
  await expect(page.getByTestId('clubs-save-error')).toContainText('already in use');
});
