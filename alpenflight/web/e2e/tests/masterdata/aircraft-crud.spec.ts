import { type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';


interface MockAircraftType {
  id: string;
  code: string;
  description: string;
  hasEngine: boolean;
  mayBeTowingAircraft: boolean;
  requiresTowingInfo: boolean;
}

interface MockAircraftState {
  id: string;
  code: string;
  description: string;
  isAircraftFlyable: boolean;
}

interface MockAircraftDetail {
  id: string;
  ownerClubId?: string;
  aircraftTypeId: string;
  immatriculation: string;
  manufacturerName?: string;
  aircraftModel?: string;
  nrOfSeats?: number;
  competitionSign?: string;
  flarmId?: string;
  isTowingOrWinchRequired: boolean;
  isTowingStartAllowed: boolean;
  isWinchStartAllowed: boolean;
  isTowingAircraft: boolean;
  isFastEntryRecord: boolean;
}

interface MockAircraftListItem {
  id: string;
  ownerClubId?: string;
  immatriculation: string;
  competitionSign?: string;
  aircraftTypeId: string;
  aircraftTypeCode: string;
  hasEngine: boolean;
  isTowingAircraft: boolean;
  currentStateCode?: string;
  currentStateFlyable?: boolean;
  manufacturerName?: string;
  aircraftModel?: string;
  nrOfSeats?: number;
}

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const GLIDER_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';
const MOTOR_TYPE_ID = '019e2e15-2c00-7afc-8000-000000002afc';
const STATE_OK_ID = '019e2e15-2c00-7ee0-8000-000000002ee0';
const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const mockCountries = [{ id: CH_COUNTRY_ID, iso2Code: 'CH', name: 'Switzerland' }];
const mockClubStates = [
  { id: '019e2e15-2c00-7bb8-8000-000000000bb8', code: 'ACTIVE', name: 'Active' },
];
const mockLocationTypes = [
  {
    id: '019e2e15-2c00-7110-8000-000000001110',
    code: 'AIRPORT',
    description: 'Airport',
    isAirfield: true,
  },
];
const mockAircraftTypes: MockAircraftType[] = [
  {
    id: GLIDER_TYPE_ID,
    code: 'GLIDER',
    description: 'Glider',
    hasEngine: false,
    mayBeTowingAircraft: false,
    requiresTowingInfo: true,
  },
  {
    id: '019e2e15-2c00-7afa-8000-000000002afa',
    code: 'GLIDER_WITH_MOTOR',
    description: 'Glider with motor',
    hasEngine: true,
    mayBeTowingAircraft: false,
    requiresTowingInfo: true,
  },
  {
    id: MOTOR_TYPE_ID,
    code: 'MOTOR_AIRCRAFT',
    description: 'Motor aircraft',
    hasEngine: true,
    mayBeTowingAircraft: true,
    requiresTowingInfo: false,
  },
];
const mockAircraftStates: MockAircraftState[] = [
  { id: STATE_OK_ID, code: 'OK', description: 'Airworthy', isAircraftFlyable: true },
];
const mockClubs = [
  {
    id: CLUB_A_ID,
    name: 'Test Club A',
    slug: 'test-club-a',
    countryId: CH_COUNTRY_ID,
    clubStateId: mockClubStates[0]!.id,
  },
];
const mockLocations: MockAircraftListItem[] = [];

const gliderSeed: MockAircraftDetail = {
  id: 'ac-019e30c3-2c00-7001-8000-000000000001',
  ownerClubId: CLUB_A_ID,
  aircraftTypeId: GLIDER_TYPE_ID,
  immatriculation: 'HB-GLI',
  manufacturerName: 'Schleicher',
  aircraftModel: 'ASK-21',
  nrOfSeats: 2,
  isTowingOrWinchRequired: true,
  isTowingStartAllowed: true,
  isWinchStartAllowed: true,
  isTowingAircraft: false,
  isFastEntryRecord: false,
};

const motorSeed: MockAircraftDetail = {
  id: 'ac-019e30c3-2c00-7001-8000-000000000002',
  ownerClubId: CLUB_A_ID,
  aircraftTypeId: MOTOR_TYPE_ID,
  immatriculation: 'HB-MOT',
  manufacturerName: 'Cessna',
  aircraftModel: '172',
  isTowingOrWinchRequired: false,
  isTowingStartAllowed: false,
  isWinchStartAllowed: false,
  isTowingAircraft: false,
  isFastEntryRecord: false,
};

const towingSeed: MockAircraftDetail = {
  id: 'ac-019e30c3-2c00-7001-8000-000000000003',
  ownerClubId: CLUB_A_ID,
  aircraftTypeId: MOTOR_TYPE_ID,
  immatriculation: 'HB-TOW',
  manufacturerName: 'Robin',
  aircraftModel: 'DR-400',
  isTowingOrWinchRequired: false,
  isTowingStartAllowed: false,
  isWinchStartAllowed: false,
  isTowingAircraft: true,
  isFastEntryRecord: false,
};

const GLIDER_WITH_MOTOR_TYPE_ID = '019e2e15-2c00-7afa-8000-000000002afa';
const gliderWithMotorSeed: MockAircraftDetail = {
  id: 'ac-019e30c3-2c00-7001-8000-000000000004',
  ownerClubId: CLUB_A_ID,
  aircraftTypeId: GLIDER_WITH_MOTOR_TYPE_ID,
  immatriculation: 'HB-GWM',
  manufacturerName: 'DG',
  aircraftModel: 'DG-1000T',
  isTowingOrWinchRequired: false,
  isTowingStartAllowed: false,
  isWinchStartAllowed: true,
  isTowingAircraft: false,
  isFastEntryRecord: false,
};

function toListItem(a: MockAircraftDetail): MockAircraftListItem {
  const type = mockAircraftTypes.find((t) => t.id === a.aircraftTypeId)!;
  const item: MockAircraftListItem = {
    id: a.id,
    immatriculation: a.immatriculation,
    aircraftTypeId: a.aircraftTypeId,
    aircraftTypeCode: type.code,
    hasEngine: type.hasEngine,
    isTowingAircraft: a.isTowingAircraft,
  };
  if (a.ownerClubId !== undefined) item.ownerClubId = a.ownerClubId;
  if (a.competitionSign !== undefined) item.competitionSign = a.competitionSign;
  if (a.manufacturerName !== undefined) item.manufacturerName = a.manufacturerName;
  if (a.aircraftModel !== undefined) item.aircraftModel = a.aircraftModel;
  if (a.nrOfSeats !== undefined) item.nrOfSeats = a.nrOfSeats;
  return item;
}

const mockCounterUnitTypes = [
  {
    id: '019e2e15-2c00-7b58-8000-000000001b58',
    code: 'HOURS_DECIMAL',
    name: 'Hours (decimal)',
    shortName: 'h',
  },
  {
    id: '019e2e15-2c00-7b59-8000-000000001b59',
    code: 'HOURS_MINUTES',
    name: 'Hours (HH:MM)',
    shortName: 'h',
  },
  {
    id: '019e2e15-2c00-7b5a-8000-000000001b5a',
    code: 'LANDINGS',
    name: 'Landings',
    shortName: 'ldg',
  },
];

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
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocationTypes),
    }),
  );
  await page.route('**/api/v1/aircraft-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockAircraftTypes),
    }),
  );
  await page.route('**/api/v1/aircraft-states**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockAircraftStates),
    }),
  );
  await page.route('**/api/v1/counter-unit-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockCounterUnitTypes),
    }),
  );
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubs),
    }),
  );
  await page.route('**/api/v1/locations**', (route) => {
    const u = new URL(route.request().url());
    if (u.pathname === '/api/v1/locations') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockLocations),
      });
    }
    return route.fallback();
  });
}

function setupAircraftBackend(aircraft: MockAircraftDetail[]) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/aircraft\/(ac-[^/]+)$/);

    if (method === 'GET' && path === '/api/v1/aircraft') {
      const typeParam = url.searchParams.get('type');
      const filtered = aircraft.filter((a) => {
        const items = toListItem(a);
        switch (typeParam) {
          case null:
            return true;
          case 'GLIDER':
            return (
              items.aircraftTypeCode === 'GLIDER' || items.aircraftTypeCode === 'GLIDER_WITH_MOTOR'
            );
          case 'MOTOR':
            return (
              items.aircraftTypeCode !== 'GLIDER' &&
              items.aircraftTypeCode !== 'GLIDER_WITH_MOTOR' &&
              items.hasEngine === true
            );
          case 'TOWING':
            return items.isTowingAircraft === true;
          default:
            return true;
        }
      });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(filtered.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && path === '/api/v1/aircraft/picker') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          aircraft.map((a) => ({
            id: a.id,
            immatriculation: a.immatriculation,
            aircraftTypeId: a.aircraftTypeId,
            isTowingAircraft: a.isTowingAircraft,
          })),
        ),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = aircraft.find((a) => a.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/aircraft') {
      const body = req.postDataJSON() as Omit<MockAircraftDetail, 'id'>;
      if (
        body.immatriculation &&
        aircraft.some(
          (a) =>
            a.immatriculation.replace(/-/g, '').toUpperCase() ===
            body.immatriculation.replace(/-/g, '').toUpperCase(),
        )
      ) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      const created: MockAircraftDetail = {
        ...body,
        id: `ac-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
      };
      aircraft.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/aircraft/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as Omit<MockAircraftDetail, 'id' | 'ownerClubId'>;
      const idx = aircraft.findIndex((a) => a.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      if (
        body.immatriculation &&
        aircraft.some(
          (a, i) =>
            i !== idx &&
            a.immatriculation.replace(/-/g, '').toUpperCase() ===
              body.immatriculation.replace(/-/g, '').toUpperCase(),
        )
      ) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      const prev = aircraft[idx]!;
      aircraft[idx] = {
        ...prev,
        ...body,
        id: prev.id,
        ...(prev.ownerClubId !== undefined ? { ownerClubId: prev.ownerClubId } : {}),
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(aircraft[idx]),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = aircraft.findIndex((a) => a.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      aircraft.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  };
}

test('aircraft: lists the seeded row at /aircraft', async ({ page }) => {
  const aircraft: MockAircraftDetail[] = [{ ...gliderSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft');

  await expect(page.locator('h1')).toHaveText('Aircraft');
  await expect(page.getByTestId('aircraft-table')).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toContainText('HB-GLI');

  await expect(page.getByTestId(`aircraft-model-${gliderSeed.id}`)).toContainText('Schleicher');
  await expect(page.getByTestId(`aircraft-model-${gliderSeed.id}`)).toContainText('ASK-21');
  await expect(page.getByTestId(`aircraft-seats-${gliderSeed.id}`)).toContainText('2 seats');
});

test('aircraft: type-filter slices GLIDER / TOWING / MOTOR preserving legacy membership', async ({
  page,
}) => {
  const aircraft: MockAircraftDetail[] = [
    { ...gliderSeed },
    { ...motorSeed },
    { ...towingSeed },
    { ...gliderWithMotorSeed },
  ];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft');

  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${motorSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${towingSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${gliderWithMotorSeed.id}`)).toBeVisible();

  await page.getByTestId('aircraft-type-filter').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Gliders' }).click();
  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${gliderWithMotorSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${motorSeed.id}`)).toHaveCount(0);
  await expect(page.getByTestId(`aircraft-row-${towingSeed.id}`)).toHaveCount(0);

  await page.getByTestId('aircraft-type-filter').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Towing aircraft' }).click();
  await expect(page.getByTestId(`aircraft-row-${towingSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toHaveCount(0);
  await expect(page.getByTestId(`aircraft-row-${motorSeed.id}`)).toHaveCount(0);
  await expect(page.getByTestId(`aircraft-row-${gliderWithMotorSeed.id}`)).toHaveCount(0);

  await page.getByTestId('aircraft-type-filter').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Motor aircraft' }).click();
  await expect(page.getByTestId(`aircraft-row-${motorSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${towingSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toHaveCount(0);
  await expect(page.getByTestId(`aircraft-row-${gliderWithMotorSeed.id}`)).toHaveCount(0);
});

test('aircraft: editing the seeded row updates the list (UI round-trip)', async ({ page }) => {
  const aircraft: MockAircraftDetail[] = [{ ...gliderSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft');
  await page.getByTestId(`aircraft-row-${gliderSeed.id}`).click();

  await expect(page).toHaveURL(/\/aircraft\/.+\/edit$/);
  await page.locator('#Immatriculation').fill('HB-REN');
  await page.locator('#Comment').fill('edited by e2e');
  await page.getByTestId('aircraft-save-button').click();

  await expect(page).toHaveURL('/aircraft');
  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toContainText('HB-REN');

  await page.reload();
  await page.getByTestId(`aircraft-row-${gliderSeed.id}`).click();
  await expect(page).toHaveURL(/\/aircraft\/.+\/edit$/);
  await expect(page.locator('#Immatriculation')).toHaveValue('HB-REN');
  await expect(page.locator('#Comment')).toHaveValue('edited by e2e');
});

test('aircraft: creating a new aircraft appears in the list', async ({ page }) => {
  const aircraft: MockAircraftDetail[] = [{ ...gliderSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft');
  await page.getByTestId('aircraft-new-button').locator('button').click();

  await expect(page).toHaveURL('/aircraft/new');
  await page.locator('#Immatriculation').fill('HB-NEW');
  await page.getByTestId('aircraft-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Glider' }).first().click();
  await page.getByTestId('aircraft-save-button').click();

  await expect(page).toHaveURL('/aircraft');
  await expect(
    page.locator('[data-testid^="aircraft-row-"]').filter({ hasText: 'HB-NEW' }),
  ).toBeVisible();
});

test('aircraft: 409 on duplicate immatriculation surfaces inline', async ({ page }, testInfo) => {
  allowConsoleErrors(testInfo, /\b409\b/);
  const aircraft: MockAircraftDetail[] = [{ ...gliderSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft/new');
  await page.locator('#Immatriculation').fill(gliderSeed.immatriculation);
  await page.getByTestId('aircraft-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Glider' }).first().click();
  await page.getByTestId('aircraft-save-button').click();

  await expect(page.getByTestId('aircraft-save-error')).toBeVisible();
  await expect(page.getByTestId('aircraft-save-error')).toContainText('already in use');
});

test('aircraft: invalid immatriculation pattern keeps Save disabled', async ({ page }) => {
  const aircraft: MockAircraftDetail[] = [{ ...gliderSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft/new');
  const immat = page.locator('#Immatriculation');
  await immat.fill('X');
  await immat.blur();

  await expect(page.getByTestId('aircraft-save-button').locator('button')).toBeDisabled();
});

test('aircraft: soft-delete removes the row from the rendered list', async ({ page }) => {
  const aircraft: MockAircraftDetail[] = [{ ...gliderSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft');
  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toBeVisible();

  page.once('dialog', (d) => d.accept());
  await page.getByTestId(`aircraft-kebab-${gliderSeed.id}`).click();
  await page.getByTestId(`aircraft-delete-${gliderSeed.id}`).click();

  await expect(page.getByTestId(`aircraft-row-${gliderSeed.id}`)).toHaveCount(0);
});

test('aircraft form: field inventory matches legacy reference screenshot (parity oracle: masterdata-aircrafts-form.png)', async ({
  page,
}) => {
  const aircraft: MockAircraftDetail[] = [];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto('/aircraft/new');

  await expect(page.getByTestId('aircraft-section-masterdata')).toBeVisible();
  await expect(page.getByTestId('aircraft-section-operational')).toBeVisible();
  await expect(page.getByTestId('aircraft-section-technical')).toBeVisible();

  await expect(page.getByRole('heading', { name: 'Master data' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Operational data' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Technical data' })).toBeVisible();

  await expect(page.getByLabel('Immatriculation')).toBeVisible();
  await expect(page.getByLabel('Competition sign')).toBeVisible();
  await expect(page.getByTestId('aircraft-type-select')).toBeVisible();
  await expect(page.getByLabel('Manufacturer')).toBeVisible();
  await expect(page.getByLabel('Model')).toBeVisible();
  await expect(page.getByLabel('Seats')).toBeVisible();

  await expect(page.getByTestId('aircraft-homebase-select')).toBeVisible();
  await expect(page.getByLabel('SPOT tracker link')).toBeVisible();
  await expect(page.getByTestId('aircraft-spotlink-test')).toBeVisible();
  await expect(page.getByLabel('This aircraft is a towing aircraft')).toBeVisible();
  await expect(page.getByLabel('Towing or winch required')).toBeVisible();
  await expect(page.getByLabel('Towing start allowed')).toBeVisible();
  await expect(page.getByLabel('Winch start allowed')).toBeVisible();

  await expect(page.getByLabel('MTOM (kg)')).toBeVisible();
  await expect(page.getByLabel('Serial number')).toBeVisible();
  await expect(page.getByTestId('aircraft-year-select')).toBeVisible();
  await expect(page.getByLabel('FLARM ID')).toBeVisible();
  await expect(page.getByLabel('DAEC index')).toBeVisible();
  await expect(page.getByLabel('Noise class')).toBeVisible();
  await expect(page.getByLabel('Noise level (dB)')).toBeVisible();
  await expect(page.getByLabel('Comment')).toBeVisible();

  await expect(page.getByLabel(/fast.entry/i)).toHaveCount(0);
  await expect(page.getByText('Flight counter unit')).toHaveCount(0);
});

test('aircraft form: engine counter unit dropdown is hidden for engineless types, visible for engine types', async ({
  page,
}) => {
  const aircraft: MockAircraftDetail[] = [{ ...gliderSeed }, { ...motorSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft**', setupAircraftBackend(aircraft));

  await page.goto(`/aircraft/${gliderSeed.id}/edit`);
  await expect(page.getByLabel('Immatriculation')).toHaveValue('HB-GLI');
  await expect(page.getByTestId('aircraft-engine-counter-unit-select')).toHaveCount(0);

  await page.goto(`/aircraft/${motorSeed.id}/edit`);
  await expect(page.getByLabel('Immatriculation')).toHaveValue('HB-MOT');
  await expect(page.getByTestId('aircraft-engine-counter-unit-select')).toBeVisible();
});
