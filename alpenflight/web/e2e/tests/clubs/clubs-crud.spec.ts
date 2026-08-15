import { type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';
import { selectAfOption } from '../_helpers/af-select';
import {
  CLUB_SETTINGS_NAV_TESTID,
  enterClubSettingsViaNav,
  openMasterdataGroup,
} from '../_helpers/nav';

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
  homebaseId: string | null;
}

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const DE_COUNTRY_ID = '019e2e15-2c00-743a-8000-00000000043a';
const ACTIVE_CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';
const DISCOVERY_FLIGHT_TYPE_ID = 'ft-019e30c3-2c00-7001-8000-0000000000f1';
const TOW_FLIGHT_TYPE_ID = 'ft-019e30c3-2c00-7001-8000-0000000000f2';
const HOMEBASE_LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-0000000000a1';
const OUTLANDING_LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-0000000000a2';

const principalsOwnClub: MockClub = {
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
  homebaseId: HOMEBASE_LOCATION_ID,
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
  homebaseId: null,
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

const mockLocations = [
  {
    id: HOMEBASE_LOCATION_ID,
    locationName: 'Birrfeld',
    icaoCode: 'LSZF',
    locationTypeCode: 'GLIDER_AIRFIELD',
    isAirfield: true,
    isFastEntryRecord: false,
  },
  {
    id: OUTLANDING_LOCATION_ID,
    locationName: 'Aussenlandefeld Nord',
    locationTypeCode: 'OUTLANDING',
    isAirfield: false,
    isFastEntryRecord: false,
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
  await page.route('**/api/v1/locations', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocations),
    }),
  );
}

interface MockDay {
  id: string;
  eventDate: string;
}

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
      clubs[idx] = {
        ...clubs[idx],
        ...body,
        discoveryFlightOperatorEmail: body.discoveryFlightOperatorEmail ?? '',
        scenicFlightOperatorEmail: body.scenicFlightOperatorEmail ?? '',
        discoveryFlightTypeId: body.discoveryFlightTypeId ?? null,
        homebaseId: body.homebaseId ?? null,
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

test('clubs: the Masterdata nav entry opens the caller’s OWN club settings (ENTER via nav)', async ({
  page,
}) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  const days: MockDay[] = [{ id: 'dfd-1', eventDate: '2026-09-12' }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, days);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/start?lang=de');
  const clubId = await enterClubSettingsViaNav(page);

  expect(clubId).toBe(principalsOwnClub.id);
  await expect(page).toHaveURL(`/clubs/${principalsOwnClub.id}/edit`);
  await expect(page.getByTestId('clubs-load-error')).toBeHidden();
  await expect(page.getByTestId('clubs-discovery-operator-email').locator('input')).toHaveValue(
    principalsOwnClub.discoveryFlightOperatorEmail,
  );
  await expect(page.getByTestId('clubs-scenic-operator-email').locator('input')).toHaveValue(
    principalsOwnClub.scenicFlightOperatorEmail,
  );
  await expect(page.getByTestId('clubs-discovery-days-panel')).toBeVisible();
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeVisible();
});

test('clubs: the sysadmin catalog entry still reaches /clubs alongside the own-club entry', async ({
  page,
}) => {
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend([{ ...principalsOwnClub }]));

  await page.goto('/start?lang=de');
  await page.getByTestId('af-nav-section-/clubs').click();

  await expect(page).toHaveURL('/clubs');
  await expect(page.getByTestId('clubs-table')).toBeVisible();

  await openMasterdataGroup(page);
  await expect(page.getByTestId(CLUB_SETTINGS_NAV_TESTID)).toBeVisible();
});

test('clubs: lists the seeded row at /clubs', async ({ page }) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');

  await expect(page.locator('h1')).toHaveText('Clubs');
  await expect(page.getByTestId('clubs-table')).toBeVisible();
  await expect(page.getByTestId('club-row-seed-club-1')).toBeVisible();
  await expect(page.getByTestId('club-row-seed-club-1')).toHaveText('Seed Club');
});

test('clubs: editing the seeded row updates the list', async ({ page }) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
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
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
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
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  await page.getByRole('button', { name: 'New club' }).click();
  await expect(page).toHaveURL('/clubs/new');

  await page.locator('#clubName').fill('Bavarian Soaring');
  await page.locator('#clubSlug').fill('bavarian-soaring');
  await page.locator('#clubKey').fill('BAV');

  await page.getByTestId('clubs-country-select').locator('nz-select').click();
  await expect(page.locator('nz-option-item').filter({ hasText: 'Switzerland' })).toBeVisible();
  await expect(page.locator('nz-option-item').filter({ hasText: 'Germany' })).toBeVisible();
  await page.locator('nz-option-item').filter({ hasText: 'Germany' }).click();

  await page.getByTestId('clubs-club-state-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Active' }).click();

  await page.getByTestId('clubs-save-button').click();
  await expect(page).toHaveURL('/clubs');

  await expect(page.getByTestId('club-row-bavarian-soaring')).toBeVisible();
  const created = clubs.find((c) => c.slug === 'bavarian-soaring');
  expect(created?.countryId).toBe(DE_COUNTRY_ID);
});

test('clubs: 409 on duplicate slug surfaces as a save error', async ({ page }, testInfo) => {
  allowConsoleErrors(testInfo, /\b409\b/);
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

test('clubs: invalid slug shows an inline field error before submit', async ({ page }) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs/new');
  await page.locator('#clubName').fill('Test Club');
  await page.locator('#clubKey').fill('TST');

  const uppercaseAndTooShortSlug = 'AB';
  const slug = page.locator('#clubSlug');
  await slug.fill(uppercaseAndTooShortSlug);
  await slug.blur();

  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeDisabled();

  await expect(
    page.locator('af-form-field', { has: page.locator('#clubSlug') }).getByRole('alert'),
  ).toBeVisible();
});

test('clubs: client-side async validator flags a duplicate slug before submit', async ({
  page,
}) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto('/clubs');
  await expect(
    page.getByTestId('club-row-seed-club-1'),
    'the list load populates ClubsStore — the async slug validator probes its entity map',
  ).toBeVisible();

  await page.getByRole('button', { name: 'New club' }).click();
  await expect(page).toHaveURL('/clubs/new');

  await page.locator('#clubName').fill('Conflict Pre-check');
  await page.locator('#clubKey').fill('CPC');
  const slug = page.locator('#clubSlug');
  await slug.fill(principalsOwnClub.slug);
  await slug.blur();

  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeDisabled();
  await expect(
    page.locator('af-form-field', { has: page.locator('#clubSlug') }).getByRole('alert'),
  ).toBeVisible();
});

test('clubs: saving an unrelated change preserves the registration fields', async ({ page }) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, []);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);

  await expect(page.getByTestId('clubs-discovery-operator-email').locator('input')).toHaveValue(
    'schnupper@seed.example',
  );
  await expect(page.getByTestId('clubs-scenic-operator-email').locator('input')).toHaveValue(
    'mitflug@seed.example',
  );
  await expect(page.getByTestId('clubs-discovery-flight-type-select')).toContainText(
    'Schnupperflug',
  );
  await expect(page.getByTestId('clubs-homebase-select')).toContainText('Birrfeld');

  const putRequest = page.waitForRequest(
    (r) =>
      r.method() === 'PUT' && new URL(r.url()).pathname === `/api/v1/clubs/${principalsOwnClub.id}`,
  );
  await page.locator('#clubName').fill('Renamed Only');
  await page.getByTestId('clubs-save-button').click();

  const body = (await putRequest).postDataJSON() as Record<string, unknown>;
  expect(body).toMatchObject({
    name: 'Renamed Only',
    discoveryFlightOperatorEmail: 'schnupper@seed.example',
    scenicFlightOperatorEmail: 'mitflug@seed.example',
    discoveryFlightTypeId: DISCOVERY_FLIGHT_TYPE_ID,
    homebaseId: HOMEBASE_LOCATION_ID,
  });

  await expect(page).toHaveURL('/clubs');
  expect(clubs[0]?.discoveryFlightOperatorEmail).toBe('schnupper@seed.example');
  expect(clubs[0]?.scenicFlightOperatorEmail).toBe('mitflug@seed.example');
  expect(clubs[0]?.discoveryFlightTypeId).toBe(DISCOVERY_FLIGHT_TYPE_ID);
  expect(clubs[0]?.homebaseId).toBe(HOMEBASE_LOCATION_ID);
});

test('clubs: the homebase picker saves a club location and clears it again', async ({ page }) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub, homebaseId: null }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, []);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);
  await expect(page.getByTestId('clubs-homebase-select')).toContainText('No homebase');

  await selectAfOption(page, 'clubs-homebase-select', HOMEBASE_LOCATION_ID);
  await page.getByTestId('clubs-save-button').click();
  await expect(page).toHaveURL('/clubs');
  expect(clubs[0]?.homebaseId).toBe(HOMEBASE_LOCATION_ID);

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);
  await expect(page.getByTestId('clubs-homebase-select')).toContainText('Birrfeld');
  await page.getByTestId('clubs-homebase-select').locator('nz-select').hover();
  await page.getByTestId('clubs-homebase-select').locator('.ant-select-clear').click();
  await page.getByTestId('clubs-save-button').click();
  await expect(page).toHaveURL('/clubs');
  expect(clubs[0]?.homebaseId).toBeNull();
});

test('clubs: a recipient list of several addresses round-trips', async ({ page }) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, []);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);
  const field = page.getByTestId('clubs-discovery-operator-email').locator('input');
  await field.fill('a@seed.example; b@seed.example c@seed.example');

  await expect(page.getByTestId('clubs-save-button').locator('button')).toBeEnabled();
  await page.getByTestId('clubs-save-button').click();

  await expect(page).toHaveURL('/clubs');
  expect(clubs[0]?.discoveryFlightOperatorEmail).toBe(
    'a@seed.example; b@seed.example c@seed.example',
  );
});

test('clubs: a malformed migrated operator email renders and is fixable', async ({ page }) => {
  const clubs: MockClub[] = [
    { ...principalsOwnClub, discoveryFlightOperatorEmail: 'bitte Adresse eintragen' },
  ];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, []);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);

  const field = page.getByTestId('clubs-discovery-operator-email').locator('input');
  await expect(field).toHaveValue('bitte Adresse eintragen');

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
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  const days: MockDay[] = [{ id: 'dfd-1', eventDate: '2026-09-12' }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, days);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);

  const panel = page.getByTestId('clubs-discovery-days-panel');
  await expect(panel).toBeVisible();
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeVisible();

  await page.getByTestId('clubs-discovery-day-input').locator('input').fill('2026-08-30');
  await page.getByTestId('clubs-discovery-day-add').click();

  await expect(page.getByTestId('clubs-discovery-day-2026-08-30')).toBeVisible();
  expect(days.map((d) => d.eventDate)).toContain('2026-08-30');
  await expect(panel.locator('li')).toHaveText([/2026-08-30/, /2026-09-12/]);

  await page.getByTestId('clubs-discovery-day-withdraw-2026-09-12').click();
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeHidden();
  expect(days.map((d) => d.eventDate)).toEqual(['2026-08-30']);
});

test('clubs: publishing a day the club already offers surfaces the conflict', async ({
  page,
}, testInfo) => {
  allowConsoleErrors(testInfo, /\b409\b/);
  const clubs: MockClub[] = [{ ...principalsOwnClub }];
  const days: MockDay[] = [{ id: 'dfd-1', eventDate: '2026-09-12' }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, days);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeVisible();

  await page.getByTestId('clubs-discovery-day-input').locator('input').fill('2026-09-12');
  await page.getByTestId('clubs-discovery-day-add').click();

  await expect(page.getByTestId('clubs-discovery-day-error')).toContainText('already offered');
  await expect(page.getByTestId('clubs-discovery-days-panel').locator('li')).toHaveCount(1);
});

test('clubs: the edit form populates from the single-club read alone', async ({
  page,
}, testInfo) => {
  allowConsoleErrors(testInfo, /\b403\b/);
  await stubReferenceData(page);
  await stubDiscoveryDays(page, [{ id: 'dfd-1', eventDate: '2026-09-12' }]);
  await page.route('**/api/v1/clubs**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    if (req.method() === 'GET' && url.pathname === '/api/v1/clubs') {
      await route.fulfill({ status: 403, contentType: 'application/json', body: '{}' });
      return;
    }
    await setupClubsBackend([{ ...principalsOwnClub }])(route);
  });

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);

  await expect(page.locator('#clubName')).toHaveValue('Seed Club');
  await expect(page.getByTestId('clubs-discovery-operator-email').locator('input')).toHaveValue(
    'schnupper@seed.example',
  );
  await expect(page.getByTestId('clubs-scenic-operator-email').locator('input')).toHaveValue(
    'mitflug@seed.example',
  );
  await expect(page.getByTestId('clubs-discovery-flight-type-select')).toContainText(
    'Schnupperflug',
  );
  await expect(page.getByTestId('clubs-discovery-day-2026-09-12')).toBeVisible();
});

test('clubs: a club that cannot be read surfaces an error instead of a blank form', async ({
  page,
}, testInfo) => {
  allowConsoleErrors(testInfo, /\b403\b/);
  await stubReferenceData(page);
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({ status: 403, contentType: 'application/json', body: '{}' }),
  );

  await page.goto(`/clubs/${otherClub.id}/edit`);

  await expect(page.getByTestId('clubs-load-error')).toContainText('not allowed to view');
  await expect(page.locator('#clubName')).toHaveValue('');
});

test('clubs: editing another club — no discovery-day panel, homebase picker disabled (own-tenant catalogs), operator emails still editable', async ({
  page,
}) => {
  const clubs: MockClub[] = [{ ...principalsOwnClub }, { ...otherClub }];
  await stubReferenceData(page);
  await stubDiscoveryDays(page, [{ id: 'dfd-1', eventDate: '2026-09-12' }]);
  await page.route('**/api/v1/clubs**', setupClubsBackend(clubs));

  await page.goto(`/clubs/${principalsOwnClub.id}/edit`);
  await expect(page.getByTestId('clubs-discovery-days-panel')).toBeVisible();

  await page.goto(`/clubs/${otherClub.id}/edit`);
  await expect(page.locator('#clubName')).toHaveValue('Other Club');
  await expect(page.getByTestId('clubs-discovery-days-panel')).toHaveCount(0);
  await expect(page.getByTestId('clubs-discovery-operator-email')).toBeVisible();
  await expect(page.getByTestId('clubs-homebase-select').locator('nz-select')).toHaveClass(
    /ant-select-disabled/,
  );
});
