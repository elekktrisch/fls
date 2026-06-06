import { expect, test, type Page, type Route } from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';

/**
 * Aircraft-reservation CRUD + conflict shape — J-5 INNER-LOOP spec (T-16,
 * thickened from the T-01 stub).
 *
 * Mock-auth fidelity: a mocked SYSTEM_ADMINISTRATOR principal (so the mutation
 * affordances render) + every `/api/v1/*` call intercepted via `page.route` — no
 * live backend. The FULL real legacy→migrate→Keycloak→Playwright chain is the
 * gate's job (`tests/real-idp/reservations-migration-parity.spec.ts`); this spec
 * pins the screen behaviour fast in the inner loop with REAL assertions on the
 * domain semantics the oracle requires.
 *
 * Reconciled from the T-01 stub against the SHIPPED screens (T-08 list, T-09
 * edit, T-10 scheduler) + the shipped wire contract:
 *   - reservation `id` is a PLAIN UUID (NOT `res-` prefixed) — the stub's
 *     `res-…` fixtures + `res-` id regex are corrected here.
 *   - picker sources are `/persons` + `/locations` (op `listPersons` /
 *     `listLocations`), NOT the non-existent `/persons/picker` /
 *     `/locations/picker` the stub mocked; aircraft is `/aircraft/picker`.
 *   - the list item field is `isAllDay` (not `isAllDayReservation`); the edit
 *     form drives `<af-select>` (helper `selectAfOption` by VALUE) + `<af-input>`
 *     date/time (`.locator('input')`), NOT the stub's bare `nz-select` + label.
 *   - cross-module labels (immatriculation / pilot / location) are decorated
 *     client-side from the picker label maps (ADR 0023, the store's `immatById`
 *     etc.) — the list row resolves the FK id to its picker label, so the mock
 *     pickers' ids MUST match the reservation rows' FK ids.
 *
 * Columns the list table commits to (legacy `reservations-table.html` parity):
 *   immatriculation, start, end, pilot, location, type, all-day.
 */

// ── ids (plain UUIDv7-shaped fixtures — NO `res-`/`ac-`/… external prefixes;
//    the new API + the SPA testids carry the raw UUID for reservations) ───────
const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';
const GLIDER_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';

// Operating club (the mock principal's tenant) + a DIFFERENT owning club, so
// the cross-tenant-open AC can reserve an aircraft the principal doesn't own.
const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const CLUB_B_ID = '019e30c3-2c00-7001-8000-000000000002';

// Aircraft: one same-tenant glider, one OTHER-tenant glider (cross-tenant AC).
// These ids are what the reservation rows carry as `aircraftId` AND what the
// `/aircraft/picker` payload carries as `id` — they MUST match so the list
// immatriculation cell + the scheduler lane resolve.
const AC_SAME = '019e30c3-2c00-7001-8000-00000000a001';
const AC_OTHER = '019e30c3-2c00-7001-8000-00000000a002';

const PILOT_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const LOCATION_ID = '019e30c3-2c00-7001-8000-00000000c001';

// Reservation types (loaded into the type dropdown).
const TYPE_FLIGHT_ID = '019e30c3-2c00-7001-8000-0000000000d1';
const TYPE_MAINT_ID = '019e30c3-2c00-7001-8000-0000000000d2';

const SEED_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000e01';

// ── mock shapes (new-API wire shape, camelCase fields) ──────────────────────
interface MockReservationType {
  id: string;
  name: string;
  active: boolean;
}

interface MockReservation {
  id: string;
  operatingClubId: string;
  aircraftId: string;
  pilotPersonId: string;
  // `| undefined` (not bare `?`) so the create/update merge below may assign an
  // explicitly-undefined value under exactOptionalPropertyTypes.
  secondCrewPersonId?: string | undefined;
  locationId: string;
  reservationTypeId?: string | undefined;
  reservationTypeName?: string | undefined;
  isAllDay: boolean;
  /** ISO-8601 instant; for all-day, start = end = day 00:00 (full-day span). */
  start: string;
  end: string;
  remarks?: string | undefined;
}

const mockReservationTypes: MockReservationType[] = [
  { id: TYPE_FLIGHT_ID, name: 'Flight', active: true },
  { id: TYPE_MAINT_ID, name: 'Maintenance', active: true },
];

const mockAircraftPicker = [
  {
    id: AC_SAME,
    immatriculation: 'HB-SAME',
    aircraftTypeId: GLIDER_TYPE_ID,
    isTowingAircraft: false,
  },
  {
    id: AC_OTHER,
    // Owned/managed by a DIFFERENT club — the cross-tenant-open AC reserves it.
    immatriculation: 'HB-OTHR',
    aircraftTypeId: GLIDER_TYPE_ID,
    isTowingAircraft: false,
  },
];

const mockPersonsPicker = [{ id: PILOT_ID, firstname: 'Anna', lastname: 'Pilot', city: 'Bern' }];

const mockLocationsPicker = [{ id: LOCATION_ID, locationName: 'Bern-Belp', icaoCode: 'LSZB' }];

// A pre-existing TIMED reservation on the same-tenant aircraft (10:00–11:00),
// the row every conflict/edit/delete AC probes against. 10:00 of a 24h day is
// 41.6̄% of the window — the scheduler placement assertion below pins exactly
// that offset (the T-10 `placeBlock` helper).
const seedReservation: MockReservation = {
  id: SEED_RESERVATION_ID,
  operatingClubId: CLUB_A_ID,
  aircraftId: AC_SAME,
  pilotPersonId: PILOT_ID,
  locationId: LOCATION_ID,
  reservationTypeId: TYPE_FLIGHT_ID,
  reservationTypeName: 'Flight',
  isAllDay: false,
  start: '2026-07-01T10:00:00Z',
  end: '2026-07-01T11:00:00Z',
};

// ── half-open overlap (the conflict rule the backend owns in T-03/T-04) ─────
// Same aircraft, `existing.start < new.end && new.start < existing.end`;
// soft-deleted rows excluded; self-excluded on update. Replicated thinly here
// only so the mock backend can answer 409 for the inner-loop flow — the REAL
// rule is asserted live in the real-idp spec.
function overlaps(a: MockReservation, b: MockReservation): boolean {
  if (a.aircraftId !== b.aircraftId) return false;
  // All-day collapses to the full-day span for the overlap test (T-03 aggregate).
  const span = (r: MockReservation): [string, string] =>
    r.isAllDay
      ? [`${r.start.slice(0, 10)}T00:00:00Z`, `${r.start.slice(0, 10)}T24:00:00Z`]
      : [r.start, r.end];
  const [aStart, aEnd] = span(a);
  const [bStart, bEnd] = span(b);
  return aStart < bEnd && bStart < aEnd;
}

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
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: `clb-${CLUB_A_ID}`,
          name: 'Operating Club A',
          slug: 'operating-club-a',
          countryId: CH_COUNTRY_ID,
          clubStateId: CLUB_STATE_ID,
        },
        {
          id: `clb-${CLUB_B_ID}`,
          name: 'Other Club B',
          slug: 'other-club-b',
          countryId: CH_COUNTRY_ID,
          clubStateId: CLUB_STATE_ID,
        },
      ]),
    }),
  );
  // Picker reference data the edit form's selects load + the list decorates
  // labels from. RECONCILED routes (T-01 mocked non-existent /picker variants):
  //   aircraft → /aircraft/picker (op listAircraftForPicker)
  //   persons  → /persons          (op listPersons — NOT /persons/picker)
  //   locations→ /locations        (op listLocations — NOT /locations/picker)
  await page.route('**/api/v1/aircraft/picker**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockAircraftPicker),
    }),
  );
  await page.route('**/api/v1/persons', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockPersonsPicker),
    }),
  );
  await page.route('**/api/v1/locations', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocationsPicker),
    }),
  );
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockReservationTypes),
    }),
  );
}

/** Project a stored reservation onto the list-item wire shape (T-06 envelope). */
function toListItem(r: MockReservation) {
  return {
    id: r.id,
    aircraftId: r.aircraftId,
    start: r.start,
    end: r.end,
    isAllDay: r.isAllDay,
    pilotPersonId: r.pilotPersonId,
    secondCrewPersonId: r.secondCrewPersonId,
    locationId: r.locationId,
    reservationTypeId: r.reservationTypeId,
    reservationTypeName: r.reservationTypeName,
    remarks: r.remarks,
  };
}

/** Project a stored reservation onto the detail wire shape (GET /{id}). */
function toDetail(r: MockReservation) {
  return {
    id: r.id,
    operatingClubId: r.operatingClubId,
    aircraftId: r.aircraftId,
    pilotPersonId: r.pilotPersonId,
    secondCrewPersonId: r.secondCrewPersonId,
    locationId: r.locationId,
    reservationTypeId: r.reservationTypeId,
    start: r.start,
    end: r.end,
    isAllDay: r.isAllDay,
    remarks: r.remarks,
  };
}

/**
 * Stub backend for the reservation resource. Holds a mutable list so a created
 * reservation appears in the next list/scheduler read, a conflicting create is
 * rejected 409, an end≤start timed create is rejected 422, and an edit/delete
 * frees the slot.
 *
 * Routes (new kebab-case REST):
 *   POST   /api/v1/aircraft-reservations/page/{start}/{size}  → SPA paged envelope
 *   GET    /api/v1/aircraft-reservations/{id}                 → detail
 *   POST   /api/v1/aircraft-reservations                      → create (201 | 409 | 422)
 *   PUT    /api/v1/aircraft-reservations/{id}                 → update (200 | 409 | 422)
 *   DELETE /api/v1/aircraft-reservations/{id}                 → soft-delete (204)
 */
function setupReservationBackend(reservations: MockReservation[]) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    // PLAIN-UUID id segment (T-01's `res-…` regex corrected).
    const idMatch = path.match(/^\/api\/v1\/aircraft-reservations\/([0-9a-f-]{36})$/);
    const pageMatch = path.match(/^\/api\/v1\/aircraft-reservations\/page\/(\d+)\/(\d+)$/);

    // Paged list — SPA envelope {items, pageStart, pageSize, totalRows} (camelCase).
    if (method === 'POST' && pageMatch) {
      const start = Number(pageMatch[1]);
      const size = Number(pageMatch[2]);
      const items = reservations.slice(start, start + size).map(toListItem);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items,
          pageStart: start,
          pageSize: size,
          totalRows: reservations.length,
        }),
      });
      return;
    }

    if (method === 'GET' && idMatch) {
      const found = reservations.find((r) => r.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ? toDetail(found) : {}),
      });
      return;
    }

    if (method === 'POST' && path === '/api/v1/aircraft-reservations') {
      const body = req.postDataJSON() as Omit<MockReservation, 'id' | 'operatingClubId'>;
      // end>start guard (timed) → 422.
      if (!body.isAllDay && body.end <= body.start) {
        await route.fulfill({
          status: 422,
          contentType: 'application/json',
          body: JSON.stringify({ key: 'aircraft.reservation.duration' }),
        });
        return;
      }
      const candidate = { ...body, id: '', operatingClubId: CLUB_A_ID } as MockReservation;
      if (reservations.some((r) => overlaps(r, candidate))) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ key: 'aircraft.reservation.overlap' }),
        });
        return;
      }
      const created: MockReservation = {
        ...body,
        operatingClubId: CLUB_A_ID,
        reservationTypeName: typeName(body.reservationTypeId),
        id: `019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
      };
      reservations.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/aircraft-reservations/${created.id}` },
        body: JSON.stringify(toDetail(created)),
      });
      return;
    }

    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as Omit<MockReservation, 'id' | 'operatingClubId'>;
      const idx = reservations.findIndex((r) => r.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const candidate = { ...body, id: idMatch[1]!, operatingClubId: CLUB_A_ID } as MockReservation;
      // Self-excluded on update: skip the row being edited in the overlap test.
      if (reservations.some((r) => r.id !== idMatch[1] && overlaps(r, candidate))) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ key: 'aircraft.reservation.overlap' }),
        });
        return;
      }
      const next: MockReservation = {
        ...reservations[idx]!,
        ...body,
        reservationTypeName: typeName(body.reservationTypeId),
        id: idMatch[1]!,
      };
      reservations[idx] = next;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(toDetail(next)),
      });
      return;
    }

    if (method === 'DELETE' && idMatch) {
      const idx = reservations.findIndex((r) => r.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      reservations.splice(idx, 1); // soft-delete: gone from subsequent reads.
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    await route.fallback();
  };
}

function typeName(id: string | undefined): string | undefined {
  return mockReservationTypes.find((t) => t.id === id)?.name;
}

async function wireReservations(page: Page, reservations: MockReservation[]): Promise<void> {
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft-reservations**', setupReservationBackend(reservations));
}

/** Fill the timed/all-day shared fields on the create/edit form. */
async function fillReservationCommon(page: Page, aircraftId: string): Promise<void> {
  await selectAfOption(page, 'reservation-aircraft-select', aircraftId);
  await selectAfOption(page, 'reservation-type-select', TYPE_FLIGHT_ID);
  await selectAfOption(page, 'reservation-pilot-select', PILOT_ID);
  await selectAfOption(page, 'reservation-location-select', LOCATION_ID);
}

/** The inline save-error alert (the `af-page-error` body renders only on error). */
function saveError(page: Page) {
  return page.getByTestId('reservation-save-error').getByTestId('af-page-error');
}

// ── inner-loop suite — drives the SHIPPED screens with full assertions ──────
test.describe('J-5 aircraft reservations (mock-auth inner loop)', () => {
  // ── AC: list renders the paged table with the seven columns ──────────────
  test('list: paged table renders the seeded reservation with all seven columns', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    await page.goto('/reservations');

    await expect(page.locator('h1')).toContainText('Reservationen');
    await expect(page.getByTestId('reservations-table')).toBeVisible();

    const row = page.getByTestId(`reservations-row-${SEED_RESERVATION_ID}`);
    await expect(row).toBeVisible();

    // Seven committed columns — REAL values (immat/pilot/location decorated
    // client-side from the picker label maps, ADR 0023; type from the row).
    await expect(page.getByTestId(`reservations-immat-${SEED_RESERVATION_ID}`)).toHaveText(
      'HB-SAME',
    );
    await expect(page.getByTestId(`reservations-start-${SEED_RESERVATION_ID}`)).toContainText(
      '01.07.2026 10:00',
    );
    await expect(page.getByTestId(`reservations-end-${SEED_RESERVATION_ID}`)).toContainText(
      '01.07.2026 11:00',
    );
    await expect(page.getByTestId(`reservations-pilot-${SEED_RESERVATION_ID}`)).toHaveText(
      'Anna Pilot',
    );
    await expect(page.getByTestId(`reservations-location-${SEED_RESERVATION_ID}`)).toHaveText(
      'Bern-Belp',
    );
    await expect(page.getByTestId(`reservations-type-${SEED_RESERVATION_ID}`)).toHaveText('Flight');
    // Timed row → the all-day cell shows "Zeitfenster" (timed), not "Ganztägig".
    await expect(page.getByTestId(`reservations-allday-${SEED_RESERVATION_ID}`)).toHaveText(
      'Zeitfenster',
    );

    await page.screenshot({
      path: 'screenshots/reservations/01-list-populated.png',
      fullPage: true,
    });
  });

  // ── AC[happy]: paged list envelope shape ─────────────────────────────────
  test('list: the paged read sends + receives the SPA envelope shape', async ({ page }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Capture the paged read so the envelope shape is asserted on the wire
    // (totalRows + pageStart + pageSize + items[]), not just the rendered rows.
    const paged = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/aircraft-reservations\/page\/\d+\/\d+$/.test(new URL(r.url()).pathname) &&
        r.status() === 200,
    );
    await page.goto('/reservations');
    const res = await paged;
    const body = (await res.json()) as {
      items: { id: string; aircraftId: string; isAllDay: boolean }[];
      pageStart: number;
      pageSize: number;
      totalRows: number;
    };
    expect(body.pageStart).toBe(0);
    expect(body.pageSize).toBe(20);
    expect(body.totalRows).toBe(1);
    expect(body.items).toHaveLength(1);
    expect(body.items[0]!.id).toBe(SEED_RESERVATION_ID);
    // The request body carries the {sorting:{start}} the store sends.
    const reqBody = res.request().postDataJSON() as { sorting?: { start?: string } };
    expect(reqBody.sorting?.start).toBe('asc');
  });

  // ── AC[happy]: create a TIMED reservation → row with all columns ─────────
  test('create: a timed reservation persists and appears in the list with all columns', async ({
    page,
  }) => {
    await wireReservations(page, []);

    await page.goto('/reservations');
    await page.getByTestId('reservations-new-button').locator('button').click();
    await expect(page).toHaveURL('/reservations/new');

    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-02');
    await page.getByTestId('reservation-start-time').locator('input').fill('14:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('15:00');

    // Wait on the 201 as the completion signal (the SPA navigates on bus-success).
    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 201,
    );
    await page.getByTestId('reservation-save-button').click();
    await created;

    await expect(page).toHaveURL('/reservations');
    const row = page.locator('[data-testid^="reservations-immat-"]').filter({ hasText: 'HB-SAME' });
    await expect(row).toBeVisible();
    // The created row carries the full column set (timed → "Zeitfenster").
    const rowId = (await page
      .locator('[data-testid^="reservations-row-"]')
      .filter({ hasText: 'HB-SAME' })
      .getAttribute('data-testid'))!.replace(/^reservations-row-/, '');
    await expect(page.getByTestId(`reservations-start-${rowId}`)).toContainText('02.07.2026 14:00');
    await expect(page.getByTestId(`reservations-allday-${rowId}`)).toHaveText('Zeitfenster');
    await page.screenshot({
      path: 'screenshots/reservations/02-timed-created.png',
      fullPage: true,
    });
  });

  // ── AC[happy]: ALL-DAY reservation renders as a full-day band ────────────
  test('create: an all-day reservation hides the time fields and renders as a full-day band', async ({
    page,
  }) => {
    await wireReservations(page, []);

    await page.goto('/reservations/new');

    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-03');
    // Toggling all-day hides the start/end time inputs (full-day span) so the
    // form's required-time validators don't gate the save.
    await page.getByTestId('reservation-allday-toggle').check();
    await expect(page.getByTestId('reservation-start-time')).toHaveCount(0);
    await expect(page.getByTestId('reservation-end-time')).toHaveCount(0);

    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 201,
    );
    await page.getByTestId('reservation-save-button').click();
    const createdRes = await created;
    // The create body collapses an all-day reservation to start=end=midnight
    // (T-09 `formToRequest`) + isAllDay true — the full-day-band contract.
    const sent = createdRes.request().postDataJSON() as {
      isAllDay: boolean;
      start: string;
      end: string;
    };
    expect(sent.isAllDay).toBe(true);
    expect(sent.start).toBe('2026-07-03T00:00:00Z');
    expect(sent.end).toBe('2026-07-03T00:00:00Z');

    await expect(page).toHaveURL('/reservations');
    const rowId = (await page
      .locator('[data-testid^="reservations-row-"]')
      .filter({ hasText: 'HB-SAME' })
      .getAttribute('data-testid'))!.replace(/^reservations-row-/, '');
    // The all-day cell renders the "Ganztägig" band marker (NOT "Zeitfenster").
    await expect(page.getByTestId(`reservations-allday-${rowId}`)).toHaveText('Ganztägig');

    // Full-day band on the SCHEDULER: an all-day block spans the whole lane.
    await page.goto('/reservation-scheduler');
    const block = page.getByTestId(`reservation-scheduler-block-${rowId}`);
    await expect(block).toBeVisible();
    await expect(block).toHaveCSS('width', /.+/);
    const width = await block.evaluate((el) => (el as HTMLElement).style.width);
    expect(width, 'an all-day reservation is a full-width band').toBe('100%');
    await page.screenshot({ path: 'screenshots/reservations/03-allday-band.png', fullPage: true });
  });

  // ── AC[key-error]: conflict→409 + edit-in-place does NOT self-conflict ───
  test('conflict: an overlapping create is rejected 409 inline; editing the existing row does NOT self-conflict', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Try to book the SAME aircraft 10:30–10:45 — overlaps the 10:00–11:00 seed.
    await page.goto('/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-01');
    await page.getByTestId('reservation-start-time').locator('input').fill('10:30');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:45');

    const conflict = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 409,
    );
    await page.getByTestId('reservation-save-button').click();
    await conflict;

    // Inline conflict error surfaces; the route does NOT navigate back to list.
    await expect(saveError(page)).toBeVisible();
    await expect(saveError(page)).toContainText('overlapping');
    await expect(page).toHaveURL('/reservations/new');
    await page.screenshot({ path: 'screenshots/reservations/04-conflict-409.png', fullPage: true });

    // Editing the EXISTING overlapping row (self-exclude) → no conflict, saves
    // and navigates back to the list (the row is excluded from its own probe).
    await page.goto(`/reservations/${SEED_RESERVATION_ID}/edit`);
    await expect(page.getByTestId('reservation-edit-form')).toBeVisible();
    await page.getByTestId('reservation-end-time').locator('input').fill('11:30');
    const updated = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname === `/api/v1/aircraft-reservations/${SEED_RESERVATION_ID}` &&
        r.status() === 200,
    );
    await page.getByTestId('reservation-save-button').click();
    await updated;
    await expect(page).toHaveURL('/reservations');
    await expect(saveError(page)).toHaveCount(0);
  });

  // ── AC[key-error]: timed end ≤ start → 422 inline ────────────────────────
  test('duration: a timed reservation with end ≤ start is rejected 422 inline', async ({
    page,
  }) => {
    await wireReservations(page, []);

    await page.goto('/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-05');
    await page.getByTestId('reservation-start-time').locator('input').fill('15:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('14:00');

    const rejected = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 422,
    );
    await page.getByTestId('reservation-save-button').click();
    await rejected;

    await expect(saveError(page)).toBeVisible();
    await expect(saveError(page)).toContainText('End must be after start');
    await expect(page).toHaveURL('/reservations/new');
  });

  // ── AC[happy]: edit moves time / delete frees the slot ───────────────────
  test('edit/delete: deleting a reservation frees the slot so a new overlapping create then succeeds', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Pre-flight: a 10:30–10:45 create overlaps the seed → 409 (proves the slot
    // is occupied before the delete frees it).
    await page.goto('/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-01');
    await page.getByTestId('reservation-start-time').locator('input').fill('10:30');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:45');
    const blocked = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 409,
    );
    await page.getByTestId('reservation-save-button').click();
    await blocked;
    await expect(saveError(page)).toBeVisible();

    // Delete the seed via the list kebab → confirm dialog.
    await page.goto('/reservations');
    page.once('dialog', (d) => d.accept());
    await page.getByTestId(`reservations-kebab-${SEED_RESERVATION_ID}`).click();
    await page.getByTestId(`reservations-delete-${SEED_RESERVATION_ID}`).click();
    await expect(page.getByTestId(`reservations-row-${SEED_RESERVATION_ID}`)).toHaveCount(0);

    // The freed 10:00–11:00 slot now ACCEPTS the same overlapping booking (201).
    await page.goto('/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-01');
    await page.getByTestId('reservation-start-time').locator('input').fill('10:30');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:45');
    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 201,
    );
    await page.getByTestId('reservation-save-button').click();
    await created;

    await expect(page).toHaveURL('/reservations');
    await expect(saveError(page)).toHaveCount(0);
  });

  // ── AC[edge]: cross-tenant aircraft reserves successfully (legacy-open) ──
  test('cross-tenant: an aircraft owned by a different club reserves successfully (no charter gate)', async ({
    page,
  }) => {
    await wireReservations(page, []);

    await page.goto('/reservations/new');
    // HB-OTHR is owned by CLUB_B (not the operating tenant) — legacy-open: the
    // picker offers it and the create succeeds (no tenant/charter rejection),
    // stamped with the operating club.
    await fillReservationCommon(page, AC_OTHER);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-04');
    await page.getByTestId('reservation-start-time').locator('input').fill('09:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:00');
    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 201,
    );
    await page.getByTestId('reservation-save-button').click();
    await created;

    await expect(page).toHaveURL('/reservations');
    await expect(saveError(page)).toHaveCount(0);
    await expect(
      page.locator('[data-testid^="reservations-immat-"]').filter({ hasText: 'HB-OTHR' }),
    ).toBeVisible();
  });

  // ── AC[happy]: scheduler shows the reservation in the right lane×time ─────
  test('scheduler: a reservation appears in its aircraft lane at the time-derived offset', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    await page.goto('/reservation-scheduler');

    await expect(page.getByTestId('reservation-scheduler')).toBeVisible();
    // The aircraft lane for the seed's aircraft exists + is labelled by immat.
    const lane = page.getByTestId(`reservation-scheduler-lane-${AC_SAME}`);
    await expect(lane).toBeVisible();
    await expect(lane).toContainText('HB-SAME');

    // The reservation block is rendered inside THAT lane (lane×placement).
    const block = lane.getByTestId(`reservation-scheduler-block-${SEED_RESERVATION_ID}`);
    await expect(block).toBeVisible();

    // Time-derived offset: 10:00 of a 24h window = 10/24 ≈ 41.6̄% left; the 1h
    // duration ≈ 1/24 ≈ 4.16% width (the T-10 `placeBlock` math). Assert the
    // inline-style left% lands in the expected band (the placement helper writes
    // `left: <pct>%`), so the block is at the right time, not just present.
    const left = await block.evaluate((el) => (el as HTMLElement).style.left);
    const leftPct = Number.parseFloat(left);
    expect(left, 'block carries a left% offset').toMatch(/%$/);
    expect(leftPct, '10:00 of a 24h window ≈ 41.6% from the lane left edge').toBeGreaterThan(40);
    expect(leftPct).toBeLessThan(43);
    await page.screenshot({
      path: 'screenshots/reservations/05-scheduler-lane-time.png',
      fullPage: true,
    });
  });
});
