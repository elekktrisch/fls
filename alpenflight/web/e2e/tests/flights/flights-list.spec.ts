import { type Page, type Route } from '@playwright/test';
import { expect, installMockApiStubs, test, watchConsoleErrors } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';

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

const TODAY = (() => {
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
})();

const A_DATE_THE_TODAY_DEFAULT_RANGE_EXCLUDES = '2026-01-01';

const allFlights: [MockFlightListItem, MockFlightListItem, MockFlightListItem] = [
  {
    id: 'fl-019e30c3-2c00-7001-8000-000000000001',
    flightAircraftType: 'GLIDER',
    flightDate: TODAY,
    startDateTime: `${TODAY}T08:42:00Z`,
    ldgDateTime: `${TODAY}T10:14:00Z`,
    aircraftId: AC_GLI,
    processStateId: PROC_STATE_VALID_ID,
    processState: 'VALID',
    airState: 'LANDED',
    version: 1,
  },
  {
    id: 'fl-019e30c3-2c00-7001-8000-000000000002',
    flightAircraftType: 'TOW',
    flightDate: TODAY,
    startDateTime: `${TODAY}T08:42:00Z`,
    ldgDateTime: `${TODAY}T08:50:00Z`,
    aircraftId: AC_TOW,
    processStateId: PROC_STATE_VALID_ID,
    processState: 'VALID',
    airState: 'LANDED',
    version: 1,
  },
  {
    id: 'fl-019e30c3-2c00-7001-8000-000000000003',
    flightAircraftType: 'GLIDER',
    flightDate: TODAY,
    startDateTime: `${TODAY}T09:10:00Z`,
    ldgDateTime: `${TODAY}T11:55:00Z`,
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
  test('renders rows, sends the today-default from/to to the server, narrows client-side by air state, navigates via kebab', async ({
    page,
  }) => {
    await stubReferenceData(page);
    const { handler: flightsHandler, lastParams } = setupFlightsBackend(allFlights);
    await page.route('**/api/v1/flights**', flightsHandler);

    await page.goto('/flights');

    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');
    await expect(page.getByTestId(`flights-row-${allFlights[0].id}`)).toBeVisible();
    await expect(page.getByTestId(`flights-row-${allFlights[1].id}`)).toBeVisible();
    await expect(page.getByTestId(`flights-row-${allFlights[2].id}`)).toBeVisible();

    await expect(page.getByTestId(`flights-immat-${allFlights[0].id}`)).toHaveText('HB-GLI');
    await expect(page.getByTestId(`flights-immat-${allFlights[1].id}`)).toHaveText('HB-TOW');

    await expect(page.getByTestId(`flights-air-state-${allFlights[0].id}`)).toContainText('Landed');
    await expect(page.getByTestId(`flights-process-state-${allFlights[0].id}`)).toContainText(
      'Valid',
    );
    await expect(page.getByTestId(`flights-aircraft-type-${allFlights[0].id}`)).toContainText(
      'Glider',
    );
    await expect(page.getByTestId(`flights-duration-${allFlights[0].id}`)).toHaveText('01:32');

    const firstRow = page.getByTestId(`flights-row-${allFlights[0].id}`);
    await test.step('column inventory — takeoff + landing render; pilot / comment / tow stay deferred (S-062a)', async () => {
      await expect(firstRow).toContainText('Takeoff');
      await expect(firstRow).toContainText('Landing');
      await expect(firstRow).not.toContainText('Pilot');
      await expect(firstRow).not.toContainText('Comment');
      await expect(firstRow).not.toContainText('Tow ');
    });

    await firstRow.click();
    await expect(page).toHaveURL(new RegExp(`/flights/${allFlights[0].id}/edit$`));
    await expect(
      page.getByTestId('flight-form').or(page.getByTestId('flight-loading')),
    ).toBeVisible();
    await page.goBack();

    await selectAfOption(page, 'flights-air-state-filter', 'STARTED');

    await expect(page.getByTestId('flights-summary')).toContainText('1 of 3 flights');
    await expect(page.getByTestId(`flights-row-${allFlights[2].id}`)).toBeVisible();
    await expect(page.getByTestId(`flights-row-${allFlights[0].id}`)).toBeHidden();

    await page.getByTestId('flights-clear-filters').click();
    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');

    await page.getByTestId(`flights-kebab-${allFlights[0].id}`).click();
    await page.getByTestId(`flights-edit-${allFlights[0].id}`).click();
    await expect(page).toHaveURL(new RegExp(`/flights/${allFlights[0].id}/edit$`));
    await expect(
      page.getByTestId('flight-form').or(page.getByTestId('flight-loading')),
    ).toBeVisible();
    await page.goBack();

    await expect(page.getByTestId('flights-date-range').locator('input').first()).toBeVisible();
    expect(lastParams.from).toBe(TODAY);
    expect(lastParams.to).toBe(TODAY);
  });

  test('the date-range picker round-trips from/to to the server and filters the list (AC11 / T-13)', async ({
    page,
  }) => {
    await stubReferenceData(page);
    const { handler: flightsHandler, lastParams } = setupFlightsBackend(allFlights);
    await page.route('**/api/v1/flights**', flightsHandler);

    await page.goto('/flights');
    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');
    expect(lastParams.from).toBe(TODAY);
    expect(lastParams.to).toBe(TODAY);

    await page.getByTestId('flights-date-range').locator('input').first().click();
    const overlay = page.locator('.cdk-overlay-container .ant-picker-panel-container');
    await expect(overlay).toBeVisible();
    const inViewDayCells = overlay.locator(
      '.ant-picker-cell-in-view:not(.ant-picker-cell-disabled) .ant-picker-cell-inner',
    );
    const rangeStartCell = inViewDayCells.nth(2);
    const rangeEndCell = inViewDayCells.nth(18);
    await rangeStartCell.click();
    await rangeEndCell.click();

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
    expect(lastParams.from! <= lastParams.to!).toBe(true);

    await page.screenshot({
      path: 'screenshots/flights/04-date-range-filtered.png',
      fullPage: true,
    });
  });

  test('visual snapshots — populated, filtered, empty', async ({ page }, testInfo) => {
    await stubReferenceData(page);
    const { handler: flightsHandler } = setupFlightsBackend(allFlights);
    await page.route('**/api/v1/flights**', flightsHandler);

    await page.goto('/flights');
    await expect(page.getByTestId('flights-summary')).toContainText('3 flights');
    await page.screenshot({ path: 'screenshots/flights/01-populated.png', fullPage: true });

    await selectAfOption(page, 'flights-air-state-filter', 'STARTED');
    await expect(page.getByTestId('flights-summary')).toContainText('1 of 3 flights');
    await page.screenshot({ path: 'screenshots/flights/02-filtered.png', fullPage: true });

    const emptyPage = await page.context().newPage();
    watchConsoleErrors(emptyPage, testInfo);
    await installMockApiStubs(emptyPage);
    await stubReferenceData(emptyPage);
    await emptyPage.route('**/api/v1/flights**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      });
    });
    await emptyPage.goto('/flights');
    await expect(emptyPage.getByTestId('flights-empty')).toContainText('No matching flights');
    await emptyPage.screenshot({ path: 'screenshots/flights/03-empty.png', fullPage: true });
    await emptyPage.close();
  });

  test('empty under the today-default range shows the no-match copy, not the true-empty copy', async ({
    page,
  }) => {
    await stubReferenceData(page);
    const flightsDatedOutsideTheTodayDefaultRange = allFlights.map((f) => ({
      ...f,
      flightDate: A_DATE_THE_TODAY_DEFAULT_RANGE_EXCLUDES,
    }));
    const { handler } = setupFlightsBackend(flightsDatedOutsideTheTodayDefaultRange);
    await page.route('**/api/v1/flights**', handler);

    await page.goto('/flights');
    const empty = page.getByTestId('flights-empty');
    await expect(empty).toContainText('No matching flights');
    await expect(empty).toContainText('date range');
    await expect(empty).not.toContainText('No flights yet');
  });

  test('empty with the date range cleared (show-all) shows the true-empty copy', async ({
    page,
  }) => {
    await stubReferenceData(page);
    const { handler } = setupFlightsBackend([]);
    await page.route('**/api/v1/flights**', handler);

    await page.goto('/flights');
    const picker = page.getByTestId('flights-date-range');
    const clearTheTodayDefaultRange = picker.locator('.ant-picker-clear');
    await picker.hover();
    await clearTheTodayDefaultRange.click();

    await expect(page.getByTestId('flights-empty')).toContainText('No flights yet');
    await expect(page.getByTestId('flights-empty')).not.toContainText('No matching flights');
  });
});
