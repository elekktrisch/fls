import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';


const MOCK_PERSON_ID = 'pn-019e30c3-2c00-7100-8000-0000000000a5';
const FLIGHT_ID = 'fl-019e30c3-2c00-7165-8000-000000000001';
const AIRCRAFT_ID = 'ac-019e30c3-2c00-7165-8000-000000000a01';
const START_LOC_ID = '019e30c3-2c00-7165-8000-000000000b01';
const LDG_LOC_ID = '019e30c3-2c00-7165-8000-000000000b02';
const FLIGHT_TYPE_ID = '019e30c3-2c00-7165-8000-000000000c01';
const PIC_CREW_TYPE_ID = '019e2e15-2c00-76b0-8000-0000000036b0';
const GLIDER_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';
const PROC_STATE_VALID_ID = '019e2e15-2c00-7100-8000-000000007002';

const mockAircraft = [
  {
    id: AIRCRAFT_ID,
    ownerClubId: '019e30c3-2c00-7001-8000-000000000001',
    immatriculation: 'HB-S165',
    aircraftTypeId: GLIDER_TYPE_ID,
    aircraftTypeCode: 'GLIDER',
    hasEngine: false,
    isTowingAircraft: false,
  },
];

const mockLocations = [
  {
    id: START_LOC_ID,
    locationName: 'Grenchen',
    icaoCode: 'LSZG',
    isAirfield: true,
    isFastEntryRecord: false,
  },
  {
    id: LDG_LOC_ID,
    locationName: 'Speck',
    icaoCode: 'LSZK',
    isAirfield: true,
    isFastEntryRecord: false,
  },
];

const mockFlightTypes = [
  {
    id: FLIGHT_TYPE_ID,
    flightTypeName: 'Private',
    flightCode: 'PRIVATE',
    isForGliderFlights: true,
    isForTowFlights: false,
    isForMotorFlights: false,
    isFlightCostBalanceSelectable: false,
  },
];

const mockMyFlight = {
  id: FLIGHT_ID,
  flightAircraftType: 'GLIDER',
  flightDate: '2026-05-21',
  startDateTime: '2026-05-21T08:42:00Z',
  ldgDateTime: '2026-05-21T10:14:00Z',
  aircraftId: AIRCRAFT_ID,
  startLocationId: START_LOC_ID,
  ldgLocationId: LDG_LOC_ID,
  flightTypeId: FLIGHT_TYPE_ID,
  processStateId: PROC_STATE_VALID_ID,
  processState: 'VALID',
  airState: 'LANDED',
  version: 1,
  crew: [
    {
      personId: MOCK_PERSON_ID,
      flightCrewTypeId: PIC_CREW_TYPE_ID,
    },
  ],
};

async function stubMe(page: Page, personId: string | null = MOCK_PERSON_ID): Promise<void> {
  await page.route('**/api/v1/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'mock-sysadmin',
        personId,
        clubId: 'clb-019e30c3-2c00-7001-8000-000000000001',
        roles: ['SYSTEM_ADMINISTRATOR', 'CLUB_ADMINISTRATOR'],
        firstName: 'Mock',
        lastName: 'Sysadmin',
        email: 'mock@local',
        username: 'mock-sysadmin',
      }),
    }),
  );
}

async function stubPickerStores(page: Page): Promise<void> {
  await page.route('**/api/v1/aircraft', (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/v1/aircraft') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockAircraft),
      });
    }
    return route.fallback();
  });
  await page.route('**/api/v1/locations**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocations),
    }),
  );
  await page.route('**/api/v1/flight-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockFlightTypes),
    }),
  );
  await page.route('**/api/v1/persons**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/countries**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/club-states**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/aircraft-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/aircraft-states**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/location-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/counter-unit-types**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
}

function flightsListHandler(items: unknown[]) {
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    if (req.method() !== 'GET' || url.pathname !== '/api/v1/flights') {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items }),
    });
  };
}

async function stubFlightDetail(page: Page, detail: unknown): Promise<void> {
  await page.route(`**/api/v1/flights/${FLIGHT_ID}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(detail),
    }),
  );
}

async function gotoPilotView(page: Page): Promise<void> {
  await page.goto('/start?lang=en');
  await page.getByTestId('start-pilot-view-toggle').click();
  await expect(page.getByTestId('start-variant-pilot')).toBeVisible();
}

test.describe('home (/start) dashboard', () => {
  test('greets the user, shows the last-flight card, navigates to detail on click', async ({
    page,
  }) => {
    await stubMe(page);
    await stubPickerStores(page);
    await page.route('**/api/v1/flights**', flightsListHandler([mockMyFlight]));
    await stubFlightDetail(page, mockMyFlight);

    await gotoPilotView(page);

    await expect(page.getByTestId('start-greeting')).toBeVisible();
    await expect(page.getByTestId('start-today')).toBeVisible();
    const lastCard = page.getByTestId('start-last-flight-card');
    await expect(lastCard).toBeVisible();
    await expect(lastCard).toContainText('HB-S165');
    await expect(lastCard).toContainText('21.05.2026');
    await expect(page.getByTestId('start-last-flight-role')).toHaveText('PIC');

    await expect(page.getByTestId('start-reservation-placeholder')).toBeVisible();
    await expect(page.getByTestId('start-quick-open-logbook')).toBeVisible();
    await expect(page.getByTestId('start-quick-log-flight')).toBeVisible();

    await page.screenshot({ path: 'screenshots/start/01-populated.png', fullPage: true });

    await lastCard.click();
    await expect(page).toHaveURL(new RegExp(`/flights/${FLIGHT_ID}/edit$`));
  });

  test('empty state when the user has no flights', async ({ page }) => {
    await stubMe(page);
    await stubPickerStores(page);
    await page.route('**/api/v1/flights**', flightsListHandler([]));

    await gotoPilotView(page);

    const empty = page.getByTestId('start-last-flight-empty');
    await expect(empty).toBeVisible();
    const emptyCta = page.getByTestId('start-empty-cta');
    await expect(emptyCta).toBeVisible();
    await expect(emptyCta).toHaveText('Log your first flight');

    await page.screenshot({ path: 'screenshots/start/02-empty.png', fullPage: true });

    await emptyCta.click();
    await expect(page).toHaveURL(/\/flights\/new$/);
  });

  test('quick-action buttons navigate to logbook and new-flight', async ({ page }) => {
    await stubMe(page);
    await stubPickerStores(page);
    await page.route('**/api/v1/flights**', flightsListHandler([]));

    await gotoPilotView(page);

    await page.getByTestId('start-quick-open-logbook').click();
    await expect(page).toHaveURL(/\/flights$/);

    await gotoPilotView(page);

    await page.getByTestId('start-quick-log-flight').click();
    await expect(page).toHaveURL(/\/flights\/new$/);
  });
});
