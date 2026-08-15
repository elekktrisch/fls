import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

const AC_GLIDER = 'ac-019e30c3-2c00-7001-8000-000000000a01';
const PERSON_PILOT = 'pn-019e30c3-2c00-7001-8000-000000000001';
const LOC_HOME = 'loc-019e30c3-2c00-7001-8000-000000000001';
const FT_GLIDER = 'ft-019e30c3-2c00-7001-8000-000000000001';
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
  for (const path of [
    'countries',
    'club-states',
    'location-types',
    'aircraft-types',
    'aircraft-states',
    'counter-unit-types',
    'clubs',
  ]) {
    await page.route(`**/api/v1/${path}**`, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
}

async function stubFlightDetail(
  page: Page,
  onUpdate: (body: unknown, ifMatch: string | null) => void,
): Promise<void> {
  await page.route(`**/api/v1/flights/${FLIGHT_ID}`, (route: Route) => {
    const req = route.request();
    if (req.method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: FLIGHT_ID,
          flightAircraftType: 'GLIDER',
          aircraftId: AC_GLIDER,
          flightDate: '2026-05-20',
          startTypeId: 'st-self',
          startLocationId: LOC_HOME,
          ldgLocationId: LOC_HOME,
          flightTypeId: FT_GLIDER,
          isSoloFlight: false,
          noStartTimeInformation: false,
          noLdgTimeInformation: false,
          airState: 'LANDED',
          processStateId: 'ps-1',
          version: 7,
          crew: [{ personId: PERSON_PILOT, flightCrewTypeId: PILOT_CREW_TYPE }],
          comment: 'before edit',
        }),
      });
    }
    if (req.method() === 'PUT') {
      onUpdate(req.postDataJSON(), req.headers()['if-match'] ?? null);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: FLIGHT_ID,
          flightAircraftType: 'GLIDER',
          aircraftId: AC_GLIDER,
          isSoloFlight: false,
          noStartTimeInformation: false,
          noLdgTimeInformation: false,
          airState: 'LANDED',
          processStateId: 'ps-1',
          version: 8,
        }),
      });
    }
    return route.fallback();
  });
  await page.route('**/api/v1/flights', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [] }),
    }),
  );
}

test.describe('flight edit — edit existing (parity port)', () => {
  test('edits a glider flight comment and PUTs with If-Match', async ({ page }) => {
    await stubMasterdata(page);
    let captured: { body: unknown; ifMatch: string | null } | null = null;
    await stubFlightDetail(page, (body, ifMatch) => {
      captured = { body, ifMatch };
    });

    await page.goto(`/flights/${FLIGHT_ID}/edit`);

    await expect(page.getByTestId('flight-form')).toBeVisible();
    await page.screenshot({ path: 'screenshots/flights/05-01-edit-loaded.png', fullPage: true });
    await page.getByTestId('flight-step-next').click();
    const commentInput = page.getByTestId('flight-edit-glider-comment').locator('input');
    await commentInput.fill('after edit');
    await page.screenshot({ path: 'screenshots/flights/05-02-edit-glider.png', fullPage: true });

    await page.getByTestId('flight-submit-header').click();

    await expect.poll(() => captured).not.toBeNull();
    expect(captured!.ifMatch).toBe('7');
    const body = captured!.body as Record<string, unknown>;
    expect(body['aircraftId']).toBe(AC_GLIDER);
    expect(body['comment']).toBe('after edit');

    await expect(page).toHaveURL(/\/flights$/);
  });
});
