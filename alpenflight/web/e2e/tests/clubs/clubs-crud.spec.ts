import { expect, test, type Route } from '@playwright/test';

/**
 * Clubs CRUD shape. Mocks the backend via `page.route` so the spec runs
 * in CI without a live Spring + Postgres stack. Real-backend e2e against
 * a logged-in OIDC session lands with the real-OIDC Playwright project
 * follow-up (S-021); the SPA is booted under the `mock-auth` angular
 * configuration here, which only stamps `Bearer mock-sysadmin` on
 * `/api/v1/*` calls — the live backend would reject it, but every
 * request in this file is intercepted by the route stub before reaching
 * the network.
 *
 * Parity port of legacy `e2e/tests/masterdata/28-club-crud.spec.ts` —
 * observable CRUD behavior only. Role-matrix + login-flow assertions
 * land alongside the real-OIDC Playwright project.
 */

interface MockClub {
  id: string;
  name: string;
  slug: string;
  clubKey: string;
  publicRegistrationEnabled: boolean;
  countryId: string;
  clubStateId: string;
}

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const DE_COUNTRY_ID = '019e2e15-2c00-743a-8000-00000000043a';
const ACTIVE_CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';

const seedClub: MockClub = {
  id: '019e30c3-2c00-7001-8000-000000000001',
  name: 'Seed Club',
  slug: 'seed-club-1',
  clubKey: 'SEED',
  publicRegistrationEnabled: false,
  countryId: CH_COUNTRY_ID,
  clubStateId: ACTIVE_CLUB_STATE_ID,
};

const mockCountries = [
  { id: CH_COUNTRY_ID, iso2Code: 'CH', name: 'Switzerland' },
  { id: DE_COUNTRY_ID, iso2Code: 'DE', name: 'Germany' },
];

const mockClubStates = [{ id: ACTIVE_CLUB_STATE_ID, code: 'ACTIVE', name: 'Active' }];

async function stubReferenceData(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/countries**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockCountries),
    }),
  );
  await page.route('**/api/v1/club-states**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubStates),
    }),
  );
}

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
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');

  await expect(page.locator('h1')).toHaveText('Clubs');
  await expect(page.getByTestId('clubs-table')).toBeVisible();
  await expect(page.getByTestId('club-row-seed-club-1')).toBeVisible();
  await expect(page.getByTestId('club-row-seed-club-1')).toHaveText('Seed Club');
});

test('clubs: editing the seeded row updates the list', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  await page.getByTestId('club-row-seed-club-1').click();

  await expect(page).toHaveURL(/\/clubs\/.+\/edit$/);
  await page.locator('#clubName').fill('Mountain Soaring');
  await page.getByTestId('clubs-save-button').click();

  await expect(page).toHaveURL('/clubs');
  await expect(page.getByTestId('club-row-seed-club-1')).toHaveText('Mountain Soaring');

  // Round-trip persistence: full page reload tears down the providedIn:root
  // ClubsStore, so when the page comes back the store re-bootstraps and
  // calls listClubs() against the mock — proving the PUT landed server-side
  // (mock-side here) rather than only patching the in-memory entity map.
  await page.reload();
  await page.getByTestId('club-row-seed-club-1').click();
  await expect(page).toHaveURL(/\/clubs\/.+\/edit$/);
  await expect(page.locator('#clubName')).toHaveValue('Mountain Soaring');
});

test('clubs: creating a new club appears in the list', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  await page.getByRole('button', { name: 'New club' }).click();

  await expect(page).toHaveURL('/clubs/new');
  await page.locator('#clubName').fill('Alps Gliding');
  await page.locator('#clubSlug').fill('alps-gliding');
  await page.locator('#clubKey').fill('ALP');
  await page.getByTestId('clubs-country-select').locator('nz-select').click();
  await page.getByRole('option', { name: 'Switzerland' }).click();
  await page.getByTestId('clubs-club-state-select').locator('nz-select').click();
  await page.getByRole('option', { name: 'Active' }).click();
  await page.getByTestId('clubs-save-button').click();

  await expect(page).toHaveURL('/clubs');
  await expect(page.getByTestId('club-row-alps-gliding')).toHaveText('Alps Gliding');
});

test('clubs: country picker is populated and a non-default country persists', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  await page.getByRole('button', { name: 'New club' }).click();
  await expect(page).toHaveURL('/clubs/new');

  await page.locator('#clubName').fill('Bavarian Soaring');
  await page.locator('#clubSlug').fill('bavarian-soaring');
  await page.locator('#clubKey').fill('BAV');

  // Country picker is populated from the seed (CH + DE both visible).
  await page.getByTestId('clubs-country-select').locator('nz-select').click();
  await expect(page.getByRole('option', { name: 'Switzerland' })).toBeVisible();
  await expect(page.getByRole('option', { name: 'Germany' })).toBeVisible();
  await page.getByRole('option', { name: 'Germany' }).click();

  await page.getByTestId('clubs-club-state-select').locator('nz-select').click();
  await page.getByRole('option', { name: 'Active' }).click();

  await page.getByTestId('clubs-save-button').click();
  await expect(page).toHaveURL('/clubs');

  // Persistence round-trip: the saved club's countryId is the German UUID.
  await expect(page.getByTestId('club-row-bavarian-soaring')).toBeVisible();
  const created = clubs.find((c) => c.slug === 'bavarian-soaring');
  expect(created?.countryId).toBe(DE_COUNTRY_ID);
});

test('clubs: 409 on duplicate slug surfaces as a save error', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs/new');
  await page.locator('#clubName').fill('Conflict Club');
  await page.locator('#clubSlug').fill(seedClub.slug);
  await page.locator('#clubKey').fill('DUP');
  await page.getByTestId('clubs-country-select').locator('nz-select').click();
  await page.getByRole('option', { name: 'Switzerland' }).click();
  await page.getByTestId('clubs-club-state-select').locator('nz-select').click();
  await page.getByRole('option', { name: 'Active' }).click();
  await page.getByTestId('clubs-save-button').click();

  await expect(page.getByTestId('clubs-save-error')).toBeVisible();
  await expect(page.getByTestId('clubs-save-error')).toContainText('already in use');
});

// S-007 — inline-validation contract: sync validators surface per-keystroke
// next to the offending control, without waiting for submit-click. Covers
// AC-DIR-1 from the vision amendment via the reference form.
test('clubs: invalid slug shows an inline field error before submit', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs/new');
  await page.locator('#clubName').fill('Test Club');
  await page.locator('#clubKey').fill('TST');

  // Type a slug that violates the pattern (uppercase) AND is too short.
  // The control is `touched` only after blur, so focus a sibling field
  // afterwards to mirror the convention "errors render once the user has
  // engaged the field" (touched gate avoids first-paint noise).
  const slug = page.locator('#clubSlug');
  await slug.fill('AB');
  await slug.blur();

  // Submit must be disabled while the form is invalid.
  await expect(page.getByTestId('clubs-save-button')).toBeDisabled();

  // The inline error renders next to the field via <af-field-errors>; the
  // mapped error key is `common.errors.pattern` (the canonical placeholder
  // until S-005 wires the i18n layer in).
  await expect(page.locator('af-field-errors').filter({ hasText: 'pattern' })).toBeVisible();
});

// S-007 — async validator surfaces a duplicate slug *before* the user clicks
// save. Server 409 stays the authoritative gate; this is the UX nicety.
test('clubs: client-side async validator flags a duplicate slug before submit', async ({
  page,
}) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  // Wait for the list load to populate ClubsStore — async validator
  // probes the in-memory entity map.
  await expect(page.getByTestId('club-row-seed-club-1')).toBeVisible();

  await page.getByRole('button', { name: 'New club' }).click();
  await expect(page).toHaveURL('/clubs/new');

  await page.locator('#clubName').fill('Conflict Pre-check');
  await page.locator('#clubKey').fill('CPC');
  const slug = page.locator('#clubSlug');
  await slug.fill(seedClub.slug); // 'seed-club-1' is already taken
  await slug.blur();

  // Save button disabled because async validator flagged duplicate.
  await expect(page.getByTestId('clubs-save-button')).toBeDisabled();
  await expect(page.locator('af-field-errors').filter({ hasText: 'duplicate' })).toBeVisible();
});
