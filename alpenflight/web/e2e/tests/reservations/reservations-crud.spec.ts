import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * Aircraft-reservation CRUD + conflict shape — J-5 SPEC STUB (T-01).
 *
 * This file commits the SCREEN DESIGN early: the route structure, the
 * `data-testid` selectors the list / edit / scheduler screens must expose, and
 * the flow skeleton each AC exercises. Assertions are deliberately THIN — they
 * pin the shape (a row/cell/error/lane is present, a route is reached) but do
 * NOT yet assert the real domain semantics. The full real assertions
 * (conflict→409 + self-exclude, all-day full-day band, cross-tenant-open
 * success, paged envelope shape, scheduler lane×time placement) land in T-16,
 * driven from the legacy-oracle read. Do NOT thicken here.
 *
 * The whole describe block is `test.describe.skip` because the screens
 * (T-08 list+store, T-09 edit page, T-10 scheduler) do not exist yet — the stub
 * must PARSE + TYPECHECK and define the contract T-08/T-09/T-10 build to, but it
 * cannot pass until those tasks land. T-16 removes the `.skip` and thickens.
 *
 * Booted under the `mock-auth` Angular configuration; the principal is a mocked
 * SYSTEM_ADMINISTRATOR so the mutation affordances render. All `/api/v1/*`
 * calls are intercepted via `page.route` — no live backend (inner-loop
 * fidelity; the real legacy→migrate→Keycloak→Playwright chain is the gate's
 * job, not this stub's).
 *
 * Wire-path note (ADR 0022): the spec drives the NEW kebab-case REST resource
 * `/api/v1/aircraft-reservations` (+ `/aircraft-reservation-types`), NOT the
 * legacy `aircraftreservations` URL shape or the `X-HTTP-Method-Override: PUT`
 * verb tunnel — observable CRUD behavior is the parity claim, not the legacy
 * transport (journey assumption #2). The paged list keeps the SPA page
 * semantics at `POST /api/v1/aircraft-reservations/page/{start}/{size}`.
 *
 * Columns the list table commits to (legacy `reservations-table.html` parity):
 *   immatriculation, start, end, pilot, location, type, all-day.
 *
 * Coverage skeleton (one test per AC):
 *   - list renders the paged table with the seven columns;
 *   - create a TIMED reservation → appears in the list;
 *   - create an ALL-DAY reservation → renders as a full-day band;
 *   - conflict→409: a 2nd overlapping reservation on the SAME aircraft is
 *     rejected inline; editing the existing one does NOT self-conflict;
 *   - edit moves time / delete frees the slot (a new overlapping one succeeds);
 *   - cross-tenant aircraft reserves successfully (legacy-open — no charter gate);
 *   - `/reservation-scheduler` shows the reservation in the right aircraft lane
 *     at the right time.
 */

// ── ids (stable UUIDv7-shaped fixtures, mirroring the aircraft spec) ─────────
const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';
const GLIDER_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';

// Operating club (the mock principal's tenant) + a DIFFERENT owning club, so
// the cross-tenant-open AC can reserve an aircraft the principal doesn't own.
const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';
const CLUB_B_ID = 'clb-019e30c3-2c00-7001-8000-000000000002';

// Aircraft: one same-tenant glider, one OTHER-tenant glider (cross-tenant AC).
const AC_SAME = 'ac-019e30c3-2c00-7001-8000-00000000a001';
const AC_OTHER = 'ac-019e30c3-2c00-7001-8000-00000000a002';

const PILOT_ID = 'pn-019e30c3-2c00-7001-8000-0000000000p1';
const LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-00000000l001';

// Reservation types (loaded into the type dropdown).
const TYPE_FLIGHT_ID = 'rt-019e30c3-2c00-7001-8000-0000000000t1';
const TYPE_MAINT_ID = 'rt-019e30c3-2c00-7001-8000-0000000000t2';

const SEED_RESERVATION_ID = 'res-019e30c3-2c00-7001-8000-000000000001';

// ── mock shapes (new-API wire shape, kebab-case + camelCase fields) ─────────
interface MockReservationType {
  id: string;
  code: string;
  name: string;
}

interface MockReservation {
  id: string;
  tenantClubId: string;
  aircraftId: string;
  immatriculation: string;
  pilotPersonId: string;
  pilotName: string;
  secondCrewPersonId?: string;
  locationId: string;
  locationName: string;
  reservationTypeId: string;
  reservationTypeName: string;
  isAllDayReservation: boolean;
  /** ISO-8601 instant; for all-day, start = day 00:00, end = next day 00:00. */
  start: string;
  end: string;
  remarks?: string;
}

const mockReservationTypes: MockReservationType[] = [
  { id: TYPE_FLIGHT_ID, code: 'FLIGHT', name: 'Flight' },
  { id: TYPE_MAINT_ID, code: 'MAINTENANCE', name: 'Maintenance' },
];

const mockAircraftPicker = [
  {
    id: AC_SAME,
    ownerClubId: CLUB_A_ID,
    immatriculation: 'HB-SAME',
    aircraftTypeId: GLIDER_TYPE_ID,
    isTowingAircraft: false,
  },
  {
    id: AC_OTHER,
    // Owned/managed by a DIFFERENT club — the cross-tenant-open AC reserves it.
    ownerClubId: CLUB_B_ID,
    immatriculation: 'HB-OTHR',
    aircraftTypeId: GLIDER_TYPE_ID,
    isTowingAircraft: false,
  },
];

const mockPersonsPicker = [{ id: PILOT_ID, firstname: 'Anna', lastname: 'Pilot', city: 'Bern' }];

const mockLocationsPicker = [{ id: LOCATION_ID, locationName: 'Bern-Belp', icaoCode: 'LSZB' }];

// A pre-existing TIMED reservation on the same-tenant aircraft (10:00–11:00),
// the row every conflict/edit/delete AC probes against.
const seedReservation: MockReservation = {
  id: SEED_RESERVATION_ID,
  tenantClubId: CLUB_A_ID,
  aircraftId: AC_SAME,
  immatriculation: 'HB-SAME',
  pilotPersonId: PILOT_ID,
  pilotName: 'Anna Pilot',
  locationId: LOCATION_ID,
  locationName: 'Bern-Belp',
  reservationTypeId: TYPE_FLIGHT_ID,
  reservationTypeName: 'Flight',
  isAllDayReservation: false,
  start: '2026-07-01T10:00:00Z',
  end: '2026-07-01T11:00:00Z',
};

// ── half-open overlap (the conflict rule the backend owns in T-03/T-04) ─────
// Same aircraft, `existing.start < new.end && new.start < existing.end`;
// soft-deleted rows excluded; self-excluded on update. Replicated thinly here
// only so the mock backend can answer 409 for the stub flow.
function overlaps(a: MockReservation, b: MockReservation): boolean {
  if (a.aircraftId !== b.aircraftId) return false;
  return a.start < b.end && b.start < a.end;
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
          id: CLUB_A_ID,
          name: 'Operating Club A',
          slug: 'operating-club-a',
          countryId: CH_COUNTRY_ID,
          clubStateId: CLUB_STATE_ID,
        },
        {
          id: CLUB_B_ID,
          name: 'Other Club B',
          slug: 'other-club-b',
          countryId: CH_COUNTRY_ID,
          clubStateId: CLUB_STATE_ID,
        },
      ]),
    }),
  );
  // Picker reference data the edit form's selects load.
  await page.route('**/api/v1/aircraft/picker**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockAircraftPicker),
    }),
  );
  await page.route('**/api/v1/persons/picker**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockPersonsPicker),
    }),
  );
  await page.route('**/api/v1/locations/picker**', (route) =>
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

/**
 * Stub backend for the reservation resource. Holds a mutable list so a created
 * reservation appears in the next list/scheduler read, a conflicting create is
 * rejected 409, and an edit/delete frees the slot.
 *
 * Routes (new kebab-case REST):
 *   GET    /api/v1/aircraft-reservations                      → list (array)
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
    const idMatch = path.match(/^\/api\/v1\/aircraft-reservations\/(res-[^/]+)$/);
    const pageMatch = path.match(/^\/api\/v1\/aircraft-reservations\/page\/(\d+)\/(\d+)$/);

    // Paged list — SPA envelope {Items, PageStart, PageSize, TotalRows}.
    if (method === 'POST' && pageMatch) {
      const start = Number(pageMatch[1]);
      const size = Number(pageMatch[2]);
      const items = reservations.slice(start, start + size);
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

    if (method === 'GET' && path === '/api/v1/aircraft-reservations') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(reservations),
      });
      return;
    }

    if (method === 'GET' && idMatch) {
      const found = reservations.find((r) => r.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }

    if (method === 'POST' && path === '/api/v1/aircraft-reservations') {
      const body = req.postDataJSON() as Omit<MockReservation, 'id'>;
      // end>start guard (timed) → 422.
      if (!body.isAllDayReservation && body.end <= body.start) {
        await route.fulfill({
          status: 422,
          contentType: 'application/json',
          body: JSON.stringify({ key: 'aircraft.reservation.duration' }),
        });
        return;
      }
      const candidate = { ...body, id: '' } as MockReservation;
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
        id: `res-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
      };
      reservations.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/aircraft-reservations/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }

    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as Omit<MockReservation, 'id'>;
      const idx = reservations.findIndex((r) => r.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const candidate = { ...body, id: idMatch[1]! } as MockReservation;
      // Self-excluded on update: skip the row being edited in the overlap test.
      if (reservations.some((r) => r.id !== idMatch[1] && overlaps(r, candidate))) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ key: 'aircraft.reservation.overlap' }),
        });
        return;
      }
      reservations[idx] = { ...reservations[idx]!, ...body, id: idMatch[1]! };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(reservations[idx]),
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

async function wireReservations(page: Page, reservations: MockReservation[]): Promise<void> {
  await stubReferenceData(page);
  await page.route('**/api/v1/aircraft-reservations**', setupReservationBackend(reservations));
}

// All screens below are unbuilt (T-08/T-09/T-10). The stub pins the contract;
// T-16 drops the `.skip` and thickens the assertions to the real semantics.
test.describe.skip('J-5 aircraft reservations (stub — screen shape, thin assertions)', () => {
  // ── AC: list renders the paged table with the seven columns ──────────────
  test('list: paged table renders the seeded reservation with all columns', async ({ page }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    await page.goto('/reservations');

    await expect(page.locator('h1')).toContainText('Reservations');
    await expect(page.getByTestId('reservations-table')).toBeVisible();

    const row = page.getByTestId(`reservations-row-${SEED_RESERVATION_ID}`);
    await expect(row).toBeVisible();

    // Seven committed columns (legacy reservations-table.html parity).
    // THIN: cell present, not value-exact — T-16 asserts the rendered values.
    await expect(page.getByTestId(`reservations-immat-${SEED_RESERVATION_ID}`)).toBeVisible();
    await expect(page.getByTestId(`reservations-start-${SEED_RESERVATION_ID}`)).toBeVisible();
    await expect(page.getByTestId(`reservations-end-${SEED_RESERVATION_ID}`)).toBeVisible();
    await expect(page.getByTestId(`reservations-pilot-${SEED_RESERVATION_ID}`)).toBeVisible();
    await expect(page.getByTestId(`reservations-location-${SEED_RESERVATION_ID}`)).toBeVisible();
    await expect(page.getByTestId(`reservations-type-${SEED_RESERVATION_ID}`)).toBeVisible();
    await expect(page.getByTestId(`reservations-allday-${SEED_RESERVATION_ID}`)).toBeVisible();

    // Paged-list affordance present (SPA paged envelope feeds it).
    await expect(page.getByTestId('reservations-pagination')).toBeVisible();
  });

  // ── AC[happy]: create a TIMED reservation ────────────────────────────────
  test('create: a timed reservation persists and appears in the list', async ({ page }) => {
    await wireReservations(page, []);

    await page.goto('/reservations');
    await page.getByTestId('reservations-new-button').locator('button').click();
    await expect(page).toHaveURL('/reservations/new');

    // Fill the timed-reservation form. THIN — fields present + fillable; T-16
    // asserts the round-tripped values + the 201 body.
    await selectReservationField(page, 'reservation-aircraft-select', 'HB-SAME');
    await selectReservationField(page, 'reservation-pilot-select', 'Anna Pilot');
    await selectReservationField(page, 'reservation-location-select', 'Bern-Belp');
    await selectReservationField(page, 'reservation-type-select', 'Flight');
    await page.getByTestId('reservation-date').locator('input').first().fill('2026-07-02');
    await page.getByTestId('reservation-start-time').locator('input').fill('14:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('15:00');
    await page.getByTestId('reservation-save-button').click();

    await expect(page).toHaveURL('/reservations');
    await expect(
      page.locator('[data-testid^="reservations-row-"]').filter({ hasText: 'HB-SAME' }),
    ).toBeVisible();
  });

  // ── AC[happy]: ALL-DAY reservation renders as a full-day band ────────────
  test('create: an all-day reservation renders as a full-day band', async ({ page }) => {
    await wireReservations(page, []);

    await page.goto('/reservations/new');

    await selectReservationField(page, 'reservation-aircraft-select', 'HB-SAME');
    await selectReservationField(page, 'reservation-pilot-select', 'Anna Pilot');
    await selectReservationField(page, 'reservation-location-select', 'Bern-Belp');
    await selectReservationField(page, 'reservation-type-select', 'Flight');
    await page.getByTestId('reservation-date').locator('input').first().fill('2026-07-03');
    // Toggling all-day hides the start/end time inputs (full-day span).
    await page.getByTestId('reservation-allday-toggle').click();
    await expect(page.getByTestId('reservation-start-time')).toHaveCount(0);
    await expect(page.getByTestId('reservation-end-time')).toHaveCount(0);
    await page.getByTestId('reservation-save-button').click();

    await expect(page).toHaveURL('/reservations');
    const row = page.locator('[data-testid^="reservations-row-"]').filter({ hasText: 'HB-SAME' });
    await expect(row).toBeVisible();
    // The all-day cell renders the full-day band marker (T-16: assert label).
    await expect(row.getByTestId(/^reservations-allday-/)).toBeVisible();
  });

  // ── AC[key-error]: conflict→409 + edit-in-place does NOT self-conflict ───
  test('conflict: an overlapping reservation is rejected 409; editing the existing one does not self-conflict', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Try to book the SAME aircraft 10:30–10:45 — overlaps the 10:00–11:00 seed.
    await page.goto('/reservations/new');
    await selectReservationField(page, 'reservation-aircraft-select', 'HB-SAME');
    await selectReservationField(page, 'reservation-pilot-select', 'Anna Pilot');
    await selectReservationField(page, 'reservation-location-select', 'Bern-Belp');
    await selectReservationField(page, 'reservation-type-select', 'Flight');
    await page.getByTestId('reservation-date').locator('input').first().fill('2026-07-01');
    await page.getByTestId('reservation-start-time').locator('input').fill('10:30');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:45');
    await page.getByTestId('reservation-save-button').click();

    // Inline conflict error surfaces; the route does NOT navigate back to list.
    await expect(page.getByTestId('reservation-save-error')).toBeVisible();
    await expect(page).toHaveURL('/reservations/new');

    // Editing the EXISTING overlapping row (self-exclude) → no conflict, saves.
    await page.goto(`/reservations/${SEED_RESERVATION_ID}/edit`);
    await page.getByTestId('reservation-end-time').locator('input').fill('11:30');
    await page.getByTestId('reservation-save-button').click();
    await expect(page).toHaveURL('/reservations');
    await expect(page.getByTestId('reservation-save-error')).toHaveCount(0);
  });

  // ── AC[happy]: edit moves time / delete frees the slot ───────────────────
  test('edit/delete: deleting a reservation frees the slot so a new overlapping one succeeds', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Delete the seed via the list kebab.
    await page.goto('/reservations');
    page.once('dialog', (d) => d.accept());
    await page.getByTestId(`reservations-kebab-${SEED_RESERVATION_ID}`).click();
    await page.getByTestId(`reservations-delete-${SEED_RESERVATION_ID}`).click();
    await expect(page.getByTestId(`reservations-row-${SEED_RESERVATION_ID}`)).toHaveCount(0);

    // The freed 10:00–11:00 slot now accepts a new overlapping booking.
    await page.goto('/reservations/new');
    await selectReservationField(page, 'reservation-aircraft-select', 'HB-SAME');
    await selectReservationField(page, 'reservation-pilot-select', 'Anna Pilot');
    await selectReservationField(page, 'reservation-location-select', 'Bern-Belp');
    await selectReservationField(page, 'reservation-type-select', 'Flight');
    await page.getByTestId('reservation-date').locator('input').first().fill('2026-07-01');
    await page.getByTestId('reservation-start-time').locator('input').fill('10:30');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:45');
    await page.getByTestId('reservation-save-button').click();

    await expect(page).toHaveURL('/reservations');
    await expect(page.getByTestId('reservation-save-error')).toHaveCount(0);
  });

  // ── AC[edge]: cross-tenant aircraft reserves successfully (legacy-open) ──
  test('cross-tenant: an aircraft owned by a different club reserves successfully (no charter gate)', async ({
    page,
  }) => {
    await wireReservations(page, []);

    await page.goto('/reservations/new');
    // HB-OTHR is owned by CLUB_B (not the operating tenant) — legacy-open: the
    // picker offers it and the reservation succeeds, stamped with the operating
    // club. THIN: the create round-trips (no tenant/charter rejection).
    await selectReservationField(page, 'reservation-aircraft-select', 'HB-OTHR');
    await selectReservationField(page, 'reservation-pilot-select', 'Anna Pilot');
    await selectReservationField(page, 'reservation-location-select', 'Bern-Belp');
    await selectReservationField(page, 'reservation-type-select', 'Flight');
    await page.getByTestId('reservation-date').locator('input').first().fill('2026-07-04');
    await page.getByTestId('reservation-start-time').locator('input').fill('09:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:00');
    await page.getByTestId('reservation-save-button').click();

    await expect(page).toHaveURL('/reservations');
    await expect(page.getByTestId('reservation-save-error')).toHaveCount(0);
    await expect(
      page.locator('[data-testid^="reservations-row-"]').filter({ hasText: 'HB-OTHR' }),
    ).toBeVisible();
  });

  // ── AC[happy]: scheduler shows the reservation in the right lane×time ─────
  test('scheduler: a reservation appears in its aircraft lane at the right time', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    await page.goto('/reservation-scheduler');

    await expect(page.getByTestId('reservation-scheduler')).toBeVisible();
    // The aircraft lane for the seed's aircraft exists.
    const lane = page.getByTestId(`reservation-scheduler-lane-${AC_SAME}`);
    await expect(lane).toBeVisible();
    // The reservation block is rendered inside that lane (lane×placement).
    // THIN: present + nested in the correct lane; T-16 asserts the time offset.
    const block = page.getByTestId(`reservation-scheduler-block-${SEED_RESERVATION_ID}`);
    await expect(block).toBeVisible();
    await expect(
      lane.getByTestId(`reservation-scheduler-block-${SEED_RESERVATION_ID}`),
    ).toBeVisible();
  });
});

/**
 * `<af-select>`-backed reservation field picker. Local thin wrapper over the
 * `data-testid` trigger + option-by-label click that the edit form's selects
 * will expose (T-09 builds the form to these test-ids). Kept inline in the stub
 * so the contract is co-located; T-16 may promote to `_helpers/` once the real
 * select markup is settled. THIN: opens the dropdown and clicks the option by
 * visible text — value-exactness is a T-16 concern.
 */
async function selectReservationField(
  page: Page,
  selectTestId: string,
  optionLabel: string,
): Promise<void> {
  await page.getByTestId(selectTestId).locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: optionLabel }).first().click();
}
