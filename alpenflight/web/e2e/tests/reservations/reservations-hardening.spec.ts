import { expect, test, type Page } from '@playwright/test';

/**
 * Reservations calendar HARDENING + nav gating — J-6b INNER-LOOP spec STUB (T-01).
 *
 * SPEC SKELETON ONLY. This task (J-6b T-01) commits the *screen shape* — the
 * `data-testid` selectors + the flow steps + THIN assertions — for the J-5
 * reservations calendar polish + the nav shell role-gating. The page-driving
 * cases are `test.fixme` (parsed + typechecked, NOT run); they un-fixme as
 * T-08 (toggle + pager + label) and T-11 (nav gating) land the behaviour, and
 * T-17 thickens these to the full real assertions for the 14 ACs. The mock
 * route wiring is authored so un-fixme is a per-test flip, not a rewrite.
 * Mirrors the J-5 inner-loop discipline (`reservations-crud.spec.ts`) +
 * J-6's (`planning-crud.spec.ts`).
 *
 * Mock-auth fidelity (once un-fixme'd): the dev server boots under
 * `--configuration=mock-auth` (synthetic SYSTEM_ADMINISTRATOR +
 * CLUB_ADMINISTRATOR principal so the affordances render); every `/api/v1/*`
 * call is intercepted via `page.route` — no live backend. The FULL real
 * legacy→migrate→Keycloak→Playwright chain is the gate's job
 * (`tests/real-idp/…`); this spec pins the screen behaviour fast in the inner
 * loop. Nav role-gating (Clubs-hide for a club-admin) needs a REAL low-privilege
 * principal to be meaningful — the mock principal is admin-everything — so the
 * gating assertions ALSO carry a real-idp sibling at T-17 (mock-auth here just
 * pins that the Reservations entry renders + the routing wiring).
 *
 * ── SCREEN SHAPE (from J-6b "Spec must assert" §Reservations + §Nav) ──────────
 *   NAV (T-11) — the primary nav (`af-nav-bar`) carries a `Reservations` section
 *     (`af-nav-section-/reservations`) routing to `/reservations`; a club-admin
 *     does NOT see a `Clubs` section (`af-nav-section-/clubs` absent — /clubs is
 *     made sysadmin-only). Reservations-present is parity-neutral (just add it);
 *     Clubs-hide is a NEW operator decision (legacy showed Clubs to all).
 *   CALENDAR (T-08) — `/reservations` is a Day/Week CALENDAR (greenfield UX, NO
 *     legacy parity → AlpenFlight-only gallery shots):
 *     (a) the Day/Week toggle's SELECTED button uses the design's selected style
 *         (`--color-fg` bg + `--color-bg` text per screens-reservations.jsx:106-110),
 *         legible — not "blacked out" (contrast inverted on the current build).
 *     (b) the pager granularity follows the active view: DAY view steps ±1 day,
 *         WEEK view steps ±7 days (today `shiftWeek` always steps 7).
 *     (c) the period label follows the view + formats DD.MM.YYYY (single day in
 *         day view) / DD.MM.YYYY – DD.MM.YYYY (range in week view) — today
 *         `weekSubtitle` is a locale string (`3 Jan – 9 Jan`).
 *
 * Selectors reused from the J-5 calendar (`reservations-crud.spec.ts`):
 *   `reservations-view-toggle` / `reservations-view-day` / `reservations-view-week`,
 *   `reservations-day-grid` / `reservations-week-grid`,
 *   `reservations-prev-week` / `reservations-next-week` (week pager — exist),
 * NEW for J-6b T-08 (land with the behaviour; named here so the shape is committed):
 *   `reservations-prev-day` / `reservations-next-day` (DAY pager — step ±1 day),
 *   `reservations-period-label`           (the view-aware DD.MM.YYYY[ – DD.MM.YYYY] label),
 *   `[data-selected="true"]` on the active toggle button (selected-style hook).
 */

// ── ids (UUIDv7-shaped, matching the J-5 calendar fixtures) ──────────────────
const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const AC_SAME = '019e30c3-2c00-7001-8000-00000000a001';
const PILOT_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const LOCATION_ID = '019e30c3-2c00-7001-8000-00000000c001';
const TYPE_FLIGHT_ID = '019e30c3-2c00-7001-8000-0000000000d1';
const SEED_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000e01';

/** Local today (the calendar's default day view), `YYYY-MM-DD` + an ISO at hh:mm. */
function localToday(): { key: string; iso: (hhmm: string) => string; ddmmyyyy: string } {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  const key = `${y}-${m}-${d}`;
  return {
    key,
    ddmmyyyy: `${d}.${m}.${y}`,
    iso: (hhmm: string) => `${key}T${hhmm}:00Z`,
  };
}

const TODAY = localToday();

interface MockReservation {
  id: string;
  aircraftId: string;
  operatingClubId: string;
  pilotPersonId: string;
  locationId: string;
  reservationTypeId: string;
  start: string;
  end: string;
  isAllDay: boolean;
}

const seedReservation: MockReservation = {
  id: SEED_RESERVATION_ID,
  aircraftId: AC_SAME,
  operatingClubId: CLUB_A_ID,
  pilotPersonId: PILOT_ID,
  locationId: LOCATION_ID,
  reservationTypeId: TYPE_FLIGHT_ID,
  start: TODAY.iso('10:00'),
  end: TODAY.iso('12:00'),
  isAllDay: false,
};

const mockAircraft = [{ id: AC_SAME, immatriculation: 'HB-3210', operatingClubId: CLUB_A_ID }];
const mockPersons = [{ id: PILOT_ID, firstname: 'Petra', lastname: 'Pilot', isActive: true }];
const mockLocations = [{ id: LOCATION_ID, locationName: 'Bern-Belp', isAirfield: true }];
const mockTypes = [{ id: TYPE_FLIGHT_ID, name: 'Flight' }];

/** Wire the calendar read endpoints the reservations store loads. */
async function wireReservations(page: Page, reservations: MockReservation[]): Promise<void> {
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: mockPersons }));
  await page.route('**/api/v1/locations**', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: mockTypes }),
  );
  await page.route('**/api/v1/aircraft-reservations/page**', (route) =>
    route.fulfill({ json: { items: reservations, totalRows: reservations.length } }),
  );
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

// ── Nav gating (T-11, items #1/#8) ───────────────────────────────────────────
test.describe('J-6b nav gating (mock-auth inner loop)', () => {
  test.fixme('Reservations nav entry present + routes to /reservations', async ({ page }) => {
    await page.route('**/api/v1/clubs**', (route) => route.fulfill({ json: { items: [] } }));
    await gotoDe(page, '/reservations');

    const entry = page.getByTestId('af-nav-section-/reservations');
    await expect(entry).toBeVisible();
    await entry.click();
    await expect(page).toHaveURL(/\/reservations(\?|$)/);
  });

  test.fixme('club-admin does NOT see a Clubs nav entry', async ({ page }) => {
    // NOTE: this needs a REAL low-privilege (club-admin) principal — the
    // mock-auth principal is admin-everything, so the meaningful assertion lives
    // in the real-idp sibling (T-17). Here we only commit the absence selector.
    await gotoDe(page, '/reservations');
    await expect(page.getByTestId('af-nav-section-/clubs')).toHaveCount(0);
  });
});

// ── Calendar Day/Week toggle + pager + label (T-08, items #2/#3) ─────────────
test.describe('J-6b reservations calendar hardening (mock-auth inner loop)', () => {
  test.fixme('Day/Week toggle renders the SELECTED button legibly', async ({ page }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    const toggle = page.getByTestId('reservations-view-toggle');
    await expect(toggle).toBeVisible();

    // Day is selected by default → carries the selected-style hook.
    const dayBtn = page.getByTestId('reservations-view-day');
    await expect(dayBtn).toHaveAttribute('data-selected', 'true');
    await expect(page.getByTestId('reservations-view-week')).toHaveAttribute(
      'data-selected',
      'false',
    );

    await page.screenshot({
      path: 'screenshots/reservations/06-toggle-day-selected.png',
      fullPage: true,
    });

    await page.getByTestId('reservations-view-week').click();
    await expect(page.getByTestId('reservations-view-week')).toHaveAttribute(
      'data-selected',
      'true',
    );
    await expect(dayBtn).toHaveAttribute('data-selected', 'false');
  });

  test.fixme('DAY view pages by single days; the label is DD.MM.YYYY', async ({ page }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    // Day-view label is the single day, DD.MM.YYYY.
    await expect(page.getByTestId('reservations-period-label')).toContainText(TODAY.ddmmyyyy);

    // DAY pager steps ±1 day (NOT 7 — today's bug is shiftWeek always stepping 7).
    await page.getByTestId('reservations-next-day').click();
    await expect(page.getByTestId('reservations-period-label')).not.toContainText(TODAY.ddmmyyyy);
    await page.getByTestId('reservations-prev-day').click();
    await expect(page.getByTestId('reservations-period-label')).toContainText(TODAY.ddmmyyyy);
  });

  test.fixme('WEEK view pages by weeks; the label is a DD.MM.YYYY – DD.MM.YYYY range', async ({
    page,
  }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    await page.getByTestId('reservations-view-week').click();
    await expect(page.getByTestId('reservations-week-grid')).toBeVisible();

    // Week-view label is a DD.MM.YYYY – DD.MM.YYYY range (start–end of the week).
    const label = page.getByTestId('reservations-period-label');
    await expect(label).toHaveText(/\d{2}\.\d{2}\.\d{4}\s*[–-]\s*\d{2}\.\d{2}\.\d{4}/);

    // WEEK pager steps a whole week — the label range moves.
    const before = (await label.textContent())?.trim();
    await page.getByTestId('reservations-next-week').click();
    await expect(label).not.toHaveText(before ?? '');

    await page.screenshot({
      path: 'screenshots/reservations/07-week-range-label.png',
      fullPage: true,
    });
  });
});
