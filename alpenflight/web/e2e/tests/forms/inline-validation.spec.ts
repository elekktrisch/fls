import { expect, test, type Locator, type Page } from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';

/**
 * Inline form validation WHILE TYPING (debounced ~200ms) — J-6b INNER-LOOP spec
 * (T-17, thickened from the T-01 stub to full real assertions). This is the
 * journey's ≥60% FEATURE.
 *
 * Mock-auth fidelity: the SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR mock
 * principal + every `/api/v1/*` call intercepted via `page.route` — no live
 * backend. The FULL real chain is the gate's job
 * (`tests/real-idp/reservations-planning-hardening.spec.ts`); this spec pins the
 * debounced inline-validation behaviour fast in the inner loop.
 *
 * ── SCREEN SHAPE (J-6b "Spec must assert" §Inline form validation) ───────────
 *   The representative edit forms (PLANNING-DAY — Date + Location required; and
 *   the RESERVATION edit — overlap async-validate) show per-field error messages
 *   WHILE the user types, debounced ~200ms, rendered inline under the field via
 *   `<af-field-errors>` (a `role="alert"` line per error). The debounce is the
 *   KEY behavioural difference from the pre-J-6b touched-only wiring — the
 *   message appears on a debounced keystroke (no blur), and clears (debounced)
 *   when the value becomes valid.
 *     - TRIVIAL rule (required) validates CLIENT-side from the reactive form
 *       validators (T-03).
 *     - NON-TRIVIAL rule (planning-day (club,date,location) uniqueness /
 *       reservation aircraft-slot overlap) calls a server `…/validate` endpoint
 *       (T-05 / T-04) and surfaces its message inline on the offending field
 *       WITHOUT a save round-trip — pre-checking the EXISTING J-6/J-5 constraint
 *       (NO new rule; oracle §Form validation).
 *
 * The server validate ENVELOPE is the real orval contract `{ valid, field?,
 * message? }` (model/planningDayValidationResult.ts + reservationValidationResult.ts)
 * — NOT a `{ valid, errors }` shape. The store maps `valid:false` →
 * `uniquenessMessage`/`overlapMessage` (non-null) → the dedicated server-error
 * span renders + submit is blocked.
 */

const LOCATION_BERN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
const AC_ID = '019e30c3-2c00-7001-8000-00000000a001';
const PILOT_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const TYPE_FLIGHT_ID = '019e30c3-2c00-7001-8000-0000000000d1';

/** `YYYY-MM-DD` for a future planning day (the create-form default). */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const mockLocations = [{ id: LOCATION_BERN_ID, locationName: 'Bern-Belp', isAirfield: true }];
const mockAircraft = [
  { id: AC_ID, immatriculation: 'HB-3210', isTowingAircraft: false, nrOfSeats: 1 },
];
const mockPersons = [{ id: PILOT_ID, firstname: 'Petra', lastname: 'Pilot', isActive: true }];
const mockTypes = [{ id: TYPE_FLIGHT_ID, name: 'Flight', active: true, instructorRequired: false }];

/**
 * Wire the planning-edit read endpoints + the planning-day `…/validate` endpoint
 * (T-05). `conflict` toggles whether the server validate reports a
 * (club,date,location) uniqueness clash. The envelope is the REAL orval contract
 * `{ valid, field?, message? }`.
 */
async function wirePlanningCreate(page: Page, { conflict }: { conflict: boolean }): Promise<void> {
  await page.route('**/api/v1/locations', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/persons', (route) => route.fulfill({ json: mockPersons }));
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: mockTypes }),
  );
  // No reservations on this (date, location) → the inline panel stays empty.
  await page.route('**/api/v1/aircraft-reservations/day/**', (route) =>
    route.fulfill({ json: [] }),
  );
  // The non-mutating validate path (T-05) — the EXISTING J-6 uniqueness
  // constraint, surfaced inline before a full save. REAL envelope.
  await page.route('**/api/v1/planning-days/validate', (route) =>
    route.fulfill({
      json: conflict
        ? { valid: false, field: 'planningDate', message: 'planning.day.duplicate' }
        : { valid: true },
    }),
  );
}

/** Wire the reservation-edit read endpoints + the overlap `…/validate` (T-04). */
async function wireReservationCreate(
  page: Page,
  { conflict }: { conflict: boolean },
): Promise<void> {
  await page.route('**/api/v1/locations', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/persons', (route) => route.fulfill({ json: mockPersons }));
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: mockTypes }),
  );
  await page.route('**/api/v1/aircraft-reservations/validate', (route) =>
    route.fulfill({
      json: conflict
        ? { valid: false, field: 'start', message: 'aircraft.reservation.overlap' }
        : { valid: true },
    }),
  );
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

/**
 * The inline client-error region under the planning-date field (an
 * af-field-errors alert). The `planning-date` testid is on the `<af-input>`; the
 * `<af-field-errors>` is its sibling inside the wrapping `<af-form-field>`, so
 * scope to that ancestor.
 */
function dateFieldErrors(page: Page): Locator {
  return page
    .locator('af-form-field', { has: page.getByTestId('planning-date') })
    .getByRole('alert');
}

test.describe('J-6b inline validation while typing — planning-day (mock-auth inner loop)', () => {
  test('a client-required field shows its error on debounced keystroke (no blur)', async ({
    page,
  }) => {
    await wirePlanningCreate(page, { conflict: false });
    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    const date = page.getByTestId('planning-date').locator('input');
    // Type then clear WITHOUT blurring — the required error appears after the
    // ~200ms debounce (pre-J-6b it would only show on blur/submit).
    await date.fill(dayKeyFromToday(3));
    await date.fill('');
    await expect(dateFieldErrors(page)).toBeVisible();

    await page.screenshot({
      path: 'screenshots/forms/01-inline-required-while-typing.png',
      fullPage: true,
    });
  });

  test('the inline error clears (debounced) when the value becomes valid', async ({ page }) => {
    await wirePlanningCreate(page, { conflict: false });
    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    const date = page.getByTestId('planning-date').locator('input');
    await date.fill('');
    await expect(dateFieldErrors(page)).toBeVisible();
    // Re-entering a valid value clears the message after the debounce.
    await date.fill(dayKeyFromToday(3));
    await expect(dateFieldErrors(page)).toHaveCount(0);
  });

  test('a non-trivial rule is server-validated; its message surfaces inline without a full submit', async ({
    page,
  }) => {
    await wirePlanningCreate(page, { conflict: true });
    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    // The async (date, location) uniqueness validate fires only when BOTH date +
    // location are set (the store keys on the pair). Pick a date + the location
    // that, together, duplicate an existing day → the async validate (T-05)
    // reports it inline on the date field — WITHOUT a save round-trip.
    await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(3));
    await selectAfOption(page, 'planning-location-select', LOCATION_BERN_ID);

    await expect(page.getByTestId('planning-date-server-error')).toBeVisible();
    // The duplicate blocks submit (the save-path 409 is the backstop, but the
    // inline pre-check disables Save earlier). `toBeDisabled` targets the native
    // <button> inside the `af-button` host (the host carries no disabled attr).
    await expect(page.getByTestId('planning-save-button').locator('button')).toBeDisabled();

    await page.screenshot({
      path: 'screenshots/forms/02-inline-server-validate.png',
      fullPage: true,
    });
  });

  test('a client-failing field blocks submit AND shows its inline message', async ({ page }) => {
    await wirePlanningCreate(page, { conflict: false });
    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    const date = page.getByTestId('planning-date').locator('input');
    await date.fill('');
    // A required field empty → Save is blocked + the inline message shows.
    await expect(page.getByTestId('planning-save-button').locator('button')).toBeDisabled();
    await expect(dateFieldErrors(page)).toBeVisible();
  });
});

test.describe('J-6b inline validation while typing — reservation overlap (mock-auth inner loop)', () => {
  test('the reservation overlap pre-check surfaces inline + blocks submit (server-validate)', async ({
    page,
  }) => {
    await wireReservationCreate(page, { conflict: true });
    await gotoDe(page, '/reservations/new');
    await expect(page.getByTestId('reservation-edit-form')).toBeVisible();

    // Fill the slot (aircraft + a timed window) so the overlap validate fires;
    // the mocked validate reports a conflict → the inline overlap message
    // surfaces on the slot WITHOUT a save, and Save is blocked.
    await selectAfOption(page, 'reservation-aircraft-select', AC_ID);
    await selectAfOption(page, 'reservation-type-select', TYPE_FLIGHT_ID);
    await selectAfOption(page, 'reservation-pilot-select', PILOT_ID);
    await selectAfOption(page, 'reservation-location-select', LOCATION_BERN_ID);
    await page.getByTestId('reservation-date').locator('input').fill(dayKeyFromToday(3));
    await page.getByTestId('reservation-start-time').locator('input').fill('10:30');
    await page.getByTestId('reservation-end-time').locator('input').fill('11:30');

    await expect(page.getByTestId('reservation-start-server-error')).toBeVisible();
    await expect(page.getByTestId('reservation-save-button').locator('button')).toBeDisabled();

    await page.screenshot({
      path: 'screenshots/forms/03-reservation-overlap-validate.png',
      fullPage: true,
    });
  });

  test('the overlap message clears when the slot no longer conflicts (valid)', async ({ page }) => {
    await wireReservationCreate(page, { conflict: false });
    await gotoDe(page, '/reservations/new');
    await expect(page.getByTestId('reservation-edit-form')).toBeVisible();

    await selectAfOption(page, 'reservation-aircraft-select', AC_ID);
    await selectAfOption(page, 'reservation-type-select', TYPE_FLIGHT_ID);
    await selectAfOption(page, 'reservation-pilot-select', PILOT_ID);
    await selectAfOption(page, 'reservation-location-select', LOCATION_BERN_ID);
    await page.getByTestId('reservation-date').locator('input').fill(dayKeyFromToday(3));
    await page.getByTestId('reservation-start-time').locator('input').fill('10:30');
    await page.getByTestId('reservation-end-time').locator('input').fill('11:30');

    // A non-conflicting slot → no overlap server-error span, Save is enabled.
    await expect(page.getByTestId('reservation-start-server-error')).toHaveCount(0);
    await expect(page.getByTestId('reservation-save-button').locator('button')).toBeEnabled();
  });
});
