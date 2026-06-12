import { expect, test, type Page } from '@playwright/test';

/**
 * Planning-day LIST page — J-6 T-07 inner-loop spec.
 *
 * Drives the REAL `/planning` list contract the T-07 store calls:
 *   GET /api/v1/planning-days/overview/future → PlanningDayDetail[]
 *   GET /api/v1/locations                     → location picker (name decoration)
 *   GET /api/v1/persons                        → person picker (crew decoration)
 *
 * Mock-auth: the dev server boots under `--configuration=mock-auth` (synthetic
 * SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR principal, so New / Setup / edit /
 * delete affordances render); every `/api/v1/*` call is intercepted via
 * `page.route`. The FULL real chain + the create/edit/setup flows are the
 * sibling `planning-crud.spec.ts` (T-08/T-09/T-16) + the real-idp parity spec.
 *
 * The shared `planning-crud.spec.ts` stub targets the future POST-page contract
 * + edit-form selectors (un-fixme'd as those screens land in T-08/T-09); this
 * spec pins the LIST screen against the GET-future contract the T-07 store uses.
 */

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const LOCATION_BERN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
const LOCATION_THUN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c002';
const INSTRUCTOR_ID = 'pn-019e30c3-2c00-7001-8000-0000000000b1';
const TOWPILOT_ID = 'pn-019e30c3-2c00-7001-8000-0000000000b2';
const FLIGHTOP_ID = 'pn-019e30c3-2c00-7001-8000-0000000000b3';

const WEEKDAY_DAY_ID = '019e30c3-2c00-7001-8000-000000000e01';
const WEEKEND_DAY_ID = '019e30c3-2c00-7001-8000-000000000e02';

/** `YYYY-MM-DD`, `days` from local today (planning days are FUTURE, pure DATE). */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * Like {@link dayKeyFromToday} but shifted past a Saturday/Sunday landing —
 * the row this key feeds is asserted `data-weekend=false` (line ~167), and a
 * naive today+3 lands on the weekend whenever the suite runs on Wed/Thu
 * (first hit: 2026-06-11, a Thursday). Mirrors V34/V39's weekday targeting.
 */
function weekdayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const shift = d.getDay() === 6 ? 2 : d.getDay() === 0 ? 1 : 0; // Sat -> Mon, Sun -> Mon
  return dayKeyFromToday(days + shift);
}

/** The next Saturday from today (`YYYY-MM-DD`) — exercises the Sat/Sun flag. */
function nextSaturdayKey(): string {
  const d = new Date();
  const delta = (6 - d.getDay() + 7) % 7 || 7; // 6 = Saturday; never today
  d.setDate(d.getDate() + delta);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const WEEKDAY_DAY_KEY = weekdayKeyFromToday(3);
const WEEKEND_DAY_KEY = nextSaturdayKey();

interface MockPlanningDay {
  id: string;
  operatingClubId: string;
  planningDate: string;
  locationId: string;
  instructorPersonId?: string;
  towingPilotPersonId?: string;
  flightOperatorPersonId?: string;
  info?: string;
  numberOfAircraftReservations: number;
  canUpdateRecord: boolean;
  canDeleteRecord: boolean;
}

const weekdayDay: MockPlanningDay = {
  id: WEEKDAY_DAY_ID,
  operatingClubId: CLUB_A_ID,
  planningDate: WEEKDAY_DAY_KEY,
  locationId: LOCATION_BERN_ID,
  instructorPersonId: INSTRUCTOR_ID,
  towingPilotPersonId: TOWPILOT_ID,
  flightOperatorPersonId: FLIGHTOP_ID,
  numberOfAircraftReservations: 1,
  canUpdateRecord: true,
  canDeleteRecord: true,
};

const weekendDay: MockPlanningDay = {
  id: WEEKEND_DAY_ID,
  operatingClubId: CLUB_A_ID,
  planningDate: WEEKEND_DAY_KEY,
  locationId: LOCATION_THUN_ID,
  numberOfAircraftReservations: 0,
  canUpdateRecord: true,
  canDeleteRecord: true,
};

const mockLocations = [
  { id: LOCATION_BERN_ID, locationName: 'Bern-Belp', isAirfield: true, isFastEntryRecord: false },
  { id: LOCATION_THUN_ID, locationName: 'Thun', isAirfield: true, isFastEntryRecord: false },
];

const mockPersons = [
  { id: INSTRUCTOR_ID, firstname: 'Iris', lastname: 'Instructor', isActive: true },
  { id: TOWPILOT_ID, firstname: 'Tom', lastname: 'Towpilot', isActive: true },
  { id: FLIGHTOP_ID, firstname: 'Fred', lastname: 'Flightop', isActive: true },
];

async function wirePlanning(page: Page, days: MockPlanningDay[]): Promise<void> {
  await page.route('**/api/v1/locations', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocations),
    }),
  );
  await page.route('**/api/v1/persons', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockPersons),
    }),
  );
  // Mutable list so a delete drops the day on the post-delete refresh.
  const state = [...days];
  await page.route('**/api/v1/planning-days/overview/future', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state) }),
  );
  await page.route(/\/api\/v1\/planning-days\/[0-9a-f-]{36}$/, async (route) => {
    if (route.request().method() === 'DELETE') {
      const id = new URL(route.request().url()).pathname.split('/').pop()!;
      const idx = state.findIndex((d) => d.id === id);
      if (idx !== -1) state.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  });
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

function planningRow(page: Page, id: string) {
  return page.getByTestId(`planning-row-${id}`);
}

test.describe('J-6 planning list (mock-auth inner loop)', () => {
  test('future planning days render with crew + reservation count; weekend rows are flagged', async ({
    page,
  }) => {
    await wirePlanning(page, [weekdayDay, weekendDay]);

    await gotoDe(page, '/planning');
    await expect(page.locator('h1')).toContainText('Planung');
    await expect(page.getByTestId('planning-list')).toBeVisible();

    const row = planningRow(page, WEEKDAY_DAY_ID);
    await expect(row).toBeVisible();
    await expect(row).toContainText('Bern-Belp');
    await expect(row).toContainText('Iris Instructor');
    await expect(row).toContainText('Tom Towpilot');
    await expect(row).toContainText('Fred Flightop');
    await expect(row.getByTestId(`planning-reservations-count-${WEEKDAY_DAY_ID}`)).toContainText(
      '1',
    );

    // The Saturday row is visually flagged (legacy isSaturday/isSunday parity).
    await expect(planningRow(page, WEEKEND_DAY_ID)).toHaveAttribute('data-weekend', 'true');
    await expect(row).toHaveAttribute('data-weekend', 'false');

    await page.screenshot({ path: 'screenshots/planning/01-list.png', fullPage: true });
  });

  test('top actions (New + Setup) render for an admin principal', async ({ page }) => {
    await wirePlanning(page, [weekdayDay]);
    await gotoDe(page, '/planning');

    // New (→ /planning/new/edit) + Setup (→ /planningsetup) — those target
    // routes land in T-08/T-09; here we pin the list page's wiring contract
    // (the affordances render for the admin principal). Post-nav URL is asserted
    // by the sibling crud + setup-wizard specs once those routes exist.
    await expect(page.getByTestId('planning-new-button')).toBeVisible();
    await expect(page.getByTestId('planning-setup-button')).toBeVisible();
  });

  test('delete asks for confirmation, calls DELETE, and the day leaves the list', async ({
    page,
  }) => {
    await wirePlanning(page, [weekdayDay]);
    await gotoDe(page, '/planning');
    await expect(planningRow(page, WEEKDAY_DAY_ID)).toBeVisible();

    await page.getByTestId(`planning-kebab-${WEEKDAY_DAY_ID}`).click();
    await page.getByTestId(`planning-delete-${WEEKDAY_DAY_ID}`).click();
    await expect(page.getByTestId('planning-delete-dialog')).toBeVisible();

    const deleted = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new URL(r.url()).pathname === `/api/v1/planning-days/${WEEKDAY_DAY_ID}` &&
        r.status() === 204,
    );
    await page.getByTestId('planning-delete-confirm').click();
    await deleted;

    await expect(planningRow(page, WEEKDAY_DAY_ID)).toHaveCount(0);
    await expect(page.getByTestId('planning-empty')).toBeVisible();
    await page.screenshot({ path: 'screenshots/planning/02-after-delete.png', fullPage: true });
  });
});
