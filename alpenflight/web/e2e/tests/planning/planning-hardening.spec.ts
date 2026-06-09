import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * Planning-day READ-ONLY / EDIT-MODE + reservation cancel-return nav — J-6b
 * INNER-LOOP spec (T-17, thickened from the T-01 stub to full real assertions).
 *
 * Mock-auth fidelity: the SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR mock
 * principal (so the edit affordances render) + every `/api/v1/*` call
 * intercepted via `page.route` — no live backend. The FULL real chain is the
 * gate's job (`tests/real-idp/reservations-planning-hardening.spec.ts`); this
 * spec pins the screen behaviour fast in the inner loop.
 *
 * ── SCREEN SHAPE (J-6b "Spec must assert" §Planning) ─────────────────────────
 *   READ-ONLY (T-09, items #10/#11) — `/planning/:id/view` renders EVERY field
 *     disabled (the assertion is the field state, not merely Save absence — the
 *     operator's #10 complaint), and exposes an `Edit` affordance
 *     (`planning-edit-toggle`) that flips to `/planning/:id/edit` (fields
 *     editable, Save returns).
 *   CANCEL-RETURN (T-10, item #9) — from an EDIT planning-day, the inline
 *     reservation row's open-link carries `returnUrl=/planning/:id/edit`; the
 *     reservation-edit `Cancel` (`reservation-edit-cancel`) honours it and
 *     returns to the planning-day edit form — NOT `/reservations`.
 */

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const LOCATION_BERN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
const AC_ID = '019e30c3-2c00-7001-8000-00000000a001';
const SEED_DAY_ID = '019e30c3-2c00-7001-8000-000000000e01';
const SEED_DAY_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000f01';

/** `YYYY-MM-DD` for a future planning day. */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const SEED_DAY_KEY = dayKeyFromToday(4);

const seedDay = {
  id: SEED_DAY_ID,
  operatingClubId: CLUB_A_ID,
  planningDate: SEED_DAY_KEY,
  locationId: LOCATION_BERN_ID,
  info: 'Soaring day',
  numberOfAircraftReservations: 1,
  canUpdateRecord: true,
  canDeleteRecord: true,
};

/** The inline per-day reservation the panel lists (the J-5 read-side join shape). */
const dayReservation = {
  id: SEED_DAY_RESERVATION_ID,
  aircraftId: AC_ID,
  start: `${SEED_DAY_KEY}T10:00:00Z`,
  end: `${SEED_DAY_KEY}T12:00:00Z`,
  isAllDay: false,
  pilotPersonId: '019e30c3-2c00-7001-8000-0000000000b1',
  locationId: LOCATION_BERN_ID,
  reservationTypeId: '019e30c3-2c00-7001-8000-0000000000d1',
  reservationTypeName: 'Flight',
};

const mockLocations = [{ id: LOCATION_BERN_ID, locationName: 'Bern-Belp', isAirfield: true }];
const mockAircraft = [{ id: AC_ID, immatriculation: 'HB-3210', isTowingAircraft: false }];

/** Wire the planning-edit read endpoints + the inline per-day reservations join. */
async function wirePlanningDay(page: Page): Promise<void> {
  await page.route('**/api/v1/locations', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/persons', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: [] }),
  );
  // The (date, location) inline join the panel loads (T-08c).
  await page.route('**/api/v1/aircraft-reservations/day/**', (route) =>
    route.fulfill({ json: [dayReservation] }),
  );
  await page.route(`**/api/v1/planning-days/${SEED_DAY_ID}`, (route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({ json: seedDay });
      return;
    }
    route.fallback();
  });
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

// ── Read-only + Edit toggle (T-09, items #10/#11) ────────────────────────────
test.describe('J-6b planning read-only + Edit toggle (mock-auth inner loop)', () => {
  test('read-only view renders ALL fields disabled (not just Save hidden)', async ({ page }) => {
    await wirePlanningDay(page);
    await gotoDe(page, `/planning/${SEED_DAY_ID}/view`);

    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    // CAPTURE-BEFORE-DEEP-ASSERT: the read-only day (a partial red still shoots).
    await page.screenshot({ path: 'screenshots/planning/05-readonly.png', fullPage: true });

    // EVERY field is genuinely non-editable — the assertion is the field state
    // (the operator's #10: form looked editable though `form.disable()` ran; the
    // CVA `setDisabledState` no-op was the bug T-09 fixed). Assert the date input,
    // the remarks input AND a select are all disabled, not merely Save absence.
    await expect(page.getByTestId('planning-date').locator('input')).toBeDisabled();
    await expect(page.getByTestId('planning-remarks').locator('input')).toBeDisabled();
    await expect(
      page.getByTestId('planning-location-select').locator('nz-select'),
      'the location select renders disabled in read-only mode',
    ).toHaveClass(/ant-select-disabled/);
    // Save is gone in view mode (the operator saw ONLY this before — necessary
    // but not sufficient; the fields above are the real read-only proof).
    await expect(page.getByTestId('planning-save-button')).toHaveCount(0);
  });

  test('Edit affordance flips read-only → edit mode (fields editable, Save returns)', async ({
    page,
  }) => {
    await wirePlanningDay(page);
    await gotoDe(page, `/planning/${SEED_DAY_ID}/view`);

    // The read-only view exposes an Edit button; clicking it routes to /edit.
    const editToggle = page.getByTestId('planning-edit-toggle');
    await expect(editToggle).toBeVisible();
    await editToggle.locator('button').click();
    await expect(page).toHaveURL(new RegExp(`/planning/${SEED_DAY_ID}/edit`));

    // Now editable (the disable effect re-enables on the route flip) + Save returns.
    await expect(page.getByTestId('planning-date').locator('input')).toBeEnabled();
    await expect(page.getByTestId('planning-remarks').locator('input')).toBeEnabled();
    await expect(page.getByTestId('planning-save-button')).toBeVisible();
    // The Edit toggle is gone in edit mode (it is view-only).
    await expect(page.getByTestId('planning-edit-toggle')).toHaveCount(0);
  });
});

// ── Reservation Cancel returns to the planning day (T-10, item #9) ───────────
test.describe('J-6b reservation Cancel returns to planning (mock-auth inner loop)', () => {
  test('edit planning-day → open inline reservation → Cancel returns to the planning-day edit form', async ({
    page,
  }) => {
    await wirePlanningDay(page);
    // The reservation-edit form loads the opened reservation's detail (GET /{id}).
    await page.route(
      `**/api/v1/aircraft-reservations/${SEED_DAY_RESERVATION_ID}`,
      (route: Route) => {
        if (route.request().method() === 'GET') {
          route.fulfill({
            json: {
              id: SEED_DAY_RESERVATION_ID,
              operatingClubId: CLUB_A_ID,
              aircraftId: AC_ID,
              pilotPersonId: dayReservation.pilotPersonId,
              locationId: LOCATION_BERN_ID,
              reservationTypeId: dayReservation.reservationTypeId,
              start: dayReservation.start,
              end: dayReservation.end,
              isAllDay: false,
            },
          });
          return;
        }
        route.fallback();
      },
    );

    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    // The inline per-day reservations panel lists the day's reservation; its open
    // link carries returnUrl=/planning/:id/edit (T-10 call-site).
    const panel = page.getByTestId('planning-reservations-panel');
    await expect(panel).toBeVisible();
    const openLink = panel.getByTestId(`planning-reservation-edit-${SEED_DAY_RESERVATION_ID}`);
    await expect(openLink).toHaveAttribute(
      'href',
      `/reservations/${SEED_DAY_RESERVATION_ID}/edit?returnUrl=${encodeURIComponent(
        `/planning/${SEED_DAY_ID}/edit`,
      )}`,
    );
    await openLink.click();

    // We are on the reservation editor, carrying the returnUrl query.
    await expect(page).toHaveURL(
      new RegExp(`/reservations/${SEED_DAY_RESERVATION_ID}/edit\\?returnUrl=`),
    );
    await expect(page.getByTestId('reservation-edit-form')).toBeVisible();

    // Cancel honours the returnUrl → back to the planning-day EDIT form
    // (NOT /reservations, the pre-T-10 hardcoded target).
    await page.getByTestId('reservation-edit-cancel').locator('button').click();
    await expect(page).toHaveURL(new RegExp(`/planning/${SEED_DAY_ID}/edit`));
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();
  });
});
