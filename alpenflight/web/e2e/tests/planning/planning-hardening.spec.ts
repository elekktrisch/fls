import { expect, test, type Page } from '@playwright/test';

/**
 * Planning-day READ-ONLY / EDIT-MODE + cancel-return nav — J-6b INNER-LOOP spec
 * STUB (T-01).
 *
 * SPEC SKELETON ONLY. This task (J-6b T-01) commits the *screen shape* — the
 * `data-testid` selectors + the flow steps + THIN assertions — for the J-6
 * planning-edit hardening. The page-driving cases are `test.fixme` (parsed +
 * typechecked, NOT run); they un-fixme as T-09 (read-only + Edit toggle) and
 * T-10 (reservation Cancel honours a returnUrl) land the behaviour, and T-17
 * thickens to the full real assertions. The mock route wiring is authored so
 * un-fixme is a per-test flip, not a rewrite. Mirrors the J-6 inner-loop
 * discipline (`planning-crud.spec.ts`).
 *
 * Mock-auth fidelity (once un-fixme'd): the SYSTEM_ADMINISTRATOR +
 * CLUB_ADMINISTRATOR mock principal (so the edit affordances render) + every
 * `/api/v1/*` call intercepted via `page.route` — no live backend. The FULL real
 * chain is the gate's job; this spec pins the screen behaviour fast in the inner
 * loop.
 *
 * ── SCREEN SHAPE (from J-6b "Spec must assert" §Planning) ────────────────────
 *   READ-ONLY (T-09, items #10/#11) — opening a planning-day in VIEW mode
 *     (`/planning/:id/view`, the `:mode` route segment = `view`) renders EVERY
 *     field disabled/read-only — the
 *     assertion is that an input is non-editable, not merely that Save is absent.
 *     A read-only view exposes an `Edit` affordance (`planning-edit-toggle`) that
 *     flips the form to edit mode (fields editable, Save returns).
 *   CANCEL-RETURN (T-10, item #9) — from an EDIT planning-day, opening a
 *     reservation from the inline list (`<af-reservation-row>`) carries a
 *     `returnUrl=/planning/:id/edit`; the reservation-edit `Cancel`
 *     (`reservation-edit-cancel`) honours it and returns to the planning-day edit
 *     form — NOT `/reservations` (today the cancel handler hardcodes /reservations).
 *
 * Selectors reused from J-6 (`planning-crud.spec.ts`):
 *   `planning-edit-form`, `planning-date`, `planning-location-select`,
 *   `planning-reservations-panel`, `planning-reservation-<id>`,
 *   `planning-reservation-edit-<id>`, `planning-save-button`.
 * NEW for J-6b (land with the behaviour; named here so the shape is committed):
 *   `planning-edit-toggle`        (the read-only → edit affordance, T-09),
 *   `reservation-edit-cancel`     (the reservation-edit Cancel button, T-10).
 */

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const LOCATION_BERN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
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

const mockLocations = [{ id: LOCATION_BERN_ID, locationName: 'Bern-Belp', isAirfield: true }];

/** Wire the planning-edit read endpoints the planning store loads on `:id`. */
async function wirePlanningDay(page: Page): Promise<void> {
  await page.route('**/api/v1/locations**', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: [] }));
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
  test.fixme('read-only view renders ALL fields disabled (not just Save hidden)', async ({
    page,
  }) => {
    await wirePlanningDay(page);
    await gotoDe(page, `/planning/${SEED_DAY_ID}/view`);

    await expect(page.getByTestId('planning-edit-form')).toBeVisible();
    // An input is genuinely non-editable — the assertion is the field state, NOT
    // merely the Save button absence (the operator's #10 complaint).
    await expect(page.getByTestId('planning-date').locator('input')).toBeDisabled();
    await expect(page.getByTestId('planning-save-button')).toHaveCount(0);

    await page.screenshot({ path: 'screenshots/planning/05-readonly.png', fullPage: true });
  });

  test.fixme('Edit affordance flips read-only → edit mode (fields editable, Save returns)', async ({
    page,
  }) => {
    await wirePlanningDay(page);
    await gotoDe(page, `/planning/${SEED_DAY_ID}/view`);

    await page.getByTestId('planning-edit-toggle').locator('button').click();

    // Now editable + Save returns.
    await expect(page.getByTestId('planning-date').locator('input')).toBeEnabled();
    await expect(page.getByTestId('planning-save-button')).toBeVisible();
  });
});

// ── Reservation Cancel returns to the planning day (T-10, item #9) ───────────
test.describe('J-6b reservation Cancel returns to planning (mock-auth inner loop)', () => {
  test.fixme('edit planning-day → open inline reservation → Cancel returns to planning-day edit', async ({
    page,
  }) => {
    await wirePlanningDay(page);
    await page.route(`**/api/v1/aircraft-reservations/${SEED_DAY_RESERVATION_ID}`, (route) =>
      route.fulfill({ json: { id: SEED_DAY_RESERVATION_ID, operatingClubId: CLUB_A_ID } }),
    );
    await gotoDe(page, `/planning/${SEED_DAY_ID}/edit`);

    // Open a reservation from the inline list — the row's [openLink] carries a
    // returnUrl=/planning/:id/edit (T-10 call-site).
    const panel = page.getByTestId('planning-reservations-panel');
    await panel.getByTestId(`planning-reservation-edit-${SEED_DAY_RESERVATION_ID}`).click();
    await expect(page).toHaveURL(/returnUrl=/);

    // Cancel honours the returnUrl → back to the planning-day EDIT form
    // (NOT /reservations, today's hardcoded target).
    await page.getByTestId('reservation-edit-cancel').locator('button').click();
    await expect(page).toHaveURL(new RegExp(`/planning/${SEED_DAY_ID}/edit`));
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();
  });
});
