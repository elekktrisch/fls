import { type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

interface MockFlightTypeDetail {
  id: string;
  flightTypeName: string;
  flightCode?: string;
  isInstructorRequired: boolean;
  isObserverPilotOrInstructorRequired: boolean;
  isCheckFlight: boolean;
  isPassengerFlight: boolean;
  isSoloFlight: boolean;
  isForGliderFlights: boolean;
  isForTowFlights: boolean;
  isForMotorFlights: boolean;
  isFlightCostBalanceSelectable: boolean;
  isCouponNumberRequired: boolean;
  isForAircraftReservationType: boolean;
  minNrOfAircraftSeatsRequired?: number;
}

interface MockFlightTypeListItem {
  id: string;
  flightTypeName: string;
  flightCode?: string;
  isForGliderFlights: boolean;
  isForTowFlights: boolean;
  isForMotorFlights: boolean;
  isFlightCostBalanceSelectable: boolean;
}

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const schulflugSeed: MockFlightTypeDetail = {
  id: 'ft-019e30c3-2c00-7001-8000-000000000001',
  flightTypeName: 'Schulflug',
  flightCode: 'S',
  isInstructorRequired: true,
  isObserverPilotOrInstructorRequired: false,
  isCheckFlight: false,
  isPassengerFlight: false,
  isSoloFlight: false,
  isForGliderFlights: true,
  isForTowFlights: false,
  isForMotorFlights: false,
  isFlightCostBalanceSelectable: false,
  isCouponNumberRequired: false,
  isForAircraftReservationType: false,
  minNrOfAircraftSeatsRequired: 2,
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

function toListItem(d: MockFlightTypeDetail): MockFlightTypeListItem {
  const item: MockFlightTypeListItem = {
    id: d.id,
    flightTypeName: d.flightTypeName,
    isForGliderFlights: d.isForGliderFlights,
    isForTowFlights: d.isForTowFlights,
    isForMotorFlights: d.isForMotorFlights,
    isFlightCostBalanceSelectable: d.isFlightCostBalanceSelectable,
  };
  if (d.flightCode !== undefined) item.flightCode = d.flightCode;
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
  await page.route('**/api/v1/flight-cost-balance-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
}

function setupFlightTypesBackend(items: MockFlightTypeDetail[]) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/flight-types\/(ft-[^/]+)$/);

    if (method === 'GET' && path === '/api/v1/flight-types') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = items.find((ft) => ft.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/flight-types') {
      const body = req.postDataJSON() as Omit<MockFlightTypeDetail, 'id'>;
      if (body.flightTypeName && items.some((ft) => ft.flightTypeName === body.flightTypeName)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      const created: MockFlightTypeDetail = {
        ...body,
        id: `ft-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
      };
      items.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/flight-types/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as Omit<MockFlightTypeDetail, 'id'>;
      const idx = items.findIndex((ft) => ft.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      if (
        body.flightTypeName &&
        items.some((ft, i) => i !== idx && ft.flightTypeName === body.flightTypeName)
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
      const idx = items.findIndex((ft) => ft.id === idMatch[1]);
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

test('flight-types: lists the seeded row at /flight-types', async ({ page }) => {
  const items: MockFlightTypeDetail[] = [{ ...schulflugSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/flight-types**', setupFlightTypesBackend(items));

  await page.goto('/flight-types');

  await expect(page.locator('h1')).toHaveText('Flight types');
  await expect(page.getByTestId('flight-types-table')).toBeVisible();
  await expect(page.getByTestId(`flight-types-row-${schulflugSeed.id}`)).toBeVisible();
  await expect(page.getByTestId(`flight-types-row-${schulflugSeed.id}`)).toContainText('Schulflug');
});

test('flight-types: creating a new flight type appears in the list', async ({ page }) => {
  const items: MockFlightTypeDetail[] = [{ ...schulflugSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/flight-types**', setupFlightTypesBackend(items));

  await page.goto('/flight-types');
  await page.getByTestId('flight-types-new-button').locator('button').click();

  await expect(page).toHaveURL('/flight-types/new');
  await page.locator('#FlightTypeName').fill('Streckenflug');
  await page.getByTestId('flight-types-flag-glider').check();
  await page.getByTestId('flight-types-save-button').locator('button').click();

  await expect(page).toHaveURL('/flight-types');
  await expect(
    page.locator('[data-testid^="flight-types-row-"]').filter({ hasText: 'Streckenflug' }),
  ).toBeVisible();
});

test('flight-types: editing the seeded row updates the list (UI round-trip)', async ({ page }) => {
  const items: MockFlightTypeDetail[] = [{ ...schulflugSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/flight-types**', setupFlightTypesBackend(items));

  await page.goto('/flight-types');
  await page.getByTestId(`flight-types-row-${schulflugSeed.id}`).click();

  await expect(page).toHaveURL(/\/flight-types\/.+\/edit$/);
  await page.locator('#FlightTypeName').fill('Schulflug-renamed');
  await page.getByTestId('flight-types-save-button').locator('button').click();

  await expect(page).toHaveURL('/flight-types');
  await expect(page.getByTestId(`flight-types-row-${schulflugSeed.id}`)).toContainText(
    'Schulflug-renamed',
  );

  await page.reload();
  await page.getByTestId(`flight-types-row-${schulflugSeed.id}`).click();
  await expect(page).toHaveURL(/\/flight-types\/.+\/edit$/);
  await expect(page.locator('#FlightTypeName')).toHaveValue('Schulflug-renamed');
});

test('flight-types: 409 on duplicate name surfaces inline', async ({ page }, testInfo) => {
  allowConsoleErrors(testInfo, /\b409\b/);
  const items: MockFlightTypeDetail[] = [{ ...schulflugSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/flight-types**', setupFlightTypesBackend(items));

  await page.goto('/flight-types/new');
  await page.locator('#FlightTypeName').fill(schulflugSeed.flightTypeName);
  await page.getByTestId('flight-types-flag-glider').check();
  await page.getByTestId('flight-types-save-button').locator('button').click();

  await expect(page.getByTestId('flight-types-save-error')).toBeVisible();
  await expect(page.getByTestId('flight-types-save-error')).toContainText('already in use');
});

test('flight-types: invalid (blank) name keeps Save disabled', async ({ page }) => {
  const items: MockFlightTypeDetail[] = [{ ...schulflugSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/flight-types**', setupFlightTypesBackend(items));

  await page.goto('/flight-types/new');
  const name = page.locator('#FlightTypeName');
  await name.fill('');
  await name.blur();

  await expect(page.getByTestId('flight-types-save-button').locator('button')).toBeDisabled();
});

test('flight-types: soft-delete removes the row from the rendered list', async ({ page }) => {
  const items: MockFlightTypeDetail[] = [{ ...schulflugSeed }];
  await stubReferenceData(page);
  await page.route('**/api/v1/flight-types**', setupFlightTypesBackend(items));

  await page.goto('/flight-types');
  await expect(page.getByTestId(`flight-types-row-${schulflugSeed.id}`)).toBeVisible();

  page.once('dialog', (d) => d.accept());
  await page.getByTestId(`flight-types-kebab-${schulflugSeed.id}`).click();
  await page.getByTestId(`flight-types-delete-${schulflugSeed.id}`).click();

  await expect(page.getByTestId(`flight-types-row-${schulflugSeed.id}`)).toHaveCount(0);
});
