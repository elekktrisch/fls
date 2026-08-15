import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';

const AC_GLIDER = 'ac-019e30c3-2c00-7001-8000-000000000a01';
const AC_TOW = 'ac-019e30c3-2c00-7001-8000-000000000a02';
const PERSON_PILOT = 'pn-019e30c3-2c00-7001-8000-000000000001';
const LOC_HOME = 'loc-019e30c3-2c00-7001-8000-000000000001';
const FT_GLIDER = 'ft-019e30c3-2c00-7001-8000-000000000001';
const ST_SELF = 'st-self';

async function stubMasterdata(page: Page): Promise<void> {
  await page.route('**/api/v1/aircraft', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: AC_GLIDER,
          immatriculation: 'HB-GLI',
          aircraftTypeId: 'at-1',
          aircraftTypeCode: 'GLIDER',
          hasEngine: false,
          isTowingAircraft: false,
        },
        {
          id: AC_TOW,
          immatriculation: 'HB-TOW',
          aircraftTypeId: 'at-2',
          aircraftTypeCode: 'MOTOR_AIRCRAFT',
          hasEngine: true,
          isTowingAircraft: true,
        },
      ]),
    }),
  );
  await page.route('**/api/v1/persons**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: PERSON_PILOT, firstname: 'Alice', lastname: 'Pilot' }]),
    }),
  );
  await page.route('**/api/v1/locations**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: LOC_HOME, locationName: 'Homebase', locationCode: 'HB' }]),
    }),
  );
  await page.route('**/api/v1/flight-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: FT_GLIDER,
          flightTypeName: 'Local',
          flightCode: 'LOC',
          isForGliderFlights: true,
          isForTowFlights: false,
          isForMotorFlights: false,
          isFlightCostBalanceSelectable: false,
        },
      ]),
    }),
  );
  await page.route('**/api/v1/countries**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/club-states**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/location-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/aircraft-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/aircraft-states**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/counter-unit-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
}

async function stubFlightEndpoints(page: Page, onCreate: (body: unknown) => void): Promise<void> {
  await page.route('**/api/v1/flights/new-template', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        flightAircraftType: 'GLIDER',
        flightDate: '2026-05-25',
        startTypeId: ST_SELF,
        startLocationId: LOC_HOME,
        ldgLocationId: LOC_HOME,
        flightTypeId: FT_GLIDER,
        aircraftId: AC_GLIDER,
        crew: [],
        isSoloFlight: false,
        noStartTimeInformation: false,
        noLdgTimeInformation: false,
      }),
    }),
  );
  await page.route('**/api/v1/flights/last-context**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: 'null' }),
  );
  await page.route('**/api/v1/flights', (route: Route) => {
    const req = route.request();
    if (req.method() === 'POST') {
      onCreate(req.postDataJSON());
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'fl-new',
          flightAircraftType: 'GLIDER',
          aircraftId: AC_GLIDER,
          version: 1,
          isSoloFlight: false,
          noStartTimeInformation: false,
          noLdgTimeInformation: false,
          airState: 'NEW',
          processStateId: 'ps-1',
        }),
      });
    }
    if (req.method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      });
    }
    return route.fallback();
  });
}

test.describe('flight edit — create (parity port)', () => {
  test('creates a single-row glider flight via the wizard', async ({ page }) => {
    await stubMasterdata(page);
    const captured: unknown[] = [];
    await stubFlightEndpoints(page, (body) => captured.push(body));

    await page.goto('/flights/new');

    await expect(page.getByTestId('flight-form')).toBeVisible();
    await expect(page.getByTestId('flight-step-launch')).toBeVisible();
    await page.screenshot({ path: 'screenshots/flights/04-01-launch.png', fullPage: true });

    await expect(page.getByTestId('flight-edit-flightDate').locator('input')).toHaveValue(
      '2026-05-25',
    );

    const towStepperItem = page.getByTestId('flight-step-2');
    await expect(towStepperItem).toHaveCount(0);

    await page.getByTestId('flight-step-next').click();
    await expect(page.getByTestId('flight-step-glider')).toBeVisible();
    await selectAfOption(page, 'flight-edit-glider-pilot', PERSON_PILOT);
    await page.getByTestId('flight-edit-glider-comment').locator('input').fill('parity create');

    await page.screenshot({ path: 'screenshots/flights/04-02-glider-filled.png', fullPage: true });

    await page.getByTestId('flight-submit-header').click();

    await expect.poll(() => captured.length).toBeGreaterThan(0);
    const body = captured[0] as Record<string, unknown>;
    expect(body['flightAircraftType']).toBe('GLIDER');
    expect(body['aircraftId']).toBe(AC_GLIDER);
    expect(body['flightDate']).toBe('2026-05-25');
    expect(body['comment']).toBe('parity create');

    await expect(page).toHaveURL(/\/flights$/);
  });
});
