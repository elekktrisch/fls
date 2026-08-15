import { type Locator, type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';

function fieldErrors(page: Page, testId: string): Locator {
  return page.locator('af-form-field', { has: page.getByTestId(testId) }).getByRole('alert');
}

function saveButton(page: Page): Locator {
  return page.getByTestId('flight-submit-header').locator('button');
}


const AC_GLIDER = 'ac-019e30c3-2c00-7001-8000-000000000a01';
const PERSON_PILOT = 'pn-019e30c3-2c00-7001-8000-000000000001';
const LOC_HOME = 'loc-019e30c3-2c00-7001-8000-000000000001';
const FT_GLIDER = 'ft-019e30c3-2c00-7001-8000-000000000001';
const ST_SELF = '019e2e15-2c00-7fa2-8000-000000000fa2';
const FLIGHT_ID = 'fl-019e30c3-2c00-7001-8000-000000000001';
const PILOT_CREW_TYPE = '019e2e15-2c00-76b0-8000-0000000036b0';

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
  for (const p of [
    'countries',
    'club-states',
    'location-types',
    'aircraft-types',
    'aircraft-states',
    'counter-unit-types',
    'clubs',
  ]) {
    await page.route(`**/api/v1/${p}**`, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
}

const TODAY = '2026-06-20';
const NEXT_WEEK = '2026-06-27';

function fullyPopulatedDetail(flightDate: string): Record<string, unknown> {
  return {
    id: FLIGHT_ID,
    flightAircraftType: 'GLIDER',
    aircraftId: AC_GLIDER,
    flightDate,
    startTypeId: ST_SELF,
    startLocationId: LOC_HOME,
    ldgLocationId: LOC_HOME,
    flightTypeId: FT_GLIDER,
    startDateTime: `${flightDate}T10:00:00Z`,
    ldgDateTime: `${flightDate}T11:30:00Z`,
    nrOfLdgs: 1,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    airState: 'LANDED',
    processStateId: 'ps-1',
    version: 7,
    crew: [{ personId: PERSON_PILOT, flightCrewTypeId: PILOT_CREW_TYPE }],
    comment: 'before edit',
  };
}

function newTemplate(flightDate: string): Record<string, unknown> {
  return {
    flightAircraftType: 'GLIDER',
    flightDate,
    startTypeId: ST_SELF,
    startLocationId: LOC_HOME,
    ldgLocationId: LOC_HOME,
    flightTypeId: FT_GLIDER,
    aircraftId: AC_GLIDER,
    crew: [],
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
  };
}

test.describe('J-2b flights hardening', () => {
  test('[happy] create-new-flight via the form persists (#229 regression guard)', async ({
    page,
  }) => {
    await stubMasterdata(page);
    const created: Record<string, unknown>[] = [];
    const persisted: Record<string, unknown>[] = [];

    await page.route('**/api/v1/flights/new-template', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(newTemplate(TODAY)),
      }),
    );
    await page.route('**/api/v1/flights/last-context**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: 'null' }),
    );
    await page.route('**/api/v1/flights', (route: Route) => {
      const req = route.request();
      if (req.method() === 'POST') {
        const body = req.postDataJSON() as Record<string, unknown>;
        created.push(body);
        persisted.push({ ...body, id: 'fl-new', version: 1, airState: 'NEW' });
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 'fl-new',
            version: 1,
            airState: 'NEW',
            processStateId: 'ps-1',
          }),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: persisted }),
      });
    });

    await page.goto('/flights/new');
    await expect(page.getByTestId('flight-form')).toBeVisible();
    await page.getByTestId('flight-step-next').click();
    await expect(page.getByTestId('flight-step-glider')).toBeVisible();
    await selectAfOption(page, 'flight-edit-glider-pilot', PERSON_PILOT);
    await page.getByTestId('flight-submit-header').click();

    await expect.poll(() => created.length).toBeGreaterThan(0);
    await expect(page).toHaveURL(/\/flights$/);
    await expect(page.getByTestId('flights-table')).toBeVisible();
  });

  test('[edge] a flight dated OFF today is discoverable via the date-range filter', async ({
    page,
  }) => {
    await stubMasterdata(page);
    await page.route('**/api/v1/flights**', (route) => {
      const url = new URL(route.request().url());
      const from = url.searchParams.get('from');
      const to = url.searchParams.get('to');
      const future = {
        id: FLIGHT_ID,
        flightAircraftType: 'GLIDER',
        flightDate: NEXT_WEEK,
        startDateTime: `${NEXT_WEEK}T08:00:00Z`,
        ldgDateTime: `${NEXT_WEEK}T09:00:00Z`,
        aircraftId: AC_GLIDER,
        processStateId: 'ps-1',
        processState: 'VALID',
        airState: 'LANDED',
        version: 1,
      };
      const inRange = (!from || from <= NEXT_WEEK) && (!to || to >= NEXT_WEEK);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: inRange ? [future] : [] }),
      });
    });

    await page.goto('/flights');
    await expect(page.getByTestId('flights-table')).toBeVisible();
    await expect(page.getByTestId('flights-date-range')).toBeVisible();

    await expect(page.getByTestId(`flights-row-${FLIGHT_ID}`)).toHaveCount(0);
  });

  test('[key-error] reopen a saved flight — populated required fields render VALID (no Entry-required)', async ({
    page,
  }) => {
    await stubMasterdata(page);
    await page.route(`**/api/v1/flights/${FLIGHT_ID}`, (route: Route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(fullyPopulatedDetail(TODAY)),
        });
      }
      return route.fallback();
    });
    await page.route('**/api/v1/flights', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '{"items":[]}' }),
    );

    await page.goto(`/flights/${FLIGHT_ID}/edit`);
    await expect(page.getByTestId('flight-form')).toBeVisible();
    await expect(saveButton(page)).toBeEnabled();

    await expect(fieldErrors(page, 'flight-edit-flightDate')).toHaveCount(0);
    await expect(fieldErrors(page, 'flight-edit-startType')).toHaveCount(0);
    await expect(fieldErrors(page, 'flight-edit-startLocation')).toHaveCount(0);

    await page.getByTestId('flight-step-next').click();
    await expect(page.getByTestId('flight-step-glider')).toBeVisible();

    await expect(fieldErrors(page, 'flight-edit-glider-aircraft')).toHaveCount(0);
    await expect(fieldErrors(page, 'flight-edit-glider-flightType')).toHaveCount(0);
    await expect(fieldErrors(page, 'flight-edit-glider-pilot')).toHaveCount(0);
    await expect(fieldErrors(page, 'flight-edit-glider-nrOfLdgs')).toHaveCount(0);
  });

  test('[key-error] as-you-type inline error appears when a required field is cleared, then gates Save', async ({
    page,
  }) => {
    await stubMasterdata(page);
    await page.route(`**/api/v1/flights/${FLIGHT_ID}`, (route: Route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(fullyPopulatedDetail(TODAY)),
        });
      }
      return route.fallback();
    });
    await page.route('**/api/v1/flights', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '{"items":[]}' }),
    );

    await page.goto(`/flights/${FLIGHT_ID}/edit`);
    await expect(page.getByTestId('flight-form')).toBeVisible();
    await expect(saveButton(page)).toBeEnabled();

    const flightDate = page.getByTestId('flight-edit-flightDate').locator('input');
    await flightDate.fill('');
    await expect(fieldErrors(page, 'flight-edit-flightDate')).toBeVisible();
    await expect(saveButton(page)).toBeDisabled();

    await flightDate.fill(TODAY);
    await expect(fieldErrors(page, 'flight-edit-flightDate')).toHaveCount(0);
    await expect(saveButton(page)).toBeEnabled();
  });

  test('[edge] off-today saved flight surfaces a "View it →" post-save jump that widens the range (Option B)', async ({
    page,
  }) => {
    await stubMasterdata(page);
    await page.route('**/api/v1/flights/new-template', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(newTemplate(NEXT_WEEK)),
      }),
    );
    await page.route('**/api/v1/flights/last-context**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: 'null' }),
    );
    const newRow = {
      id: 'fl-new',
      flightAircraftType: 'GLIDER',
      flightDate: NEXT_WEEK,
      startDateTime: `${NEXT_WEEK}T08:00:00Z`,
      ldgDateTime: `${NEXT_WEEK}T09:00:00Z`,
      aircraftId: AC_GLIDER,
      processStateId: 'ps-1',
      processState: 'VALID',
      airState: 'LANDED',
      version: 1,
    };
    await page.route('**/api/v1/flights**', (route: Route) => {
      const req = route.request();
      const url = new URL(req.url());
      if (url.pathname !== '/api/v1/flights') {
        return route.fallback();
      }
      if (req.method() === 'POST') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 'fl-new', version: 1, flightDate: NEXT_WEEK }),
        });
      }
      const from = url.searchParams.get('from');
      const inRange = from === NEXT_WEEK;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: inRange ? [newRow] : [] }),
      });
    });

    await page.goto('/flights/new');
    await expect(page.getByTestId('flight-form')).toBeVisible();
    await page.getByTestId('flight-step-next').click();
    await selectAfOption(page, 'flight-edit-glider-pilot', PERSON_PILOT);
    await page.getByTestId('flight-submit-header').click();

    await expect(page.getByTestId('flight-offrange-view')).toBeVisible();
    await page.getByTestId('flight-offrange-view').click();
    await expect(page.getByTestId(`flights-row-fl-new`)).toBeVisible();
  });
});
