import { expect, test, type Page } from '@playwright/test';

/**
 * Inline form validation WHILE TYPING (debounced ~200ms) — J-6b INNER-LOOP spec
 * STUB (T-01). This is the journey's ≥60% FEATURE.
 *
 * SPEC SKELETON ONLY. This task (J-6b T-01) commits the *screen shape* — the
 * `data-testid` selectors + the flow steps + THIN assertions — for the new
 * inline-validation-while-typing capability on the shared edit forms. The
 * page-driving cases are `test.fixme` (parsed + typechecked, NOT run); they
 * un-fixme as T-03 (shared `<af-field-errors>` + debounce util), T-04/T-05 (the
 * `…/validate` endpoints), and T-06/T-07 (the reservation- + planning-edit forms
 * adopt the directive) land the behaviour, and T-17 thickens to the full real
 * assertions for the 14 ACs. The mock route wiring is authored so un-fixme is a
 * per-test flip, not a rewrite.
 *
 * Mock-auth fidelity (once un-fixme'd): the SYSTEM_ADMINISTRATOR +
 * CLUB_ADMINISTRATOR mock principal + every `/api/v1/*` call intercepted via
 * `page.route` — no live backend. The FULL real chain is the gate's job; this
 * spec pins the screen behaviour fast in the inner loop.
 *
 * ── SCREEN SHAPE (from J-6b "Spec must assert" §Inline form validation) ───────
 *   A representative edit form (the PLANNING-DAY edit, the simpler of the two —
 *   Date + Location required) shows per-field error messages WHILE the user types,
 *   debounced ~200ms, rendered inline under the field via the existing
 *   `<af-field-errors>` molecule (one `role="alert"` line per error). The
 *   debounce is the KEY behavioural difference from today: today the page wires
 *   field errors on `touched` (blur/submit only — `[errors]="ctl.touched ? …"`),
 *   so the message only appears AFTER blur. The spec asserts the message appears
 *   on a debounced keystroke (no blur), and clears (debounced) when the value
 *   becomes valid.
 *     - TRIVIAL rule (required/format) validates CLIENT-side from the reactive
 *       form validators (T-03).
 *     - NON-TRIVIAL rule (planning-day (club,date,location) uniqueness) calls the
 *       server `…/validate` endpoint (T-05) and surfaces its message inline on the
 *       offending field WITHOUT a full save round-trip — pre-checking the EXISTING
 *       J-6 `ux_pln_club_date_loc` constraint (NO new rule; oracle §Form validation).
 *
 * Selectors (J-6 planning-edit, reused + extended):
 *   `planning-edit-form`, `planning-date`, `planning-location-select`.
 * NEW for J-6b (land with the behaviour; named here so the shape is committed):
 *   `planning-date` field-error region          → `af-field-errors` `[role=alert]`,
 *   `planning-date-validating`                   (the async-validate pending hint, T-05),
 *   `planning-date-server-error`                 (the server validate message line, T-05).
 *
 * NOTE — the REPRESENTATIVE form is the planning-day edit; the reservation-edit
 * form (Date/Type/Pilot/Aircraft/Location required + overlap-validate, T-06) gets
 * the same treatment and its own assertions at T-17 (its overlap async-validate
 * uses the T-04 endpoint). One representative form keeps this stub one-seam.
 */

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const LOCATION_BERN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';

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

/**
 * Wire the planning-edit read endpoints + the NEW planning-day `…/validate`
 * endpoint (T-05). `conflict` toggles whether the server validate reports a
 * (club,date,location) uniqueness clash for the async-validate path.
 */
async function wirePlanningCreate(page: Page, { conflict }: { conflict: boolean }): Promise<void> {
  await page.route('**/api/v1/locations**', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: [] }));
  // The non-mutating validate path (T-05) — field-level result for the EXISTING
  // J-6 uniqueness constraint, surfaced inline before a full save.
  await page.route('**/api/v1/planning-days/validate', (route) =>
    route.fulfill({
      json: conflict
        ? { valid: false, errors: { planningDate: 'planning.validate.duplicateDay' } }
        : { valid: true, errors: {} },
    }),
  );
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

/** The inline error region under the planning-date field (an af-field-errors alert). */
function dateFieldErrors(page: Page) {
  return page.getByTestId('planning-date').locator('xpath=..').getByRole('alert');
}

test.describe('J-6b inline validation while typing (mock-auth inner loop)', () => {
  test.fixme('a client-required field shows its error on debounced keystroke (no blur)', async ({
    page,
  }) => {
    await wirePlanningCreate(page, { conflict: false });
    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    const date = page.getByTestId('planning-date').locator('input');
    // Type then clear WITHOUT blurring — the required error appears after the
    // ~200ms debounce (today it would only show on blur/submit).
    await date.fill(dayKeyFromToday(3));
    await date.fill('');
    await expect(dateFieldErrors(page)).toBeVisible();

    await page.screenshot({
      path: 'screenshots/forms/01-inline-required-while-typing.png',
      fullPage: true,
    });
  });

  test.fixme('the inline error clears (debounced) when the value becomes valid', async ({
    page,
  }) => {
    await wirePlanningCreate(page, { conflict: false });
    await gotoDe(page, '/planning/new/edit');

    const date = page.getByTestId('planning-date').locator('input');
    await date.fill('');
    await expect(dateFieldErrors(page)).toBeVisible();
    // Re-entering a valid value clears the message after the debounce.
    await date.fill(dayKeyFromToday(3));
    await expect(dateFieldErrors(page)).toHaveCount(0);
  });

  test.fixme('a non-trivial rule is server-validated; its message surfaces inline without a full submit', async ({
    page,
  }) => {
    await wirePlanningCreate(page, { conflict: true });
    await gotoDe(page, '/planning/new/edit');

    const date = page.getByTestId('planning-date').locator('input');
    // Picking a (date) that, with the chosen location, duplicates an existing
    // day → the async validate (T-05) reports it inline on the date field —
    // WITHOUT a save round-trip (pre-checking the EXISTING J-6 uniqueness).
    await date.fill(dayKeyFromToday(3));
    await expect(page.getByTestId('planning-date-server-error')).toBeVisible();
    await expect(page.getByTestId('planning-save-button')).toBeDisabled();

    await page.screenshot({
      path: 'screenshots/forms/02-inline-server-validate.png',
      fullPage: true,
    });
  });

  test.fixme('a client-failing field blocks submit', async ({ page }) => {
    await wirePlanningCreate(page, { conflict: false });
    await gotoDe(page, '/planning/new/edit');

    const date = page.getByTestId('planning-date').locator('input');
    await date.fill('');
    // A required field empty → Save is blocked + the inline message shows.
    await expect(page.getByTestId('planning-save-button')).toBeDisabled();
    await expect(dateFieldErrors(page)).toBeVisible();
  });
});
