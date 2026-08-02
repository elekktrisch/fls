import { type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

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
  discoveryFlightOperatorEmail: string;
  scenicFlightOperatorEmail: string;
  discoveryFlightTypeId: string | null;
}

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const DE_COUNTRY_ID = '019e2e15-2c00-743a-8000-00000000043a';
const ACTIVE_CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';
const DISCOVERY_FLIGHT_TYPE_ID = 'ft-019e30c3-2c00-7001-8000-0000000000f1';
const TOW_FLIGHT_TYPE_ID = 'ft-019e30c3-2c00-7001-8000-0000000000f2';

// The mock-auth principal's own club (`app.config.mock.ts` MOCK_CLUB_ID), so the
// club-admin-only discovery-day panel is in scope when this row is edited.
const seedClub: MockClub = {
  id: '019e30c3-2c00-7001-8000-000000000001',
  name: 'Seed Club',
  slug: 'seed-club-1',
  clubKey: 'SEED',
  publicRegistrationEnabled: false,
  countryId: CH_COUNTRY_ID,
  clubStateId: ACTIVE_CLUB_STATE_ID,
  discoveryFlightOperatorEmail: 'schnupper@seed.example',
  scenicFlightOperatorEmail: 'mitflug@seed.example',
  discoveryFlightTypeId: DISCOVERY_FLIGHT_TYPE_ID,
};

const otherClub: MockClub = {
  id: '019e30c3-2c00-7001-8000-000000000002',
  name: 'Other Club',
  slug: 'other-club',
  clubKey: 'OTHR',
  publicRegistrationEnabled: false,
  countryId: CH_COUNTRY_ID,
  clubStateId: ACTIVE_CLUB_STATE_ID,
  discoveryFlightOperatorEmail: '',
  scenicFlightOperatorEmail: '',
  discoveryFlightTypeId: null,
};

const mockFlightTypes = [
  {
    id: DISCOVERY_FLIGHT_TYPE_ID,
    flightTypeName: 'Schnupperflug',
    flightCode: 'SF',
    isForGliderFlights: true,
    isForTowFlights: false,
    isForMotorFlights: false,
    isFlightCostBalanceSelectable: false,
  },
  {
    id: TOW_FLIGHT_TYPE_ID,
    flightTypeName: 'Schlepp',
    flightCode: 'SL',
    isForGliderFlights: false,
    isForTowFlights: true,
    isForMotorFlights: false,
    isFlightCostBalanceSelectable: false,
  },
];

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
  // The session-bootstrap forkJoin pulls all reference catalogs in parallel
  // (S-049). Stubbing this empty here keeps the clubs spec self-contained
  // — the dropdown values we care about live in countries / club-states.
  await page.route('**/api/v1/location-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/flight-types', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockFlightTypes),
    }),
  );
}

interface MockDay {
  id: string;
  eventDate: string;
}

/**
 * The club-admin discovery-day resource. Tenant-scoped server-side — the path
 * carries no club id — so the mock keeps one list and mirrors the two contract
 * rules the panel has to react to: a duplicate live date is a 409, a past date
 * a 400.
 */
function stubDiscoveryDays(page: import('@playwright/test').Page, days: MockDay[]) {
  let nextId = days.length + 1;
  return page.route('**/api/v1/discovery-flight-days**', async (route) => {
    const req = route.request();
    const method = req.method();
    const path = new URL(req.url()).pathname;
    const idMatch = path.match(/^\/api\/v1\/discovery-flight-days\/([^/]+)$/);

    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(days),
      });
      return;
    }
    if (method === 'POST') {
      const { eventDate } = req.postDataJSON() as { eventDate: string };
      if (days.some((d) => d.eventDate === eventDate)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      if (eventDate < '2026-01-01') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({
            field: 'eventDate',
            message: 'Event date must not be in the past.',
          }),
        });
        return;
      }
      const created: MockDay = { id: `dfd-${nextId++}`, eventDate };
      days.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/discovery-flight-days/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = days.findIndex((d) => d.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      days.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  });
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
      const body = req.postDataJSON() as Partial<Omit<MockClub, 'id' | 'clubKey'>>;
      const idx = clubs.findIndex((c) => c.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      if (clubs.some((c, i) => i !== idx && c.slug === body.slug)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      // Full-replace, like the server: a field the payload omits is stored as
      // cleared, so a form that forgets one loses the club's value here too.
      clubs[idx] = {
        ...clubs[idx],
        ...body,
        discoveryFlightOperatorEmail: body.discoveryFlightOperatorEmail ?? '',
        scenicFlightOperatorEmail: body.scenicFlightOperatorEmail ?? '',
        discoveryFlightTypeId: body.discoveryFlightTypeId ?? null,
      } as MockClub;
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
  await stubDiscoveryDays(page, []);
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
  // Await the post-reload list re-fetch + the row's re-render before clicking:
  // under suite-level load the bootstrap GET lags the reload's load event, so a
  // bare click raced an un-rendered row (the flake this stabilises).
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === '/api/v1/clubs' &&
        r.status() === 200,
    ),
    page.reload(),
  ]);
  await expect(page.getByTestId('club-row-seed-club-1')).toHaveText('Mountain Soaring');
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
  await page.locator('nz-option-item').filter({ hasText: 'Switzerland' }).click();
  await page.getByTestId('clubs-club-state-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Active' }).click();
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
  await expect(page.locator('nz-option-item').filter({ hasText: 'Switzerland' })).toBeVisible();
  await expect(page.locator('nz-option-item').filter({ hasText: 'Germany' })).toBeVisible();
  await page.locator('nz-option-item').filter({ hasText: 'Germany' }).click();

  await page.getByTestId('clubs-club-state-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Active' }).click();

  await page.getByTestId('clubs-save-button').click();
  await expect(page).toHaveURL('/clubs');

  // Persistence round-trip: the saved club's countryId is the German UUID.
  await expect(page.getByTestId('club-row-bavarian-soaring')).toBeVisible();
  const created = clubs.find((c) => c.slug === 'bavarian-soaring');
  expect(created?.countryId).toBe(DE_COUNTRY_ID);
});

test('clubs: 409 on duplicate slug surfaces as a save error', async ({ page }, testInfo) => {
  // The duplicate-slug POST is deliberately rejected; the browser logs the 409.
  allowConsoleErrors(testInfo, /\b409\b/);
  // Race-condition shape: the client-side `slugAvailable` validator only
  // catches slugs already in the loaded entity map; the authoritative
  // duplicate guard is the server 409 (e.g. another tab created the same
  // slug between page load and submit). Seed the store empty so the client
  // validator passes, and have the POST mock return 409 unconditionally.
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    if (req.method() === 'GET' && url.pathname === '/api/v1/clubs') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      return;
    }
    if (req.method() === 'POST' && url.pathname === '/api/v1/clubs') {
      await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
      return;
    }
    await route.fallback();
  });

  await page.goto('/clubs/new');
  await page.locator('#clubName').fill('Conflict Club');
  await page.locator('#clubSlug').fill('race-condition-slug');
  await page.locator('#clubKey').fill('DUP');
  await page.getByTestId('clubs-country-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Switzerland' }).click();
  await page.getByTestId('clubs-club-state-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Active' }).click();
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
  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeDisabled();

  // The inline error renders next to the field via <af-field-errors>. Since
  // J-26 T-08 the mapped key renders TRANSLATED (no raw `common.errors.pattern`
  // text to match on), so scope the assertion to the slug field's alert.
  await expect(
    page.locator('af-form-field', { has: page.locator('#clubSlug') }).getByRole('alert'),
  ).toBeVisible();
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

  // Save button disabled because async validator flagged duplicate. The
  // duplicate error renders TRANSLATED since J-26 T-08, so scope by field.
  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeDisabled();
  await expect(
    page.locator('af-form-field', { has: page.locator('#clubSlug') }).getByRole('alert'),
  ).toBeVisible();
});

// J-17 — the club PUT is full-replace, so the edit form has to resubmit every
// field it does not display as well as the ones it does. A club's organiser
// recipients and discovery flight type are only reachable through this screen;
// a payload that drops them wipes them on the next unrelated save.
test('clubs: saving an unrelated change preserves the registration fields', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, []);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${seedClub.id}/edit`);

  await expect(page.getByTestId('clubs-discovery-operator-email').locator('input')).toHaveValue(
    'schnupper@seed.example',
  );
  await expect(page.getByTestId('clubs-scenic-operator-email').locator('input')).toHaveValue(
    'mitflug@seed.example',
  );
  await expect(page.getByTestId('clubs-discovery-flight-type-select')).toContainText(
    'Schnupperflug',
  );

  const putRequest = page.waitForRequest(
    (r) => r.method() === 'PUT' && new URL(r.url()).pathname === `/api/v1/clubs/${seedClub.id}`,
  );
  await page.locator('#clubName').fill('Renamed Only');
  await page.getByTestId('clubs-save-button').click();

  const body = (await putRequest).postDataJSON() as Record<string, unknown>;
  expect(body).toMatchObject({
    name: 'Renamed Only',
    discoveryFlightOperatorEmail: 'schnupper@seed.example',
    scenicFlightOperatorEmail: 'mitflug@seed.example',
    discoveryFlightTypeId: DISCOVERY_FLIGHT_TYPE_ID,
  });

  await expect(page).toHaveURL('/clubs');
  // The mock replaces rather than merges, so a dropped field would read as ''.
  expect(clubs[0]?.discoveryFlightOperatorEmail).toBe('schnupper@seed.example');
  expect(clubs[0]?.scenicFlightOperatorEmail).toBe('mitflug@seed.example');
  expect(clubs[0]?.discoveryFlightTypeId).toBe(DISCOVERY_FLIGHT_TYPE_ID);
});

test('clubs: a recipient list of several addresses round-trips', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, []);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${seedClub.id}/edit`);
  const field = page.getByTestId('clubs-discovery-operator-email').locator('input');
  await field.fill('a@seed.example; b@seed.example c@seed.example');

  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeEnabled();
  await page.getByTestId('clubs-save-button').click();

  await expect(page).toHaveURL('/clubs');
  expect(clubs[0]?.discoveryFlightOperatorEmail).toBe(
    'a@seed.example; b@seed.example c@seed.example',
  );
});

// T-14 ports legacy operator-email values verbatim, so a migrated club can hold
// a value that never parsed as an address. The edit form is where it surfaces.
test('clubs: a malformed migrated operator email renders and is fixable', async ({ page }) => {
  const clubs: MockClub[] = [
    { ...seedClub, discoveryFlightOperatorEmail: 'bitte Adresse eintragen' },
  ];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, []);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${seedClub.id}/edit`);

  const field = page.getByTestId('clubs-discovery-operator-email').locator('input');
  await expect(field).toHaveValue('bitte Adresse eintragen');

  // It is not silently dropped and it does not break the form — it is flagged,
  // and the save that would 400 server-side is blocked until it is corrected.
  await expect(
    page
      .locator('af-form-field', { has: page.getByTestId('clubs-discovery-operator-email') })
      .getByRole('alert'),
  ).toBeVisible();
  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeDisabled();

  await field.fill('schnupper@seed.example');
  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeEnabled();
  await page.getByTestId('clubs-save-button').click();

  await expect(page).toHaveURL('/clubs');
  expect(clubs[0]?.discoveryFlightOperatorEmail).toBe('schnupper@seed.example');
});

test('clubs: discovery-flight days can be published and withdrawn', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }];
  const days: MockDay[] = [{ id: 'dfd-1', eventDate: '2026-09-12' }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, days);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${seedClub.id}/edit`);

  const panel = page.getByTestId('clubs-discovery-days-panel');
  await expect(panel).toBeVisible();
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeVisible();

  await page.getByTestId('clubs-discovery-day-input').locator('input').fill('2026-08-30');
  await page.getByTestId('clubs-discovery-day-add').click();

  await expect(page.getByTestId('clubs-discovery-day-2026-08-30')).toBeVisible();
  expect(days.map((d) => d.eventDate)).toContain('2026-08-30');
  // Ascending, so the newly added earlier day sorts above the seeded one.
  await expect(panel.locator('li')).toHaveText([/2026-08-30/, /2026-09-12/]);

  await page.getByTestId('clubs-discovery-day-withdraw-2026-09-12').click();
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeHidden();
  expect(days.map((d) => d.eventDate)).toEqual(['2026-08-30']);
});

test('clubs: publishing a day the club already offers surfaces the conflict', async ({
  page,
}, testInfo) => {
  allowConsoleErrors(testInfo, /\b409\b/);
  const clubs: MockClub[] = [{ ...seedClub }];
  const days: MockDay[] = [{ id: 'dfd-1', eventDate: '2026-09-12' }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, days);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${seedClub.id}/edit`);
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeVisible();

  await page.getByTestId('clubs-discovery-day-input').locator('input').fill('2026-09-12');
  await page.getByTestId('clubs-discovery-day-add').click();

  await expect(page.getByTestId('clubs-discovery-day-error')).toContainText('already offered');
  await expect(page.getByTestId('clubs-discovery-days-panel').locator('li')).toHaveCount(1);
});

// The day resource is scoped to the caller's own tenant and carries no club id,
// so the panel must not appear over a club that is not the principal's — it
// would manage the wrong club's days under that club's heading.
test('clubs: the discovery-day panel is absent when editing another club', async ({ page }) => {
  const clubs: MockClub[] = [{ ...seedClub }, { ...otherClub }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, [{ id: 'dfd-1', eventDate: '2026-09-12' }]);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${seedClub.id}/edit`);
  await expect(page.getByTestId('clubs-discovery-days-panel')).toBeVisible();

  await page.goto(`/clubs/${otherClub.id}/edit`);
  await expect(page.locator('#clubName')).toHaveValue('Other Club');
  await expect(page.getByTestId('clubs-discovery-days-panel')).toHaveCount(0);
  // The operator-email fields stay editable — they ride the club PUT, which a
  // sysadmin may issue for any club.
  await expect(page.getByTestId('clubs-discovery-operator-email')).toBeVisible();
});
