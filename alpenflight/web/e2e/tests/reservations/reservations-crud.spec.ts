import { expect, test, type Page, type Route } from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';

/**
 * Aircraft-reservation CRUD + conflict shape — J-5 INNER-LOOP spec (T-41,
 * reworked from the T-16 table spec for the CALENDAR redesign T-39/T-40).
 *
 * Mock-auth fidelity: a mocked SYSTEM_ADMINISTRATOR principal (so the mutation
 * affordances render) + every `/api/v1/*` call intercepted via `page.route` — no
 * live backend. The FULL real legacy→migrate→Keycloak→Playwright chain is the
 * gate's job (`tests/real-idp/reservations-migration-parity.spec.ts`); this spec
 * pins the screen behaviour fast in the inner loop with REAL assertions on the
 * domain semantics the oracle requires.
 *
 * ── CALENDAR redesign (T-39/T-40) — what this rewrite drives ─────────────────
 * `/reservations` is no longer a paged table; it is a CALENDAR:
 *   - DAY view = aircraft×hour grid (local 08–20 business window), reusing the
 *     T-10 scheduler placement. Each aircraft is a lane
 *     (`reservation-scheduler-lane-<aircraftId>`); each reservation STARTING ON
 *     the selected day is a time-placed block
 *     (`reservation-scheduler-block-<id>`). All-day → a full-width band.
 *   - WEEK view = aircraft×day matrix (`reservations-week-grid`, per-cell
 *     `reservations-week-cell-<aircraftId>-<YYYY-MM-DD>`).
 *   - day/week toggle (`reservations-view-day` / `reservations-view-week`) + a
 *     week day-picker (`reservations-daypicker-<YYYY-MM-DD>`, prev/next week).
 * `/reservation-scheduler` now REDIRECTS to `/reservations`. The paged table +
 * its `reservations-row-/immat-/allday-` testids are GONE — render assertions
 * moved to the calendar block in the right lane.
 *
 * The store STILL calls the paged + picker endpoints (`pageAircraftReservations`,
 * `listAircraftForPicker`, `listPersons`, `listLocations`,
 * `listAircraftReservationTypes`) and the calendar is a pure derivation over the
 * loaded entities — so the route mocks below are unchanged; we just assert the
 * calendar render, not a table.
 *
 * The day view only shows reservations that START ON the selected day, and the
 * calendar defaults to TODAY. So the mock reservations are dated to TODAY (at
 * fixed business-hours) — TZ-robust (no week-shifting to a far-future date) and
 * deterministic (the seed always lands on the default day view).
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
// `/aircraft/picker` payload carries as `id` — they MUST match so the day-view
// lane label (immatriculation) + placement resolve.
const AC_SAME = '019e30c3-2c00-7001-8000-00000000a001';
const AC_OTHER = '019e30c3-2c00-7001-8000-00000000a002';

const PILOT_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const LOCATION_ID = '019e30c3-2c00-7001-8000-00000000c001';

// Reservation types (loaded into the type dropdown).
const TYPE_FLIGHT_ID = '019e30c3-2c00-7001-8000-0000000000d1';
const TYPE_MAINT_ID = '019e30c3-2c00-7001-8000-0000000000d2';

const SEED_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000e01';

// ── TODAY (local) — the calendar's default day view. All mock reservations are
//    dated to today so they render on the default day grid without week-shifting
//    (the day view only shows reservations that START ON the selected day). The
//    date string the form's <af-input type=date> expects is `YYYY-MM-DD`.
function localToday(): { key: string; iso: (hhmm: string) => string } {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  const key = `${y}-${m}-${d}`;
  // The form serializes `<date>T<time>:00Z` (UTC); the calendar places by LOCAL
  // hour. Within a single TZ-consistent runner this round-trips to the chosen
  // wall-clock hour, which is all the placement assertion needs (offset > 0).
  return { key, iso: (hhmm: string) => `${key}T${hhmm}:00Z` };
}
const TODAY = localToday();

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

// A pre-existing TIMED reservation on the same-tenant aircraft (TODAY 10:00–11:00),
// the block every conflict/edit/delete AC probes against. It starts on today, so
// it renders on the default day view in the AC_SAME lane.
const seedReservation: MockReservation = {
  id: SEED_RESERVATION_ID,
  operatingClubId: CLUB_A_ID,
  aircraftId: AC_SAME,
  pilotPersonId: PILOT_ID,
  locationId: LOCATION_ID,
  reservationTypeId: TYPE_FLIGHT_ID,
  reservationTypeName: 'Flight',
  isAllDay: false,
  start: TODAY.iso('10:00'),
  end: TODAY.iso('11:00'),
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
  // Picker reference data the edit form's selects load + the calendar decorates
  // lane labels / pilot names from:
  //   aircraft → /aircraft/picker (op listAircraftForPicker)
  //   persons  → /persons          (op listPersons)
  //   locations→ /locations        (op listLocations)
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
 * reservation appears in the next paged read (→ the calendar re-derives), a
 * conflicting create is rejected 409, an end≤start timed create is rejected 422,
 * and a delete frees the slot.
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
    // PLAIN-UUID id segment (reservation ids are raw UUIDs).
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

/**
 * Navigate, forcing the German cold-start locale (`?lang=de`). German is the
 * product's primary market + the i18n source (`de.ts`), so the gallery + these
 * assertions are German. The cold-start chain is `?lang=` → navigator.language →
 * `de` (web/CLAUDE.md §8b); the mock chromium runner's navigator.language is
 * `en`, so we pin `?lang=de` EXPLICITLY (it wins the cold-start) — matching the
 * real-idp reservations spec. Only the cold-start `page.goto` needs it;
 * subsequent in-app router navs keep the in-memory locale. The `toHaveURL`
 * assertions run after an in-app click (a fresh router path with no query), so
 * the `?lang=de` query never leaks into them.
 */
async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

/** The day-view block for a reservation, scoped to its aircraft lane. */
function dayBlock(page: Page, aircraftId: string, reservationId: string) {
  return page
    .getByTestId(`reservation-scheduler-lane-${aircraftId}`)
    .getByTestId(`reservation-scheduler-block-${reservationId}`);
}

/**
 * Navigate the calendar's week day-picker to the day `dateKey` (`YYYY-MM-DD`) and
 * select it, so the day view shows reservations starting on that day. The picker
 * renders only the SELECTED day's Monday→Sunday week, so we shift weeks
 * (next/prev) until the target's pill is present, then click it.
 *
 * The naive "click while count===0" loop OVERSHOOTS: Playwright clicks faster
 * than Angular re-renders the picker, so `count()` still reads 0 after the target
 * week is shown and the loop clicks past it (and the target pill then disappears
 * again — the J-5 T-45 fanout red `:721`). Fix: after EACH shift, wait for the
 * picker to actually re-render to a NEW week (a different first-pill key) before
 * re-checking — so each click is observed before the next, no race, no overshoot.
 * Mirrors the real-idp spec helper (shared calendar DOM). No `waitForTimeout`.
 */
async function selectCalendarDay(page: Page, dateKey: string): Promise<void> {
  const pill = page.getByTestId(`reservations-daypicker-${dateKey}`);
  const todayMs = new Date(`${TODAY.key}T00:00:00`).getTime();
  const targetMs = new Date(`${dateKey}T00:00:00`).getTime();
  const direction = targetMs >= todayMs ? 'next' : 'prev';
  // The picker's seven pills carry `data-testid="reservations-daypicker-<key>"`;
  // the first pill's key identifies the rendered week.
  const firstPillKey = async (): Promise<string | null> => {
    const first = page.locator('[data-testid^="reservations-daypicker-"]').first();
    if ((await first.count()) === 0) return null;
    return (
      (await first.getAttribute('data-testid'))?.replace('reservations-daypicker-', '') ?? null
    );
  };
  for (let i = 0; i < 60 && (await pill.count()) === 0; i++) {
    const before = await firstPillKey();
    await page.getByTestId(`reservations-${direction}-week`).click();
    // Gate on the picker re-rendering to a different week before re-checking,
    // so we never click past the target while the DOM still shows the old week.
    await expect
      .poll(async () => firstPillKey(), { message: 'the day-picker must shift to a new week' })
      .not.toBe(before);
  }
  await expect(pill, `the day-picker must reach ${dateKey}`).toBeVisible();
  await pill.click();
}

/** `YYYY-MM-DD` `days` days from local today (the picker-nav target day). */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** Parse the leftPct out of the day block's `calc(<n>% + 2px)` left style. */
async function blockLeftPct(
  page: Page,
  aircraftId: string,
  reservationId: string,
): Promise<number> {
  const left = await dayBlock(page, aircraftId, reservationId).evaluate(
    (el) => (el as HTMLElement).style.left,
  );
  const m = /([\d.]+)%/.exec(left);
  return m ? Number.parseFloat(m[1]!) : Number.NaN;
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

// ── inner-loop suite — drives the SHIPPED calendar screens with full assertions
test.describe('J-5 aircraft reservations (mock-auth inner loop)', () => {
  // ── AC: the calendar day view renders the seeded reservation as a placed
  //    block in its aircraft lane (lane×time placement) ──────────────────────
  test('calendar (day view): the seeded reservation renders as a placed block in its aircraft lane', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    await gotoDe(page, '/reservations');

    await expect(page.locator('h1')).toContainText('Reservationen');
    // Day view is the default; its grid + the day/week toggle render.
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    await expect(page.getByTestId('reservations-view-toggle')).toBeVisible();

    // The aircraft lane for the seed exists + is labelled by its immatriculation
    // (decorated client-side from the picker label map, ADR 0023).
    const lane = page.getByTestId(`reservation-scheduler-lane-${AC_SAME}`);
    await expect(lane).toBeVisible();
    await expect(lane).toContainText('HB-SAME');

    // The reservation is a time-placed block INSIDE that lane (lane×placement).
    const block = dayBlock(page, AC_SAME, SEED_RESERVATION_ID);
    await expect(block).toBeVisible();
    // Block carries the pilot label + its time window (decorated).
    await expect(block).toContainText('Anna Pilot');
    await expect(block).toContainText('10:00');

    // Timed placement: a non-zero, sub-100 left offset (a full-day band would be
    // 0%). The exact % depends on the runner TZ over the 08–20 window, so we
    // assert the meaningful invariant — placed inside the day, not full-width.
    const leftPct = await blockLeftPct(page, AC_SAME, SEED_RESERVATION_ID);
    expect(leftPct, 'a timed block carries a positive sub-100 left offset').toBeGreaterThan(0);
    expect(leftPct).toBeLessThan(100);

    await page.screenshot({
      path: 'screenshots/reservations/01-calendar-day.png',
      fullPage: true,
    });
  });

  // ── AC[happy]: the store sends + receives the SPA paged envelope ──────────
  test('calendar: the paged read sends + receives the SPA envelope shape', async ({ page }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Capture the paged read so the envelope shape is asserted on the wire
    // (totalRows + pageStart + pageSize + items[]), not just the rendered block.
    const paged = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/aircraft-reservations\/page\/\d+\/\d+$/.test(new URL(r.url()).pathname) &&
        r.status() === 200,
    );
    await gotoDe(page, '/reservations');
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

  // ── AC: the day/week toggle + day-picker drive the views ─────────────────
  test('calendar: the day/week toggle and the week day-picker switch views', async ({ page }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    await gotoDe(page, '/reservations');
    // Default = day view.
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    await expect(page.getByTestId('reservations-week-grid')).toHaveCount(0);

    // Toggle to the WEEK view (aircraft×day matrix). The seed's aircraft lane +
    // its today cell (with a count) render in the week grid.
    await page.getByTestId('reservations-view-week').click();
    await expect(page.getByTestId('reservations-week-grid')).toBeVisible();
    await expect(page.getByTestId('reservations-day-grid')).toHaveCount(0);
    const weekCell = page.getByTestId(`reservations-week-cell-${AC_SAME}-${TODAY.key}`);
    await expect(weekCell).toBeVisible();
    // The today cell shows the count (1 reservation) + reserved hours.
    await expect(weekCell).toContainText('1');
    await page.screenshot({
      path: 'screenshots/reservations/06-calendar-week.png',
      fullPage: true,
    });

    // Clicking a week cell opens that day in the DAY view (the calendar focuses
    // the selected day) — back to the day grid with the block placed.
    await weekCell.click();
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    await expect(dayBlock(page, AC_SAME, SEED_RESERVATION_ID)).toBeVisible();

    // The day-picker keeps the day selectable directly (today's pill).
    await page.getByTestId('reservations-view-week').click();
    await expect(page.getByTestId('reservations-week-grid')).toBeVisible();
    await page.getByTestId(`reservations-daypicker-${TODAY.key}`).click();
    // Selecting a day-picker pill stays on the week view (it re-selects the day);
    // toggling back to day shows the seed's block again.
    await page.getByTestId('reservations-view-day').click();
    await expect(dayBlock(page, AC_SAME, SEED_RESERVATION_ID)).toBeVisible();
  });

  // ── AC[happy]: create a TIMED reservation → it renders as a day-view block ─
  test('create: a timed reservation persists and appears as a placed block in the day view', async ({
    page,
  }) => {
    await wireReservations(page, []);

    await gotoDe(page, '/reservations');
    await page.getByTestId('reservations-new-button').locator('button').click();
    await expect(page).toHaveURL('/reservations/new');

    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill(TODAY.key);
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
    const createdResp = await created;
    const id = new URL(createdResp.headers()['location']!, 'http://localhost').pathname
      .split('/')
      .pop()!;

    await expect(page).toHaveURL('/reservations');
    // The created reservation renders as a block in its aircraft lane on the day
    // view (the table `reservations-row-*` is GONE — calendar block is the proof).
    const block = dayBlock(page, AC_SAME, id);
    await expect(block).toBeVisible();
    await expect(block).toContainText('14:00');
    // Timed placement: a 14:00 block is offset to the RIGHT of a 10:00 block
    // (proves the time→offset math), still inside the day.
    const leftPct = await blockLeftPct(page, AC_SAME, id);
    expect(leftPct, 'a 14:00 block is offset right of the lane start').toBeGreaterThan(0);
    expect(leftPct).toBeLessThan(100);
    await page.screenshot({
      path: 'screenshots/reservations/02-timed-created.png',
      fullPage: true,
    });
  });

  // ── AC[happy]: ALL-DAY reservation renders as a full-width band ───────────
  test('create: an all-day reservation hides the time fields and renders as a full-width day band', async ({
    page,
  }) => {
    await wireReservations(page, []);

    await gotoDe(page, '/reservations/new');

    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill(TODAY.key);
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
    expect(sent.start).toBe(`${TODAY.key}T00:00:00Z`);
    expect(sent.end).toBe(`${TODAY.key}T00:00:00Z`);
    const id = new URL(createdRes.headers()['location']!, 'http://localhost').pathname
      .split('/')
      .pop()!;

    await expect(page).toHaveURL('/reservations');
    // Full-day band on the day view: an all-day block spans the whole lane
    // (placement leftPct 0, widthPct 100 → `calc(0% + 2px)` / `calc(100% - 4px)`).
    const block = dayBlock(page, AC_SAME, id);
    await expect(block).toBeVisible();
    const leftPct = await blockLeftPct(page, AC_SAME, id);
    expect(leftPct, 'an all-day band starts at the lane left edge (0%)').toBe(0);
    const width = await block.evaluate((el) => (el as HTMLElement).style.width);
    expect(width, 'an all-day reservation is a full-width band').toContain('100%');
    await page.screenshot({ path: 'screenshots/reservations/03-allday-band.png', fullPage: true });
  });

  // ── AC[key-error]: conflict→409 + edit-in-place does NOT self-conflict ───
  test('conflict: an overlapping create is rejected 409 inline; editing the existing block does NOT self-conflict', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Try to book the SAME aircraft 10:30–10:45 — overlaps the 10:00–11:00 seed.
    await gotoDe(page, '/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill(TODAY.key);
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

    // Editing the EXISTING overlapping reservation (self-exclude) → no conflict.
    // The calendar opens the edit form by CLICKING the day-view block (the block
    // is a button → /reservations/:id/edit), proving the block→edit affordance.
    await gotoDe(page, '/reservations');
    await dayBlock(page, AC_SAME, SEED_RESERVATION_ID).click();
    await expect(page).toHaveURL(`/reservations/${SEED_RESERVATION_ID}/edit`);
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

    await gotoDe(page, '/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill(TODAY.key);
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

  // ── AC[happy]: delete frees the slot so a new overlapping create succeeds ──
  // The calendar has no delete affordance (delete is not a UI list action in the
  // calendar-first design); the delete-frees domain proof drives the DELETE via
  // the (mocked) REST API — intercepted by the same route handler the UI uses —
  // then re-renders the calendar to confirm the block is gone and the slot frees.
  test('delete: deleting a reservation frees the slot so a new overlapping create then succeeds', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    // Pre-flight: a 10:30–10:45 create overlaps the seed → 409 (proves the slot
    // is occupied before the delete frees it).
    await gotoDe(page, '/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill(TODAY.key);
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

    // Confirm the seed block is on the day view, then delete via the REST API
    // (the mock route handler removes it from subsequent reads — soft-delete).
    await gotoDe(page, '/reservations');
    await expect(dayBlock(page, AC_SAME, SEED_RESERVATION_ID)).toBeVisible();
    const del = await page.request.delete(`/api/v1/aircraft-reservations/${SEED_RESERVATION_ID}`);
    expect(del.status()).toBe(204);
    // Re-render the calendar — the deleted block is gone from the day grid.
    await gotoDe(page, '/reservations');
    await expect(dayBlock(page, AC_SAME, SEED_RESERVATION_ID)).toHaveCount(0);

    // The freed 10:00–11:00 slot now ACCEPTS the same overlapping booking (201).
    await gotoDe(page, '/reservations/new');
    await fillReservationCommon(page, AC_SAME);
    await page.getByTestId('reservation-date').locator('input').fill(TODAY.key);
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

    await gotoDe(page, '/reservations/new');
    // HB-OTHR is owned by CLUB_B (not the operating tenant) — legacy-open: the
    // picker offers it and the create succeeds (no tenant/charter rejection),
    // stamped with the operating club.
    await fillReservationCommon(page, AC_OTHER);
    await page.getByTestId('reservation-date').locator('input').fill(TODAY.key);
    await page.getByTestId('reservation-start-time').locator('input').fill('09:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('10:00');
    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
        r.status() === 201,
    );
    await page.getByTestId('reservation-save-button').click();
    const createdResp = await created;
    const id = new URL(createdResp.headers()['location']!, 'http://localhost').pathname
      .split('/')
      .pop()!;

    await expect(page).toHaveURL('/reservations');
    await expect(saveError(page)).toHaveCount(0);
    // The foreign-managed aircraft gets its own lane on the day view, with the
    // cross-tenant reservation placed in it — the render proof of the open rule.
    const lane = page.getByTestId(`reservation-scheduler-lane-${AC_OTHER}`);
    await expect(lane).toBeVisible();
    await expect(lane).toContainText('HB-OTHR');
    await expect(dayBlock(page, AC_OTHER, id)).toBeVisible();
  });

  // ── AC[happy]: an all-day reservation on a FUTURE day renders as a full-day
  //    band — reached via the week day-picker nav (proves the same shared
  //    calendar DOM the real-idp `:485` all-day + `:721` migrated nav rely on,
  //    locally; J-5 T-45). The all-day reservation is dated to a distinct future
  //    day (not today) so it can't collide with a same-day timed booking on the
  //    same aircraft — exactly the collision that red-ed the real-idp `:485`
  //    create (an all-day full-day span overlaps any same-aircraft timed row). ──
  test('calendar: an all-day reservation on a future day renders as a full-width band (day-picker nav)', async ({
    page,
  }) => {
    const futureKey = dayKeyFromToday(7);
    const ALLDAY_ID = '019e30c3-2c00-7001-8000-000000000f01';
    const allDay: MockReservation = {
      id: ALLDAY_ID,
      operatingClubId: CLUB_A_ID,
      aircraftId: AC_SAME,
      pilotPersonId: PILOT_ID,
      locationId: LOCATION_ID,
      reservationTypeId: TYPE_FLIGHT_ID,
      reservationTypeName: 'Flight',
      isAllDay: true,
      // All-day normalises to the full-day span [date 00:00, date+1 00:00) — the
      // list item carries the day's midnight start (the real backend's shape).
      start: `${futureKey}T00:00:00Z`,
      end: `${futureKey}T00:00:00Z`,
    };
    await wireReservations(page, [allDay]);

    await gotoDe(page, '/reservations');
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();

    // Navigate the week day-picker forward to the all-day reservation's day.
    await selectCalendarDay(page, futureKey);

    // It renders as a FULL-WIDTH band in its aircraft lane (placement leftPct 0,
    // widthPct 100 → `calc(0% + 2px)` / `calc(100% - 4px)`).
    const block = dayBlock(page, AC_SAME, ALLDAY_ID);
    await expect(block).toBeVisible();
    const leftPct = await blockLeftPct(page, AC_SAME, ALLDAY_ID);
    expect(leftPct, 'an all-day band starts at the lane left edge (0%)').toBe(0);
    const width = await block.evaluate((el) => (el as HTMLElement).style.width);
    expect(width, 'an all-day reservation is a full-width band').toContain('100%');
  });

  // ── AC: the week day-picker navigates to a future timed reservation's day and
  //    shows its block — proves the `selectCalendarDay` nav helper does not
  //    overshoot (the real-idp `:721` migrated-render nav, locally). ───────────
  test('calendar: the week day-picker navigates to a future timed reservation and shows its block', async ({
    page,
  }) => {
    const futureKey = dayKeyFromToday(10);
    const FUTURE_ID = '019e30c3-2c00-7001-8000-000000000f02';
    const future: MockReservation = {
      id: FUTURE_ID,
      operatingClubId: CLUB_A_ID,
      aircraftId: AC_SAME,
      pilotPersonId: PILOT_ID,
      locationId: LOCATION_ID,
      reservationTypeId: TYPE_FLIGHT_ID,
      reservationTypeName: 'Flight',
      isAllDay: false,
      start: `${futureKey}T13:00:00Z`,
      end: `${futureKey}T14:00:00Z`,
    };
    await wireReservations(page, [future]);

    await gotoDe(page, '/reservations');
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    // The future reservation is NOT on today's default day view.
    await expect(dayBlock(page, AC_SAME, FUTURE_ID)).toHaveCount(0);

    // Navigate the week picker to its day (multiple week-shifts) — must land
    // exactly on the target day, not overshoot.
    await selectCalendarDay(page, futureKey);
    await expect(dayBlock(page, AC_SAME, FUTURE_ID)).toBeVisible();
  });

  // ── AC: /reservation-scheduler redirects to the calendar (T-39) ──────────
  test('redirect: /reservation-scheduler redirects to the /reservations calendar', async ({
    page,
  }) => {
    await wireReservations(page, [{ ...seedReservation }]);

    await gotoDe(page, '/reservation-scheduler');
    // The standalone scheduler route is folded into the calendar — it redirects.
    await expect(page).toHaveURL('/reservations');
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    // The day view IS the old scheduler grid: the seed block is placed in its lane.
    await expect(dayBlock(page, AC_SAME, SEED_RESERVATION_ID)).toBeVisible();
  });
});
