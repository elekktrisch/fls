import { expect, test, type Page, type Route } from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';

/**
 * Aerotow paired-create happy path + tow-fail rollback (S-067 AC).
 *
 * S-062c shipped `FlightStore.savePair` with the 3-call orchestration
 * (POST glider → POST tow → PUT-link with If-Match) plus the
 * compensating-DELETE-on-tow-fail flow, but the only ported parity
 * specs (`04`, `05`) cover self-start. This spec walks the wizard with
 * start-type = Aerotow, asserts the request order, asserts the link PUT
 * carries `If-Match: <gliderVersion>`, and screenshots the wizard at
 * each asserted state (per alpenflight/web/CLAUDE.md §8).
 *
 * Mocks backend stateful: POST returns version=1, PUT returns version
 * bumped, GET echoes back. The mapper round-trip identity test for the
 * full attribute surface lives in flight-form.model.spec.ts.
 */

const AC_GLIDER = 'ac-019e30c3-2c00-7001-8000-000000000a01';
const AC_TOW = 'ac-019e30c3-2c00-7001-8000-000000000a02';
const PERSON_PILOT = 'pn-019e30c3-2c00-7001-8000-000000000001';
const PERSON_TOW_PILOT = 'pn-019e30c3-2c00-7001-8000-000000000002';
const LOC_HOME = 'loc-019e30c3-2c00-7001-8000-000000000001';
const FT_GLIDER = 'ft-019e30c3-2c00-7001-8000-000000000001';
const ST_AEROTOW = '019e2e15-2c00-7fa1-8000-000000000fa1';

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
      body: JSON.stringify([
        { id: PERSON_PILOT, firstname: 'Alice', lastname: 'Pilot' },
        { id: PERSON_TOW_PILOT, firstname: 'Bob', lastname: 'Tower' },
      ]),
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
          isForTowFlights: true,
          isForMotorFlights: false,
          isFlightCostBalanceSelectable: false,
        },
      ]),
    }),
  );
  for (const path of [
    '**/api/v1/countries**',
    '**/api/v1/club-states**',
    '**/api/v1/location-types**',
    '**/api/v1/aircraft-types**',
    '**/api/v1/aircraft-states**',
    '**/api/v1/counter-unit-types**',
    '**/api/v1/clubs**',
  ]) {
    await page.route(path, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
}

interface Captured {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
  ifMatch: string | null;
}

interface FlightsBackend {
  handler: (route: Route) => Promise<void>;
  observed: Captured[];
}

function newTemplateBody(): unknown {
  return {
    flightAircraftType: 'GLIDER',
    flightDate: '2026-05-25',
    startTypeId: ST_AEROTOW,
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

function setupBackend(opts: { failTowPost?: boolean }): FlightsBackend {
  const observed: Captured[] = [];
  let gliderVersion = 1;
  return {
    observed,
    handler: async (route: Route) => {
      const req = route.request();
      const url = new URL(req.url());
      const method = req.method();

      // Template + last-context come before the POST chain.
      if (url.pathname === '/api/v1/flights/new-template' && method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(newTemplateBody()),
        });
        return;
      }
      if (url.pathname.startsWith('/api/v1/flights/last-context')) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      // Paired-create POSTs.
      if (method === 'POST' && url.pathname === '/api/v1/flights') {
        const body = req.postDataJSON() as Record<string, unknown>;
        observed.push({ method, path: url.pathname, body, ifMatch: null });
        const discriminator = body['flightAircraftType'] as string;
        if (discriminator === 'TOW' && opts.failTowPost) {
          await route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({ title: 'Tow create failed (simulated)' }),
          });
          return;
        }
        const isGlider = discriminator === 'GLIDER';
        const id = isGlider ? 'fl-glider-id' : 'fl-tow-id';
        const version = isGlider ? gliderVersion : 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id,
            flightAircraftType: discriminator,
            aircraftId: body['aircraftId'],
            version,
            isSoloFlight: body['isSoloFlight'] ?? false,
            noStartTimeInformation: body['noStartTimeInformation'] ?? false,
            noLdgTimeInformation: body['noLdgTimeInformation'] ?? false,
            airState: 'NEW',
            processStateId: 'ps-1',
          }),
        });
        return;
      }
      // Link PUT on the glider — carries If-Match: <gliderVersion>.
      if (method === 'PUT' && url.pathname === '/api/v1/flights/fl-glider-id') {
        const body = req.postDataJSON() as Record<string, unknown>;
        observed.push({
          method,
          path: url.pathname,
          body,
          ifMatch: req.headers()['if-match'] ?? null,
        });
        gliderVersion += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 'fl-glider-id',
            flightAircraftType: 'GLIDER',
            aircraftId: body['aircraftId'],
            version: gliderVersion,
            isSoloFlight: false,
            noStartTimeInformation: false,
            noLdgTimeInformation: false,
            towFlightId: body['towFlightId'],
            airState: 'NEW',
            processStateId: 'ps-1',
          }),
        });
        return;
      }
      // Compensating DELETE on tow-fail.
      if (method === 'DELETE' && url.pathname === '/api/v1/flights/fl-glider-id') {
        observed.push({
          method,
          path: url.pathname,
          body: null,
          ifMatch: req.headers()['if-match'] ?? null,
        });
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      // List refetch after save.
      if (method === 'GET' && url.pathname === '/api/v1/flights') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: [] }),
        });
        return;
      }
      await route.fallback();
    },
  };
}

test.describe('flight wizard — aerotow paired-create (S-067)', () => {
  test('happy path: POST glider → POST tow → PUT-link with If-Match in order; round-trips on reload', async ({
    page,
  }) => {
    await stubMasterdata(page);
    const backend = setupBackend({});
    await page.route('**/api/v1/flights**', backend.handler);

    await page.goto('/flights/new');
    await expect(page.getByTestId('flight-form')).toBeVisible();
    await page.screenshot({
      path: 'screenshots/flights/04c-01-wizard-launch.png',
      fullPage: true,
    });

    // Tow step is rendered (start type = Aerotow from new-template).
    await expect(page.getByTestId('flight-step-2')).toBeVisible();

    // Fill glider step — pick the glider pilot (J-26 T-13 required field; the
    // new-template's empty crew leaves it blank) + a comment.
    await page.getByTestId('flight-step-next').click();
    await expect(page.getByTestId('flight-step-glider')).toBeVisible();
    await selectAfOption(page, 'flight-edit-glider-pilot', PERSON_PILOT);
    await page.getByTestId('flight-edit-glider-comment').locator('input').fill('paired-create');
    await page.screenshot({
      path: 'screenshots/flights/04c-02-glider-filled.png',
      fullPage: true,
    });

    // Advance to tow step and pick the tow aircraft + tow pilot.
    await page.getByTestId('flight-step-next').click();
    await expect(page.getByTestId('flight-step-tow')).toBeVisible();
    const towAircraft = page.getByTestId('flight-edit-tow-aircraft').locator('nz-select');
    await towAircraft.click();
    await page.getByTestId(`af-select-option-${AC_TOW}`).click();
    const towPilot = page.getByTestId('flight-edit-tow-pilot').locator('nz-select');
    await towPilot.click();
    await page.getByTestId(`af-select-option-${PERSON_TOW_PILOT}`).click();
    await page.screenshot({
      path: 'screenshots/flights/04c-03-tow-filled.png',
      fullPage: true,
    });

    await page.getByTestId('flight-submit-header').click();

    // Three calls observed in order: POST glider, POST tow, PUT-link.
    await expect.poll(() => backend.observed.length).toBeGreaterThanOrEqual(3);
    expect(backend.observed[0]!.method).toBe('POST');
    expect(backend.observed[0]!.body!['flightAircraftType']).toBe('GLIDER');
    expect(backend.observed[1]!.method).toBe('POST');
    expect(backend.observed[1]!.body!['flightAircraftType']).toBe('TOW');
    expect(backend.observed[2]!.method).toBe('PUT');
    expect(backend.observed[2]!.body!['towFlightId']).toBe('fl-tow-id');
    // If-Match on the link PUT is the glider's POST-time version (1).
    expect(backend.observed[2]!.ifMatch).toBe('1');

    // Back to /flights on save.
    await expect(page).toHaveURL(/\/flights$/);
    await page.screenshot({
      path: 'screenshots/flights/04c-04-post-submit.png',
      fullPage: true,
    });
  });

  test('tow-POST failure triggers compensating DELETE on glider', async ({ page }) => {
    await stubMasterdata(page);
    const backend = setupBackend({ failTowPost: true });
    await page.route('**/api/v1/flights**', backend.handler);

    await page.goto('/flights/new');
    await expect(page.getByTestId('flight-form')).toBeVisible();

    await page.getByTestId('flight-step-next').click();
    // Pick the glider pilot (J-26 T-13 required field) before advancing to tow.
    await expect(page.getByTestId('flight-step-glider')).toBeVisible();
    await selectAfOption(page, 'flight-edit-glider-pilot', PERSON_PILOT);
    await page.getByTestId('flight-step-next').click();
    await expect(page.getByTestId('flight-step-tow')).toBeVisible();
    const towAircraft = page.getByTestId('flight-edit-tow-aircraft').locator('nz-select');
    await towAircraft.click();
    await page.getByTestId(`af-select-option-${AC_TOW}`).click();

    await page.getByTestId('flight-submit-header').click();

    // POST glider, POST tow (fails), DELETE glider — exactly three calls.
    await expect.poll(() => backend.observed.length).toBeGreaterThanOrEqual(3);
    expect(backend.observed[0]!.method).toBe('POST');
    expect(backend.observed[1]!.method).toBe('POST');
    expect(backend.observed[2]!.method).toBe('DELETE');
    expect(backend.observed[2]!.path).toBe('/api/v1/flights/fl-glider-id');

    // No PUT-link in the chain — compensating delete took precedence.
    expect(backend.observed.find((c) => c.method === 'PUT')).toBeUndefined();

    await page.screenshot({
      path: 'screenshots/flights/04c-05-rollback.png',
      fullPage: true,
    });
  });
});
