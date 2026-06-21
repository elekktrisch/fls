import { type Page } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

/**
 * Reservations calendar HARDENING + nav routing — J-6b INNER-LOOP spec (T-17,
 * thickened from the T-01 stub to full real assertions).
 *
 * Mock-auth fidelity: the dev server boots under `--configuration=mock-auth`
 * (synthetic SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR principal); every
 * `/api/v1/*` call is intercepted via `page.route` — no live backend. The FULL
 * real legacy→migrate→Keycloak→Playwright chain is the gate's job
 * (`tests/real-idp/reservations-planning-hardening.spec.ts`); this spec pins the
 * calendar polish (toggle selected-style + view-aware pager + DD.MM.YYYY label)
 * fast in the inner loop with REAL assertions.
 *
 * ── NAV ROLE-GATING is a REAL-IDP concern (deliberately NOT asserted here) ────
 * The mock principal carries BOTH roles, and `navSectionsFor` SHORT-CIRCUITS on
 * `isSystemAdmin` → the mock nav renders Clubs ONLY (no Reservations entry, no
 * Users). So the meaningful nav matrix — a real CLUB_ADMINISTRATOR sees
 * Reservations + NO Clubs; a sysadmin sees Clubs — can ONLY be proven against a
 * REAL low-privilege principal, and lives in the real-idp sibling
 * (`reservations-planning-hardening.spec.ts`, J-6b T-17). Here we pin only that
 * the `/reservations` ROUTE renders the calendar (the routing wiring); asserting
 * `af-nav-section-/reservations` under mock-auth would contradict the mock
 * principal (sysadmin branch) and red the per-push gate for the wrong reason.
 *
 * ── SCREEN SHAPE (J-6b "Spec must assert" §Reservations, T-08) ────────────────
 *   (a) the Day/Week toggle's SELECTED button carries `data-selected="true"` +
 *       the design's legible selected style (dark ground / light text per
 *       screens-reservations.jsx:106-110) — not "blacked out".
 *   (b) the pager granularity follows the active view: DAY view steps ±1 day
 *       (`reservations-prev-day`/`-next-day`), WEEK view steps ±7 days
 *       (`reservations-prev-week`/`-next-week`).
 *   (c) the period label follows the view + formats DD.MM.YYYY (single day in
 *       day view) / DD.MM.YYYY – DD.MM.YYYY (range in week view).
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

const mockAircraft = [
  { id: AC_SAME, immatriculation: 'HB-3210', operatingClubId: CLUB_A_ID, isTowingAircraft: false },
];
const mockPersons = [{ id: PILOT_ID, firstname: 'Petra', lastname: 'Pilot', isActive: true }];
const mockLocations = [{ id: LOCATION_ID, locationName: 'Bern-Belp', isAirfield: true }];
const mockTypes = [{ id: TYPE_FLIGHT_ID, name: 'Flight', active: true }];

/** Wire the calendar read endpoints the reservations store loads. */
async function wireReservations(page: Page, reservations: MockReservation[]): Promise<void> {
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/persons', (route) => route.fulfill({ json: mockPersons }));
  await page.route('**/api/v1/locations', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: mockTypes }),
  );
  // The store pages via POST /aircraft-reservations/page/{start}/{size}.
  await page.route('**/api/v1/aircraft-reservations/page/**', (route) =>
    route.fulfill({
      json: {
        items: reservations,
        pageStart: 0,
        pageSize: 20,
        totalRows: reservations.length,
      },
    }),
  );
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

// ── /reservations route renders the calendar (routing wiring, T-11 happy) ────
test.describe('J-6b reservations route + calendar shape (mock-auth inner loop)', () => {
  test('the /reservations route renders the Day/Week calendar', async ({ page }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    // The route wires to the calendar (the J-6b nav adds the Reservations entry;
    // its presence/role-gating is proven real-idp — see the file header).
    await expect(page.getByTestId('reservations-view-toggle')).toBeVisible();
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
  });
});

// ── Calendar Day/Week toggle + pager + label (T-08, items #2/#3) ─────────────
test.describe('J-6b reservations calendar hardening (mock-auth inner loop)', () => {
  test('Day/Week toggle renders the SELECTED button legibly (data-selected + dark-ground/light-text)', async ({
    page,
  }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    const toggle = page.getByTestId('reservations-view-toggle');
    await expect(toggle).toBeVisible();

    // Day is selected by default → carries the selected-style hook.
    const dayBtn = page.getByTestId('reservations-view-day');
    const weekBtn = page.getByTestId('reservations-view-week');
    await expect(dayBtn).toHaveAttribute('data-selected', 'true');
    await expect(weekBtn).toHaveAttribute('data-selected', 'false');

    // The selected button is LEGIBLE, not "blacked-out" (contrast inverted): the
    // design's selected style is a DARK ground with LIGHT text
    // (screens-reservations.jsx:106-110). The operator's #2 complaint was an
    // all-dark block (fg≈bg → illegible). Read the computed fg/bg, then round-trip
    // each through a throwaway DOM element's `backgroundColor` — the browser
    // serialises ANY computed `<color>` (oklch tokens included) to `rgb()` there,
    // so the `\d+` parse is reliable (a direct parse of `getComputedStyle` can see
    // an `oklch(...)` passthrough). Assert the selected ground is dark, its text
    // is light, the two have strong contrast, and selected stands out vs unselected.
    const lumOf = (el: ReturnType<Page['getByTestId']>, prop: 'backgroundColor' | 'color') =>
      el.evaluate((node, p) => {
        const raw = getComputedStyle(node as HTMLElement)[p as 'backgroundColor' | 'color'];
        const probe = document.createElement('span');
        probe.style.backgroundColor = raw;
        document.body.appendChild(probe);
        const rgb = getComputedStyle(probe).backgroundColor;
        probe.remove();
        const [r, g, b] = rgb.match(/\d+(\.\d+)?/g)!.map(Number);
        return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
      }, prop);

    const selBg = await lumOf(dayBtn, 'backgroundColor');
    const selFg = await lumOf(dayBtn, 'color');
    const unselBg = await lumOf(weekBtn, 'backgroundColor');
    expect(selBg, 'the SELECTED toggle ground is dark').toBeLessThan(96);
    expect(
      selFg,
      'the SELECTED toggle text is light (legible over the dark ground)',
    ).toBeGreaterThan(160);
    expect(
      selFg - selBg,
      'the selected toggle has strong fg↔bg contrast (not the blacked-out fg≈bg bug)',
    ).toBeGreaterThan(120);
    expect(
      unselBg - selBg,
      'the selected toggle ground is darker than the unselected one (selected stands out)',
    ).toBeGreaterThan(60);

    // CAPTURE-BEFORE-DEEP-ASSERT: the legible day-selected toggle.
    await page.screenshot({
      path: 'screenshots/reservations/06-toggle-day-selected.png',
      fullPage: true,
    });

    // Toggling flips the selected-style hook to the week button.
    await weekBtn.click();
    await expect(weekBtn).toHaveAttribute('data-selected', 'true');
    await expect(dayBtn).toHaveAttribute('data-selected', 'false');
  });

  test('DAY view pages by single days; the label is a single DD.MM.YYYY', async ({ page }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    const label = page.getByTestId('reservations-period-label');
    // Day-view label is the single day, DD.MM.YYYY (one date, no range dash).
    await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}$/);
    await expect(label).toHaveText(TODAY.ddmmyyyy);

    // The DAY pager (`-next-day`/`-prev-day`) exists in day view; it steps ±1 day
    // (NOT 7 — today's bug is `shiftWeek` always stepping 7). The label moves to
    // a NEW single day, then back.
    await expect(page.getByTestId('reservations-next-week')).toHaveCount(0);
    await page.getByTestId('reservations-next-day').click();
    await expect(label).not.toHaveText(TODAY.ddmmyyyy);
    // Single-day step: still a single DD.MM.YYYY label (not a week range).
    await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}$/);
    await page.getByTestId('reservations-prev-day').click();
    await expect(label).toHaveText(TODAY.ddmmyyyy);
  });

  test('WEEK view pages by weeks; the label is a DD.MM.YYYY – DD.MM.YYYY range', async ({
    page,
  }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    await page.getByTestId('reservations-view-week').click();
    await expect(page.getByTestId('reservations-week-grid')).toBeVisible();

    // Week-view label is a DD.MM.YYYY – DD.MM.YYYY range (start–end of the week).
    const label = page.getByTestId('reservations-period-label');
    await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}\s*[–-]\s*\d{2}\.\d{2}\.\d{4}$/);

    // CAPTURE-BEFORE-DEEP-ASSERT: the week-range label.
    await page.screenshot({
      path: 'screenshots/reservations/07-week-range-label.png',
      fullPage: true,
    });

    // The WEEK pager (`-next-week`/`-prev-week`) exists in week view; the DAY
    // pager does NOT. Stepping a whole week moves the range label.
    await expect(page.getByTestId('reservations-next-day')).toHaveCount(0);
    const before = (await label.textContent())?.trim() ?? '';
    await page.getByTestId('reservations-next-week').click();
    await expect(label).not.toHaveText(before);
    // Still a range (the week pager keeps a week range), and the move is exactly
    // one week — the start advances 7 days from the prior range start.
    await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}\s*[–-]\s*\d{2}\.\d{2}\.\d{4}$/);
    const after = (await label.textContent())?.trim() ?? '';
    expect(weekStartMs(after) - weekStartMs(before)).toBe(7 * 24 * 3600 * 1000);

    await page.getByTestId('reservations-prev-week').click();
    await expect(label).toHaveText(before);
  });
});

/** Parse the start `DD.MM.YYYY` of a `DD.MM.YYYY – DD.MM.YYYY` range label to ms. */
function weekStartMs(rangeLabel: string): number {
  const start = rangeLabel.split(/[–-]/)[0]!.trim();
  const [d, m, y] = start.split('.').map(Number);
  return new Date(y!, m! - 1, d!).getTime();
}
