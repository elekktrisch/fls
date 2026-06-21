import { type Page, type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';

/**
 * Planning-day CRUD — J-6 INNER-LOOP spec STUB (T-01).
 *
 * SPEC SKELETON ONLY. This task (J-6 T-01) commits the *screen shape* — the
 * `data-testid` selectors + the flow steps + THIN assertions — before the
 * backend (`ch.alpenflight.planning`) or the SPA `/planning` feature exist. The
 * page-driving cases are therefore `test.fixme` (parsed + typechecked, NOT run)
 * and will be un-fixme'd as T-07/T-08 land the list + edit screens and T-16
 * thickens the assertions from the behavior oracle. The mock backend + the route
 * wiring below are fully authored so un-fixme is a one-line-per-test flip, not a
 * rewrite. Mirrors the J-5 reservations inner-loop discipline
 * (`tests/reservations/reservations-crud.spec.ts`).
 *
 * Mock-auth fidelity (once un-fixme'd): the SYSTEM_ADMINISTRATOR +
 * CLUB_ADMINISTRATOR mock principal (so the new/edit/delete affordances render)
 * + every `/api/v1/*` call intercepted via `page.route` — no live backend. The
 * FULL real legacy→migrate→Keycloak→Playwright chain is the gate's job
 * (`tests/real-idp/planning-migration-parity.spec.ts`); this spec pins the
 * screen behaviour fast in the inner loop.
 *
 * ── SCREEN SHAPE (from J-6 "Spec must assert" + the behavior oracle) ─────────
 *   LIST `/planning` — a PAGED FUTURE-DAYS table. Columns: date + weekday,
 *     location, the 3 crew roles (instructor / tow-pilot / flight-operator),
 *     #reservations. Saturday/Sunday rows are visually flagged
 *     (`planning-row-weekend-<id>`). Row actions: new / edit / view / delete.
 *     A "Setup" button → `/planningsetup`. Paged via
 *     `POST /api/v1/planningdays/page/{start}/{size}` → `{items, …, totalRows}`.
 *   EDIT `/planning/:id/edit` (+ `/planning/new`) — date picker, location select,
 *     3 person pickers (Instructor / TowingPilot / FlightOperator), remarks;
 *     save / cancel / delete. An INLINE per-day reservations list (joins J-5's
 *     AircraftReservation) with view/edit links + a "new reservation" button.
 *     Create POSTs `/api/v1/planningdays`; update PUTs `/api/v1/planningdays/:id`.
 *   Duplicate (date, location) for the same club → 409 inline (V4
 *     `ux_pln_club_date_loc`, correcting legacy's no-dedup bug).
 *   Delete → `DELETE /api/v1/planningdays/:id`; cascade-deletes assignments;
 *     the day leaves the list.
 *
 * The setup-wizard screen (`/planningsetup`) is a SECOND view on the same
 * feature — its stub lives in the sibling `planning-setup-wizard.spec.ts`.
 */

// ── ids (plain UUIDv7-shaped fixtures — the new API + SPA testids carry the raw
//    UUID for planning days, mirroring J-5 reservations) ───────────────────────
const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';

const LOCATION_BERN_ID = '019e30c3-2c00-7001-8000-00000000c001';
const LOCATION_THUN_ID = '019e30c3-2c00-7001-8000-00000000c002';

// The 3 well-known role pickers map to per-club assignment-type rows by name
// (behavior oracle: segelflugleiter→FlightOperator, schlepppilot→TowingPilot,
// fluglehrer→Instructor). On the wire the planning-day DTO keeps 3 nullable
// person ids; these are the persons the pickers select.
const INSTRUCTOR_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const TOWPILOT_ID = '019e30c3-2c00-7001-8000-0000000000b2';
const FLIGHTOP_ID = '019e30c3-2c00-7001-8000-0000000000b3';

const SEED_DAY_ID = '019e30c3-2c00-7001-8000-000000000e01';
// A reservation on the seed day — rendered inline on the edit screen (J-5 join).
const SEED_DAY_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000f01';

/**
 * `YYYY-MM-DD` `days` days from local today. Planning days are FUTURE days
 * (`overview/future` = `planning_date >= today`), so the fixtures are dated
 * forward of today. Pure DATE (no tz shift on migrate / store — oracle).
 */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** The next Saturday from today (`YYYY-MM-DD`) — for the Sat/Sun weekend flag. */
function nextSaturdayKey(): string {
  const d = new Date();
  const delta = (6 - d.getDay() + 7) % 7 || 7; // 6 = Saturday; never today
  d.setDate(d.getDate() + delta);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const SEED_DAY_KEY = dayKeyFromToday(3);
const WEEKEND_DAY_KEY = nextSaturdayKey();

// ── mock shapes (new-API wire shape, camelCase fields) ──────────────────────
//
// T-08: the real resource (T-04) is kebab-case `/api/v1/planning-days` with
// real verbs + the `planningDate` / `info` / `canUpdateRecord` field names; the
// detail carries the 3 nullable typed person ids. This mock matches THAT wire
// contract (the T-01 stub predated the resource decision).
interface MockPlanningDay {
  id: string;
  operatingClubId: string;
  /** Pure DATE `YYYY-MM-DD` — no time, no tz (oracle: stores as DATE). */
  date: string;
  locationId: string;
  instructorPersonId?: string | undefined;
  towingPilotPersonId?: string | undefined;
  flightOperatorPersonId?: string | undefined;
  remarks?: string | undefined;
  /** Computed count (same club, reservation date == day, same location). */
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

// A pre-existing planning day (FUTURE, weekday) with full crew + one reservation.
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

/**
 * Project a planning day onto the detail/overview wire shape — the resource
 * (T-04) serves the SAME `PlanningDayDetail` projection for the future-list
 * (`overview/future`), the paged list, and `GET /{id}`. Field names match the
 * orval client: `planningDate`, `info`, `canUpdateRecord` / `canDeleteRecord`.
 */
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
  // Aircraft picker — decorates the inline per-day reservation rows with the
  // immatriculation (best-effort label, loaded on its own stream).
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
  // The inline per-day reservations panel reuses the J-5 read-side
  // (`GET /aircraft-reservations/day/{date}`); the edit page filters the result
  // to the day's location client-side. The seed day's date returns one row at
  // the seed location; any other date returns none.
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

/** A request body off the create/update wire (the orval `planningDate`/`info` shape). */
interface PlanningWriteBody {
  planningDate: string;
  locationId: string;
  instructorPersonId?: string;
  towingPilotPersonId?: string;
  flightOperatorPersonId?: string;
  info?: string;
}

/** Lift a write body onto the mutable mock-day shape (the spec keys on `date`/`remarks`). */
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

/**
 * Stub backend for the planning-day resource. Holds a mutable list so a created
 * day appears in the next future-list read, a duplicate (date, location) is
 * rejected 409, an update persists crew, and a delete drops the day.
 *
 * Routes — the real kebab-case REST resource (T-04, real verbs):
 *   GET    /api/v1/planning-days/overview/future  → PlanningDayDetail[]
 *   POST   /api/v1/planning-days/page/{start}/{size}  → SPA paged envelope
 *   GET    /api/v1/planning-days/{id}             → detail
 *   POST   /api/v1/planning-days                  → create (201 | 409)
 *   PUT    /api/v1/planning-days/{id}             → update (200 | 409)
 *   DELETE /api/v1/planning-days/{id}             → delete (204)
 */
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

    // Future-days list (overview/future) — the list page's primary read.
    if (method === 'GET' && path === '/api/v1/planning-days/overview/future') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(futureDays().map(toDetail)),
      });
      return;
    }

    // Paged future-days list — SPA envelope {items, pageStart, pageSize, totalRows}.
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
      // Self-excluded on the dup check (editing the same day keeps its date/loc).
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
      days.splice(idx, 1); // cascade-deletes assignments → gone from reads.
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

/**
 * Navigate, forcing the German cold-start locale (`?lang=de`) — German is the
 * product's primary market + the i18n source, matching the J-5 specs + the
 * gallery locale. Only the cold-start `page.goto` needs it (web/CLAUDE.md §8b).
 */
async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

/** A planning-day row on the list, by id. */
function planningRow(page: Page, id: string) {
  return page.getByTestId(`planning-row-${id}`);
}

/** The inline save-error alert on the edit form. */
function saveError(page: Page) {
  return page.getByTestId('planning-save-error').getByTestId('af-page-error');
}

/** Fill the shared crew + location fields on the create/edit form. */
async function fillCrew(page: Page): Promise<void> {
  await selectAfOption(page, 'planning-location-select', LOCATION_BERN_ID);
  await selectAfOption(page, 'planning-instructor-select', INSTRUCTOR_ID);
  await selectAfOption(page, 'planning-towpilot-select', TOWPILOT_ID);
  await selectAfOption(page, 'planning-flightop-select', FLIGHTOP_ID);
}

// ── inner-loop suite — drives the /planning screens ──────────────────────────
// T-07 landed the list, T-08 the create/edit/view page, T-09 the setup wizard +
// `/planningsetup` route. The edit + create + delete + 409-inline +
// inline-reservations + list-render-with-Setup-button cases are un-fixme'd here
// (they drive the real kebab-case resource via `page.route` mocks). The
// page-POST envelope case stays fixme: the list page reads `overview/future`,
// not the page POST — un-fixme at T-16.
test.describe('J-6 planning days (mock-auth inner loop)', () => {
  // ── AC: the future-days list renders the seed day with crew + #reservations,
  //    a weekend row is flagged, and the Setup button routes to the wizard ─────
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

    // The seed day's row carries its date + location + the 3 crew labels +
    // the computed reservation count.
    const row = planningRow(page, SEED_DAY_ID);
    await expect(row).toBeVisible();
    await expect(row).toContainText('Bern-Belp');
    await expect(row).toContainText('Iris Instructor');
    await expect(row).toContainText('Tom Towpilot');
    await expect(row).toContainText('Fred Flightop');
    await expect(row.getByTestId(`planning-reservations-count-${SEED_DAY_ID}`)).toContainText('1');

    // A Saturday/Sunday row is visually flagged (oracle :40-41 parity).
    await expect(planningRow(page, weekendDay.id)).toHaveAttribute('data-weekend', 'true');

    // The Setup button routes to the wizard.
    await page.getByTestId('planning-setup-button').click();
    await expect(page).toHaveURL('/planningsetup');

    await page.screenshot({ path: 'screenshots/planning/01-list.png', fullPage: true });
  });

  // ── AC[happy]: the paged read sends + receives the SPA envelope shape ───────
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

  // ── AC[happy]: create a planning day → it appears in the list ───────────────
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

  // ── AC[happy]: edit a day's crew → it persists on reopen ────────────────────
  test('edit: changing the crew assignments persists and reflects on reopen', async ({ page }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    // Reassign the instructor to the flight-operator person.
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

    // Reopen — the change persisted.
    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);
    await expect(page.getByTestId('planning-instructor-select')).toContainText('Fred Flightop');
  });

  // ── AC[happy]: the edit screen lists that day's reservations inline (J-5 join)
  test('edit: the per-day reservations list renders inline with view/edit + new-reservation links', async ({
    page,
  }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);
    // The inline reservations panel loads from the J-5 join endpoint.
    const panel = page.getByTestId('planning-reservations-panel');
    await expect(panel).toBeVisible();
    const resvRow = panel.getByTestId(`planning-reservation-${SEED_DAY_RESERVATION_ID}`);
    await expect(resvRow).toBeVisible();
    // Each reservation links to J-5's reservation editor, carrying a
    // returnUrl=/planning/:id/edit so the editor's Cancel returns here (J-6b
    // T-10). The href is the editor path + the encoded returnUrl query param.
    await expect(
      resvRow.getByTestId(`planning-reservation-edit-${SEED_DAY_RESERVATION_ID}`),
    ).toHaveAttribute(
      'href',
      `/reservations/${SEED_DAY_RESERVATION_ID}/edit?returnUrl=${encodeURIComponent(
        `/planning/${SEED_DAY_ID}/edit`,
      )}`,
    );
    // "New reservation" navigates to J-5's create form pre-seeding the day's
    // date + location as query params (the J-5 edit page reads them — legacy
    // `PlanningDayEditController.js:128-132` parity).
    await expect(panel.getByTestId('planning-new-reservation-button')).toBeVisible();
    await panel.getByTestId('planning-new-reservation-button').locator('button').click();
    await expect(page).toHaveURL(
      new RegExp(`/reservations/new\\?.*date=${SEED_DAY_KEY}.*locationId=${LOCATION_BERN_ID}`),
    );
  });

  // ── AC[happy]: the inline reservations panel keys on date+location (T-08c) —
  //    it renders on a NEW day the moment date+location are picked, no save ─────
  test('create: the inline reservations list appears on date+location select, before save', async ({
    page,
  }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    // On a blank create form (no date, no location) the panel is hidden — it
    // keys on date+location, NOT a saved day id (the deliberate improvement over
    // legacy's saved-day gate).
    await expect(page.getByTestId('planning-reservations-panel')).toHaveCount(0);

    // Pick the SEED DAY's date + location (a NEW day, never saved). The panel
    // appears and loads that date+location's reservations from the J-5 join —
    // surfacing the seed day's reservation even though THIS day is unsaved.
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

    // Switch the location to one with no reservations on that date → the panel
    // re-fetches reactively (keyed on date+location) and shows the empty state.
    await selectAfOption(page, 'planning-location-select', LOCATION_THUN_ID);
    await expect(panel.getByTestId('planning-reservations-empty')).toBeVisible();

    // "New reservation" pre-seeds J-5's create form with the picked date +
    // location — works in create mode (reads the live form, not a saved detail).
    await panel.getByTestId('planning-new-reservation-button').locator('button').click();
    await expect(page).toHaveURL(
      new RegExp(`/reservations/new\\?.*date=${SEED_DAY_KEY}.*locationId=${LOCATION_THUN_ID}`),
    );
  });

  // ── AC[key-error]: a duplicate (date, location) is rejected 409 inline ───────
  test('duplicate: a second day with the same (date, location) is rejected 409 inline', async ({
    page,
  }, testInfo) => {
    // The duplicate-day POST is deliberately rejected; the browser logs it.
    allowConsoleErrors(testInfo, /\b409\b/);
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, '/planning/new/edit');
    // Same date + location as the seed day → V4 ux_pln_club_date_loc → 409.
    await page.getByTestId('planning-date').locator('input').fill(SEED_DAY_KEY);
    await fillCrew(page); // location defaults/selects to Bern, same as the seed

    const conflict = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/planning-days' &&
        r.status() === 409,
    );
    await page.getByTestId('planning-save-button').click();
    await conflict;

    await expect(saveError(page)).toBeVisible();
    // Stayed on the create form (no nav on error); tolerate the cold-start
    // `?lang=de` query the gotoDe helper leaves on the first nav (J-5 T-46).
    await expect(page).toHaveURL(/\/planning\/new\/edit(\?|$)/);
    await page.screenshot({ path: 'screenshots/planning/03-duplicate-409.png', fullPage: true });
  });

  // ── AC[key-error]: delete a day → cascade + it leaves the list ──────────────
  test('delete: deleting a planning day cascades its assignments and removes it from the list', async ({
    page,
  }) => {
    await wirePlanning(page, [{ ...seedDay }]);

    await gotoDe(page, '/planning');
    await expect(planningRow(page, SEED_DAY_ID)).toBeVisible();

    // Delete via the row's kebab → Delete → confirm dialog; the day leaves the
    // list. The kebab menu renders into a CDK-overlay portal, so open it first,
    // then the `planning-delete-<id>` item is page-level (not row-nested).
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
