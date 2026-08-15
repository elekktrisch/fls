import { type Page, type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';


const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';

const LOCATION_BERN_ID = '019e30c3-2c00-7001-8000-00000000c001';
const LOCATION_THUN_ID = '019e30c3-2c00-7001-8000-00000000c002';

const INSTRUCTOR_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const TOWPILOT_ID = '019e30c3-2c00-7001-8000-0000000000b2';
const FLIGHTOP_ID = '019e30c3-2c00-7001-8000-0000000000b3';

const SEED_DAY_ID = '019e30c3-2c00-7001-8000-000000000e01';
const SEED_DAY_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000f01';

function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function nextSaturdayKey(): string {
  const d = new Date();
  const delta = (6 - d.getDay() + 7) % 7 || 7;
  d.setDate(d.getDate() + delta);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const SEED_DAY_KEY = dayKeyFromToday(3);
const WEEKEND_DAY_KEY = nextSaturdayKey();

interface MockPlanningDay {
  id: string;
  operatingClubId: string;
  date: string;
  locationId: string;
  instructorPersonId?: string | undefined;
  towingPilotPersonId?: string | undefined;
  flightOperatorPersonId?: string | undefined;
  remarks?: string | undefined;
  numberOfAircraftReservations: number;
}

const mockLocationsPicker = [
  { id: LOCATION_BERN_ID, locationName: 'Bern-Belp', icaoCode: 'LSZB' },
  { id: LOCATION_THUN_ID, locationName: 'Thun', icaoCode: 'LSZW' },
];

const mockPersonsPicker = [
  { id: INSTRUCTOR_ID, firstname: 'Iris', lastname: 'Instructor', city: 'Bern' },
  { id: TOWPILOT_ID, firstname: 'Tom', lastname: 'Towpilot', city: 'Thun' },
  { id: FLIGHTOP_ID, firstname: 'Fred', lastname: 'Flightop', city: 'Bern' },
];

const seedDay: MockPlanningDay = {
  id: SEED_DAY_ID,
  operatingClubId: CLUB_A_ID,
  date: SEED_DAY_KEY,
  locationId: LOCATION_BERN_ID,
  instructorPersonId: INSTRUCTOR_ID,
  towingPilotPersonId: TOWPILOT_ID,
  flightOperatorPersonId: FLIGHTOP_ID,
  remarks: 'Seed planning day',
  numberOfAircraftReservations: 1,
};

function toDetail(d: MockPlanningDay) {
  return {
    id: d.id,
    operatingClubId: d.operatingClubId,
    planningDate: d.date,
    locationId: d.locationId,
    instructorPersonId: d.instructorPersonId,
    towingPilotPersonId: d.towingPilotPersonId,
    flightOperatorPersonId: d.flightOperatorPersonId,
    info: d.remarks,
    numberOfAircraftReservations: d.numberOfAircraftReservations,
    canUpdateRecord: true,
    canDeleteRecord: true,
  };
}

async function stubReferenceData(page: Page): Promise<void> {
  await page.route('**/api/v1/locations', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocationsPicker),
    }),
  );
  await page.route('**/api/v1/persons', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockPersonsPicker),
    }),
  );
  await page.route('**/api/v1/aircraft/picker', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '019e30c3-2c00-7001-8000-00000000a001',
          immatriculation: 'HB-3001',
          aircraftTypeId: '019e30c3-2c00-7001-8000-0000000000a0',
          isTowingAircraft: false,
        },
      ]),
    }),
  );
  await page.route('**/api/v1/aircraft-reservations/day/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    const matchesSeed = path.endsWith(`/${SEED_DAY_KEY}`);
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        matchesSeed
          ? [
              {
                id: SEED_DAY_RESERVATION_ID,
                aircraftId: '019e30c3-2c00-7001-8000-00000000a001',
                start: `${SEED_DAY_KEY}T10:00:00Z`,
                end: `${SEED_DAY_KEY}T11:00:00Z`,
                isAllDay: false,
                pilotPersonId: INSTRUCTOR_ID,
                locationId: LOCATION_BERN_ID,
              },
            ]
          : [],
      ),
    });
  });
}

interface PlanningWriteBody {
  planningDate: string;
  locationId: string;
  instructorPersonId?: string;
  towingPilotPersonId?: string;
  flightOperatorPersonId?: string;
  info?: string;
}

function bodyToDay(
  body: PlanningWriteBody,
): Omit<MockPlanningDay, 'id' | 'operatingClubId' | 'numberOfAircraftReservations'> {
  return {
    date: body.planningDate,
    locationId: body.locationId,
    instructorPersonId: body.instructorPersonId,
    towingPilotPersonId: body.towingPilotPersonId,
    flightOperatorPersonId: body.flightOperatorPersonId,
    remarks: body.info,
  };
}

function setupPlanningBackend(days: MockPlanningDay[]) {
  let nextId = 1000;
  const isDuplicate = (date: string, locationId: string, exceptId?: string): boolean =>
    days.some((d) => d.id !== exceptId && d.date === date && d.locationId === locationId);
  const futureDays = (): MockPlanningDay[] =>
    days.filter((d) => d.date >= dayKeyFromToday(0)).sort((a, b) => a.date.localeCompare(b.date));

  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/planning-days\/([0-9a-f-]{36})$/);
    const pageMatch = path.match(/^\/api\/v1\/planning-days\/page\/(\d+)\/(\d+)$/);

    if (method === 'GET' && path === '/api/v1/planning-days/overview/future') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(futureDays().map(toDetail)),
      });
      return;
    }

    if (method === 'POST' && pageMatch) {
      const start = Number(pageMatch[1]);
      const size = Number(pageMatch[2]);
      const future = futureDays();
      const items = future.slice(start, start + size).map(toDetail);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items,
          pageStart: start,
          pageSize: size,
          totalRows: future.length,
        }),
      });
      return;
    }

    if (method === 'GET' && idMatch) {
      const found = days.find((d) => d.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ? toDetail(found) : {}),
      });
      return;
    }

    if (method === 'POST' && path === '/api/v1/planning-days') {
      const body = bodyToDay(req.postDataJSON() as PlanningWriteBody);
      if (isDuplicate(body.date, body.locationId)) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ key: 'planning.day.duplicate' }),
        });
        return;
      }
      const created: MockPlanningDay = {
        ...body,
        operatingClubId: CLUB_A_ID,
        numberOfAircraftReservations: 0,
        id: `019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
      };
      days.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/planning-days/${created.id}` },
        body: JSON.stringify(toDetail(created)),
      });
      return;
    }

    if (method === 'PUT' && idMatch) {
      const body = bodyToDay(req.postDataJSON() as PlanningWriteBody);
      const idx = days.findIndex((d) => d.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      if (isDuplicate(body.date, body.locationId, idMatch[1]!)) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ key: 'planning.day.duplicate' }),
        });
        return;
      }
      const next: MockPlanningDay = { ...days[idx]!, ...body, id: idMatch[1]! };
      days[idx] = next;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(toDetail(next)),
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
  };
}

async function wirePlanning(page: Page, days: MockPlanningDay[]): Promise<void> {
  await stubReferenceData(page);
  await page.route('**/api/v1/planning-days**', setupPlanningBackend(days));
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

function planningRow(page: Page, id: string) {
  return page.getByTestId(`planning-row-${id}`);
}

function saveError(page: Page) {
  return page.getByTestId('planning-save-error').getByTestId('af-page-error');
}

async function fillCrew(page: Page): Promise<void> {
  await selectAfOption(page, 'planning-location-select', LOCATION_BERN_ID);
  await selectAfOption(page, 'planning-instructor-select', INSTRUCTOR_ID);
  await selectAfOption(page, 'planning-towpilot-select', TOWPILOT_ID);
  await selectAfOption(page, 'planning-flightop-select', FLIGHTOP_ID);
}

test.describe('J-6 planning days (mock-auth inner loop)', () => {
  test('list: future planning days render with crew + reservation count; weekend rows are flagged', async ({
    page,
  }) => {
    const weekendDay: MockPlanningDay = {
      ...seedDay,
      id: '019e30c3-2c00-7001-8000-000000000e02',
      date: WEEKEND_DAY_KEY,
      locationId: LOCATION_THUN_ID,
      numberOfAircraftReservations: 0,
    };
    await wirePlanning(page, [{ ...seedDay }, weekendDay]);

    await gotoDe(page, '/planning');
    await expect(page.locator('h1')).toContainText('Planung');
    await expect(page.getByTestId('planning-list')).toBeVisible();

    const row = planningRow(page, SEED_DAY_ID);
    await expect(row).toBeVisible();
    await expect(row).toContainText('Bern-Belp');
    await expect(row).toContainText('Iris Instructor');
    await expect(row).toContainText('Tom Towpilot');
    await expect(row).toContainText('Fred Flightop');
    await expect(row.getByTestId(`planning-reservations-count-${SEED_DAY_ID}`)).toContainText('1');

    await expect(planningRow(page, weekendDay.id)).toHaveAttribute('data-weekend', 'true');

    await page.getByTestId('planning-setup-button').click();
    await expect(page).toHaveURL('/planningsetup');

    await page.screenshot({ path: 'screenshots/planning/01-list.png', fullPage: true });
  });

  test.fixme('list: the paged read sends + receives the SPA envelope shape', async ({ page }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    const paged = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/planningdays\/page\/\d+\/\d+$/.test(new URL(r.url()).pathname) &&
        r.status() === 200,
    );
    await gotoDe(page, '/planning');
    const res = await paged;
    const body = (await res.json()) as {
      items: { id: string }[];
      pageStart: number;
      totalRows: number;
    };
    expect(body.pageStart).toBe(0);
    expect(body.totalRows).toBe(1);
    expect(body.items[0]!.id).toBe(SEED_DAY_ID);
  });

  test('create: a new planning day persists and appears in the future-days list', async ({
    page,
  }) => {
    await wirePlanning(page, []);

    await gotoDe(page, '/planning');
    await page.getByTestId('planning-new-button').locator('button').click();
    await expect(page).toHaveURL('/planning/new/edit');

    await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(5));
    await fillCrew(page);
    await page.getByTestId('planning-remarks').locator('input').fill('Created in the inner loop');

    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/planning-days' &&
        r.status() === 201,
    );
    await page.getByTestId('planning-save-button').click();
    const createdResp = await created;
    const id = new URL(createdResp.headers()['location']!, 'http://localhost').pathname
      .split('/')
      .pop()!;

    await expect(page).toHaveURL('/planning');
    await expect(planningRow(page, id)).toBeVisible();
    await page.screenshot({ path: 'screenshots/planning/02-created.png', fullPage: true });
  });

  test('edit: changing the crew assignments persists and reflects on reopen', async ({ page }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    await selectAfOption(page, 'planning-instructor-select', FLIGHTOP_ID);
    const updated = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname === `/api/v1/planning-days/${SEED_DAY_ID}` &&
        r.status() === 200,
    );
    await page.getByTestId('planning-save-button').click();
    await updated;
    await expect(page).toHaveURL('/planning');

    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);
    await expect(page.getByTestId('planning-instructor-select')).toContainText('Fred Flightop');
  });

  test('edit: the per-day reservations list renders inline with view/edit + new-reservation links', async ({
    page,
  }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);
    const panel = page.getByTestId('planning-reservations-panel');
    await expect(panel).toBeVisible();
    const resvRow = panel.getByTestId(`planning-reservation-${SEED_DAY_RESERVATION_ID}`);
    await expect(resvRow).toBeVisible();
    await expect(
      resvRow.getByTestId(`planning-reservation-edit-${SEED_DAY_RESERVATION_ID}`),
    ).toHaveAttribute(
      'href',
      `/reservations/${SEED_DAY_RESERVATION_ID}/edit?returnUrl=${encodeURIComponent(
        `/planning/${SEED_DAY_ID}/edit`,
      )}`,
    );
    await expect(panel.getByTestId('planning-new-reservation-button')).toBeVisible();
    await panel.getByTestId('planning-new-reservation-button').locator('button').click();
    await expect(page).toHaveURL(
      new RegExp(`/reservations/new\\?.*date=${SEED_DAY_KEY}.*locationId=${LOCATION_BERN_ID}`),
    );
  });

  test('create: the inline reservations list appears on date+location select, before save', async ({
    page,
  }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    await expect(page.getByTestId('planning-reservations-panel')).toHaveCount(0);

    await page.getByTestId('planning-date').locator('input').fill(SEED_DAY_KEY);
    await selectAfOption(page, 'planning-location-select', LOCATION_BERN_ID);

    const panel = page.getByTestId('planning-reservations-panel');
    await expect(panel).toBeVisible();
    const resvRow = panel.getByTestId(`planning-reservation-${SEED_DAY_RESERVATION_ID}`);
    await expect(resvRow).toBeVisible();
    await page.screenshot({
      path: 'screenshots/planning/04-reservations-on-date-select.png',
      fullPage: true,
    });

    await selectAfOption(page, 'planning-location-select', LOCATION_THUN_ID);
    await expect(panel.getByTestId('planning-reservations-empty')).toBeVisible();

    await panel.getByTestId('planning-new-reservation-button').locator('button').click();
    await expect(page).toHaveURL(
      new RegExp(`/reservations/new\\?.*date=${SEED_DAY_KEY}.*locationId=${LOCATION_THUN_ID}`),
    );
  });

  test('duplicate: a second day with the same (date, location) is rejected 409 inline', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(testInfo, /\b409\b/);
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, '/planning/new/edit');
    await page.getByTestId('planning-date').locator('input').fill(SEED_DAY_KEY);
    await fillCrew(page);

    const conflict = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/planning-days' &&
        r.status() === 409,
    );
    await page.getByTestId('planning-save-button').click();
    await conflict;

    await expect(saveError(page)).toBeVisible();
    await expect(page).toHaveURL(/\/planning\/new\/edit(\?|$)/);
    await page.screenshot({ path: 'screenshots/planning/03-duplicate-409.png', fullPage: true });
  });

  test('delete: deleting a planning day cascades its assignments and removes it from the list', async ({
    page,
  }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, '/planning');
    await expect(planningRow(page, SEED_DAY_ID)).toBeVisible();

    const deleted = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new URL(r.url()).pathname === `/api/v1/planning-days/${SEED_DAY_ID}` &&
        r.status() === 204,
    );
    await page.getByTestId(`planning-kebab-${SEED_DAY_ID}`).click();
    await page.getByTestId(`planning-delete-${SEED_DAY_ID}`).click();
    await page.getByTestId('planning-delete-confirm').click();
    await deleted;
    await expect(planningRow(page, SEED_DAY_ID)).toHaveCount(0);
  });
});
