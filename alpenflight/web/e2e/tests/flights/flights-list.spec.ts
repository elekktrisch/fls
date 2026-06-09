import { expect, test, type Page, type Route } from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';

/**
 * Logbook (flight list) smoke. Booted under the `mock-auth` Angular
 * configuration; the principal is a mocked SYSTEM_ADMINISTRATOR so the
 * mutation affordances (new + kebab actions) render. All `/api/v1/*`
 * calls are intercepted via `page.route` — no live backend.
 *
 * Coverage:
 *   - List renders all rows from a seeded GET /api/v1/flights response.
 *   - Date-range filter round-trips to the server (`from`/`to`).
 *   - Client-side air-state filter narrows the visible rows over the
 *     loaded page without re-querying.
 *   - Kebab menu → edit-placeholder navigation.
 *
 * Reference: docs/modernization/stories/S-062b-flight-list-page.md.
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';
const GLIDER_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';
const MOTOR_TYPE_ID = '019e2e15-2c00-7afc-8000-000000002afc';
const STATE_OK_ID = '019e2e15-2c00-7ee0-8000-000000002ee0';
const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';
const PROC_STATE_VALID_ID = '019e2e15-2c00-7100-8000-000000007002';

interface MockFlightListItem {
  id: string;
  flightAircraftType: 'GLIDER' | 'TOW' | 'MOTOR';
  flightDate: string;
  startDateTime: string;
  ldgDateTime: string;
  aircraftId: string;
  processStateId: string;
  processState:
    | 'NOT_PROCESSED'
    | 'INVALID'
    | 'VALID'
    | 'LOCKED'
    | 'DELIVERY_PREPARATION_ERROR'
    | 'DELIVERY_PREPARED'
    | 'DELIVERY_BOOKED'
    | 'EXCLUDED_FROM_DELIVERY_PROCESS';
  airState:
    | 'NEW'
    | 'FLIGHT_PLAN_OPEN'
    | 'MIGHT_BE_STARTED'
    | 'STARTED'
    | 'MIGHT_BE_LANDED_OR_IN_AIR'
    | 'LANDED'
    | 'FLIGHT_PLAN_CLOSED';
  version: number;
}

const AC_GLI = 'ac-019e30c3-2c00-7001-8000-000000000a01';
const AC_TOW = 'ac-019e30c3-2c00-7001-8000-000000000a02';

const allFlights: MockFlightListItem[] = [
  {
    id: 'fl-019e30c3-2c00-7001-8000-000000000001',
    flightAircraftType: 'GLIDER',
    flightDate: '2026-05-21',
    startDateTime: '2026-05-21T08:42:00Z',
    ldgDateTime: '2026-05-21T10:14:00Z',
    aircraftId: AC_GLI,
    processStateId: PROC_STATE_VALID_ID,
    processState: 'VALID',
    airState: 'LANDED',
    version: 1,
  },
  {
    id: 'fl-019e30c3-2c00-7001-8000-000000000002',
    flightAircraftType: 'TOW',
    flightDate: '2026-05-21',
    startDateTime: '2026-05-21T08:42:00Z',
    ldgDateTime: '2026-05-21T08:50:00Z',
    aircraftId: AC_TOW,
    processStateId: PROC_STATE_VALID_ID,
    processState: 'VALID',
    airState: 'LANDED',
    version: 1,
  },
  {
    id: 'fl-019e30c3-2c00-7001-8000-000000000003',
    flightAircraftType: 'GLIDER',
    flightDate: '2026-05-22',
    startDateTime: '2026-05-22T09:10:00Z',
    ldgDateTime: '2026-05-22T11:55:00Z',
    aircraftId: AC_GLI,
    processStateId: PROC_STATE_VALID_ID,
    processState: 'VALID',
    airState: 'STARTED',
    version: 1,
  },
];

const mockAircraftList = [
  {
    id: AC_GLI,
    ownerClubId: CLUB_A_ID,
    immatriculation: 'HB-GLI',
    aircraftTypeId: GLIDER_TYPE_ID,
    aircraftTypeCode: 'GLIDER',
    hasEngine: false,
    isTowingAircraft: false,
  },
  {
    id: AC_TOW,
    ownerClubId: CLUB_A_ID,
    immatriculation: 'HB-TOW',
    aircraftTypeId: MOTOR_TYPE_ID,
    aircraftTypeCode: 'MOTOR_AIRCRAFT',
    hasEngine: true,
    isTowingAircraft: true,
  },
];

async function stubReferenceData(page: Page): Promise<void> {
  await page.route('**/api/v1/countries**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: CH_COUNTRY_ID, iso2Code: 'CH', name: 'Switzerland' }]),
    }),
  );
  await page.route('**/api/v1/club-states**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: CLUB_STATE_ID, code: 'ACTIVE', name: 'Active' }]),
    }),
  );
  await page.route('**/api/v1/location-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/aircraft-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: GLIDER_TYPE_ID,
          code: 'GLIDER',
          description: 'Glider',
          hasEngine: false,
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
      ]),
    }),
  );
  await page.route('**/api/v1/aircraft-states**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        { id: STATE_OK_ID, code: 'OK', description: 'Airworthy', isAircraftFlyable: true },
      ]),
    }),
  );
  await page.route('**/api/v1/counter-unit-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: CLUB_A_ID,
          name: 'Test Club A',
          slug: 'test-club-a',
          countryId: CH_COUNTRY_ID,
          clubStateId: CLUB_STATE_ID,
        },
      ]),
    }),
  );
  await page.route('**/api/v1/aircraft', (route) => {
    const u = new URL(route.request().url());
    if (u.pathname === '/api/v1/aircraft') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockAircraftList),
      });
    }
    return route.fallback();
  });
}

function setupFlightsBackend(flights: readonly MockFlightListItem[]): {
  handler: (route: Route) => Promise<void>;
  lastParams: { from?: string; to?: string };
} {
  const state: { from?: string; to?: string } = {};
  return {
    lastParams: state,
    handler: async (route: Route) => {
      const req = route.request();
      const url = new URL(req.url());
      if (req.method() !== 'GET' || url.pathname !== '/api/v1/flights') {
        await route.fallback();
        return;
      }
      const from = url.searchParams.get('from') ?? undefined;
      const to = url.searchParams.get('to') ?? undefined;
      if (from !== undefined) state.from = from;
      else delete state.from;
      if (to !== undefined) state.to = to;
      else delete state.to;
      const filtered = flights.filter((f) => {
        if (from && f.flightDate < from) return false;
        if (to && f.flightDate > to) return false;
        return true;
      });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: filtered }),
      });
    },
  };
}

test.describe('flights list page', () => {
  test('renders rows, applies server date filter, applies client air-state filter, navigates via kebab', async ({
    page,
  }) => {
    await stubReferenceData(page);
    const { handler: flightsHandler, lastParams } = setupFlightsBackend(allFlights);
    await page.route('**/api/v1/flights**', flightsHandler);

    await page.goto('/flights');

    // Initial load: 3 rows visible.
    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');
    await expect(page.getByTestId(`flights-row-${allFlights[0].id}`)).toBeVisible();
    await expect(page.getByTestId(`flights-row-${allFlights[1].id}`)).toBeVisible();
    await expect(page.getByTestId(`flights-row-${allFlights[2].id}`)).toBeVisible();

    // Immatriculation is resolved via AircraftStore lookup.
    await expect(page.getByTestId(`flights-immat-${allFlights[0].id}`)).toHaveText('HB-GLI');
    await expect(page.getByTestId(`flights-immat-${allFlights[1].id}`)).toHaveText('HB-TOW');

    // Air state + process state pills show resolved labels.
    await expect(page.getByTestId(`flights-air-state-${allFlights[0].id}`)).toContainText('Landed');
    await expect(page.getByTestId(`flights-process-state-${allFlights[0].id}`)).toContainText(
      'Valid',
    );
    // Aircraft-type pill renders the legacy-equivalent label.
    await expect(page.getByTestId(`flights-aircraft-type-${allFlights[0].id}`)).toContainText(
      'Glider',
    );
    // Duration is computed from start/landing times.
    await expect(page.getByTestId(`flights-duration-${allFlights[0].id}`)).toHaveText('01:32');

    // Column-inventory parity guard: the row exposes Takeoff + Landing labels
    // but NOT pilot / location / comment / tow columns (deferred per S-062a).
    const firstRow = page.getByTestId(`flights-row-${allFlights[0].id}`);
    await expect(firstRow).toContainText('Takeoff');
    await expect(firstRow).toContainText('Landing');
    await expect(firstRow).not.toContainText('Pilot');
    await expect(firstRow).not.toContainText('Comment');
    await expect(firstRow).not.toContainText('Tow ');

    // Row body click → navigates to the edit placeholder (legacy parity:
    // whole-row click opens edit). Verified once, then the same target via
    // the kebab menu below.
    await firstRow.click();
    await expect(page).toHaveURL(new RegExp(`/flights/${allFlights[0].id}/edit$`));
    // The edit wizard mounts on the same URL — assert on its stable testid
    // (either the form mounts after loadDetail resolves, or the loading
    // placeholder shows while the GET pends).
    await expect(
      page.getByTestId('flight-form').or(page.getByTestId('flight-loading')),
    ).toBeVisible();
    await page.goBack();

    // Apply a client-side air-state filter (Started). Both Landed rows hide;
    // the Started row stays. The server is NOT re-queried for this — the
    // client narrows the loaded page.
    //
    // S-062f: drive the nz-select through `selectAfOption`, which waits for the
    // freshly-opened option to be VISIBLE before clicking and for the overlay to
    // detach after. This is the flake fix — a bare click here raced the prior
    // edit-route portal that the `page.goBack()` above left detaching.
    await selectAfOption(page, 'flights-air-state-filter', 'STARTED');

    await expect(page.getByTestId('flights-summary')).toContainText('1 of 3 flights');
    await expect(page.getByTestId(`flights-row-${allFlights[2].id}`)).toBeVisible();
    await expect(page.getByTestId(`flights-row-${allFlights[0].id}`)).toBeHidden();

    // Clear filters restores all rows.
    await page.getByTestId('flights-clear-filters').click();
    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');

    // Kebab → Edit → navigates to the placeholder route.
    await page.getByTestId(`flights-kebab-${allFlights[0].id}`).click();
    await page.getByTestId(`flights-edit-${allFlights[0].id}`).click();
    await expect(page).toHaveURL(new RegExp(`/flights/${allFlights[0].id}/edit$`));
    await expect(
      page.getByTestId('flight-form').or(page.getByTestId('flight-loading')),
    ).toBeVisible();
    await page.goBack();

    // The date range filter is a single nz-range-picker (S-062e fixed the
    // zoneless deadlock that had forced the two-single-picker workaround).
    await expect(page.getByTestId('flights-date-range').locator('input').first()).toBeVisible();
    // No date params on initial load.
    expect(lastParams.from).toBeUndefined();
    expect(lastParams.to).toBeUndefined();
  });

  // AC11 / J-6b T-13 REGRESSION GUARD — the flights date-range picker FILTERS the
  // list (the operator's "currently broken" #12). T-13's root cause was an
  // asymmetric ISO↔Date round-trip in the list page: the picker→store→server
  // `from`/`to` wiring dropped silently in negative-offset zones, so a picked
  // range never reached the server. The pure helpers got Vitest specs; THIS is
  // the e2e that the picker→store→server param round-trip actually fires.
  //
  // SCOPE NOTE (T-20 collateral iii): J-6b's per-push gate `mock_test:` scopes to
  // the THREE J-6b spec stems (reservations/planning/forms), so this flights spec
  // does NOT run in J-6b's per-push gate — it runs in the FULL nightly suite +
  // the §4 done-gate. AC11 is honestly covered here (the flights feature's own
  // spec, its natural home), not bolted onto a reservations/planning spec.
  test('the date-range picker round-trips from/to to the server and filters the list (AC11 / T-13)', async ({
    page,
  }) => {
    await stubReferenceData(page);
    const { handler: flightsHandler, lastParams } = setupFlightsBackend(allFlights);
    await page.route('**/api/v1/flights**', flightsHandler);

    await page.goto('/flights');
    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');
    // Initial load sends NO date params.
    expect(lastParams.from).toBeUndefined();
    expect(lastParams.to).toBeUndefined();

    // Pick a from+to range. ng-zorro renders two calendar panels; the first
    // non-disabled cell click sets the range start, the second sets the end
    // (idiom from primitives-date-picker.spec.ts:62-75). Using in-view cells
    // keeps the spec independent of the current month — the round-trip (params
    // SENT, not their exact values) is what T-13 fixed.
    await page.getByTestId('flights-date-range').locator('input').first().click();
    const overlay = page.locator('.cdk-overlay-container .ant-picker-panel-container');
    await expect(overlay).toBeVisible();
    const days = overlay.locator(
      '.ant-picker-cell-in-view:not(.ant-picker-cell-disabled) .ant-picker-cell-inner',
    );
    await days.nth(2).click();
    await days.nth(18).click();

    // The picker→store→server wiring T-13 fixed now SENDS both date params on the
    // refetch — the regression (params silently dropped) would leave them
    // undefined. Poll lastParams via expect.poll so the assertion waits for the
    // debounced store refetch (no fixed timeout — zoneless-safe).
    await expect
      .poll(() => lastParams.from, {
        message: 'the picked range sends a `from` date param to the server (T-13 round-trip)',
      })
      .toMatch(/^\d{4}-\d{2}-\d{2}$/);
    await expect
      .poll(() => lastParams.to, {
        message: 'the picked range sends a `to` date param to the server (T-13 round-trip)',
      })
      .toMatch(/^\d{4}-\d{2}-\d{2}$/);
    // `from` is on/before `to` — the bridge preserves the [start, end] order.
    expect(lastParams.from! <= lastParams.to!).toBe(true);

    await page.screenshot({
      path: 'screenshots/flights/04-date-range-filtered.png',
      fullPage: true,
    });
  });

  test('visual snapshots — populated, filtered, empty', async ({ page }) => {
    await stubReferenceData(page);
    const { handler: flightsHandler } = setupFlightsBackend(allFlights);
    await page.route('**/api/v1/flights**', flightsHandler);

    // 01 — populated list (default state, three rows).
    await page.goto('/flights');
    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');
    await page.screenshot({ path: 'screenshots/flights/01-populated.png', fullPage: true });

    // 02 — same list, with the Started air-state filter applied.
    await selectAfOption(page, 'flights-air-state-filter', 'STARTED');
    await expect(page.getByTestId('flights-summary')).toContainText('1 of 3 flights');
    await page.screenshot({ path: 'screenshots/flights/02-filtered.png', fullPage: true });

    // 03 — empty state (server returns zero rows). Use a fresh page so the
    // store re-initialises with the empty backend.
    const emptyPage = await page.context().newPage();
    await stubReferenceData(emptyPage);
    await emptyPage.route('**/api/v1/flights**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      });
    });
    await emptyPage.goto('/flights');
    await expect(emptyPage.getByTestId('flights-empty')).toContainText('No flights yet');
    await emptyPage.screenshot({ path: 'screenshots/flights/03-empty.png', fullPage: true });
    await emptyPage.close();
  });
});
