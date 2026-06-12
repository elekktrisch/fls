import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * J-26 VALIDATION HARDENING — mock inner-loop spec (T-01 STUB).
 *
 * THIN STUB: every case below is `test.fixme` — the structure, chrome-entry
 * flow, and selectors are authored here so each fix task (T-05..T-13) un-fixmes
 * its case as the behavior lands; T-27 thickens all of them to full real
 * assertions. The cases LIST (`--list`) + compile, but never run red on the
 * per-push gate while the fixes are in flight.
 *
 * Mock-auth fidelity: the dev server boots under `--configuration=mock-auth`
 * (synthetic SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR principal); every
 * `/api/v1/*` call is intercepted via `page.route` — no live backend. The full
 * real chain (real constraint → 409 → inline) is the real-idp sibling's job
 * (`tests/real-idp/hardening-J26.spec.ts`, the journey's `parity_test`).
 *
 * ── CHROME ENTRY (J-26 "Spec must assert" / do-ship done-bar) ─────────────────
 * Every case ENTERS through the nav chrome — `af-nav-section-<path>` → list →
 * form — never a bare `page.goto` to the form. Two PREREQUISITES the stub
 * surfaces (must land before these cases un-fixme; flagged to the manager in
 * the T-01 report):
 *   1. The MOCK principal carries both roles and `navSectionsFor` SHORT-CIRCUITS
 *      on `isSystemAdmin` → the mock nav renders Clubs ONLY (src/app/
 *      nav-sections.ts:44-53; the J-6b precedent documented in
 *      tests/reservations/reservations-hardening.spec.ts). For the tenant
 *      sections to be chrome-enterable under mock-auth, the dual-role principal
 *      must see the UNION of sections (sysadmin + tenant + club-admin).
 *   2. `/flight-types` has NO nav section at all (URL-only screen — exactly the
 *      J-7 /flightreports hollow-screen miss the done-bar names). A
 *      `af-nav-section-/flight-types` entry must be added.
 *
 * ── PLANNED CASES (J-26 "Spec must assert" §forms/validation-hardening) ──────
 *   [key-error] duplicate FlightCode → inline 409 ON the flightCode field (T-05)
 *   [key-error] Instructor × Observer XOR → blocked client-side (T-06)
 *   [key-error] duplicate clubKey → 409 labeled on the clubKey field (T-07)
 *   [happy]     af-field-errors renders TRANSLATED text, never a raw
 *               `common.errors.*` key (T-08) + profile languageId required (T-08)
 *   [happy]     as-you-type debounced (~200ms) trio: aircraft (T-10),
 *               person (T-11), flight-type (T-11)
 *   [edge]      flight edit: Save gated on client required validators (T-13)
 *   [edge]      reservation Save disable state tracks form validity across the
 *               async second-crew validator (T-09)
 *
 * Grounded in docs/modernization/form-validation-parity-audit.md (legacy =
 * minimum bar, operator 2026-06-09). Debounce/inline conventions follow the
 * J-6b reference spec (tests/forms/inline-validation.spec.ts).
 */

// ── ids (UUIDv7-shaped, matching the J-5/J-6b mock fixture convention) ────────
const CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';
const FLIGHT_TYPE_ID = '019e30c3-2c00-7001-8000-0000000000f1';
const PERSON_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const AIRCRAFT_ID = '019e30c3-2c00-7001-8000-00000000a001';

const mockFlightTypes = [
  {
    id: FLIGHT_TYPE_ID,
    flightTypeName: 'Schulflug',
    flightCode: 'S',
    isForGliderFlights: true,
    isInstructorRequired: false,
    isObserverPilotOrInstructorRequired: false,
  },
];
const mockClubs = [{ id: CLUB_ID, name: 'Seed Club', clubKey: 'SC1', slug: 'seed-club' }];
const mockPersons = [
  { id: PERSON_ID, firstname: 'Petra', lastname: 'Pilot', memberNumber: '100', isActive: true },
];
const mockAircraft = [
  { id: AIRCRAFT_ID, immatriculation: 'HB-3210', isTowingAircraft: false, nrOfSeats: 1 },
];

/**
 * CHROME ENTRY — land on the app shell, then click the nav section the way an
 * operator does. NEVER `page.goto` straight to a form (do-ship done-bar; J-26
 * "Spec must assert"). The `?lang=de` pin keeps message assertions
 * locale-stable (J-6b convention).
 */
async function enterSection(page: Page, sectionPath: string): Promise<void> {
  await page.goto('/start?lang=de');
  const section = page.getByTestId(`af-nav-section-${sectionPath}`);
  await expect(section, `the ${sectionPath} nav section is chrome-reachable`).toBeVisible();
  await section.click();
}

/**
 * The inline error region under one form field — the `<af-field-errors>` alert
 * scoped to the `<af-form-field>` wrapping the target control (the J-6b
 * `dateFieldErrors` pattern; survives testid-vs-inputId implementation detail).
 */
function fieldErrors(page: Page, controlLocator: Locator): Locator {
  return page.locator('af-form-field', { has: controlLocator }).getByRole('alert');
}

// ═════════════════════════════════════════════════════════════════════════════
// Inline 409 FIELD-ROUTING — the duplicate-key fixes (T-05 / T-07).
// ═════════════════════════════════════════════════════════════════════════════
test.describe('J-26 duplicate-key 409 routes to the offending field (mock inner loop)', () => {
  // Error-path logic, not wiring — the server 409 envelope is covered by
  // FlightTypeDuplicateCodeIT; this case asserts the CLIENT field-routing.
  // covered-by: FlightTypeDuplicateCodeIT
  test(
    '[key-error] flight-type duplicate FlightCode → inline 409 on the flightCode field, not a raw 500',
    { tag: '@helper' },
    async ({ page }) => {
      // T-05: backend DIVE discrimination (`ux_flight_type_club_code` →
      // 409 field=flightCode) + store 409 field-routing (name vs code no longer
      // collapse onto flightTypeName). Mock shape: the POST answers 409 with the
      // problem-detail `field: 'flightCode'`.
      await page.route('**/api/v1/flight-types', (route) =>
        route.request().method() === 'POST'
          ? route.fulfill({
              status: 409,
              json: { field: 'flightCode', message: 'common.errors.duplicate' },
            })
          : route.fulfill({ json: mockFlightTypes }),
      );

      // Chrome entry: nav section (PREREQ 2 — `/flight-types` nav entry) → list →
      // New → form.
      await enterSection(page, '/flight-types');
      await page.getByTestId('flight-types-new-button').click();
      await expect(page.getByTestId('flight-types-edit-form')).toBeVisible();

      await page.locator('#FlightTypeName').fill('Duplikat');
      await page.locator('#FlightCode').fill('S'); // duplicates the seeded code
      await page.getByTestId('flight-types-save-button').click();

      // The 409 lands INLINE on the Code field — not on flightTypeName, not a
      // raw 500 toast (the legacy-reproducing bug this fixes).
      await expect(fieldErrors(page, page.locator('#FlightCode'))).toBeVisible();
      await expect(fieldErrors(page, page.locator('#FlightTypeName'))).toHaveCount(0);

      await page.screenshot({
        path: 'screenshots/forms/10-duplicate-flightcode-409.png',
        fullPage: true,
      });
    },
  );

  test.fixme('[key-error] club duplicate clubKey → 409 labeled on the clubKey field (not the slug)', async ({
    page,
  }) => {
    // T-07 thickens: ClubsService DIVE discrimination `ux_club_key` vs
    // `ux_club_slug` (today ANY DataIntegrityViolation → SlugAlreadyExists on
    // the wrong field).
    await page.route('**/api/v1/clubs', (route) => route.fulfill({ json: mockClubs }));
    await page.route(`**/api/v1/clubs/${CLUB_ID}`, (route) =>
      route.request().method() === 'PUT'
        ? route.fulfill({
            status: 409,
            json: { field: 'clubKey', message: 'common.errors.duplicate' },
          })
        : route.fulfill({ json: mockClubs[0] }),
    );

    // Chrome entry: the Clubs section IS in the mock (sysadmin-branch) nav.
    await enterSection(page, '/clubs');
    await page.getByTestId('clubs-table').getByRole('row').nth(1).click();
    await expect(page.getByTestId('clubs-edit-form')).toBeVisible();

    await page.locator('#clubKey').fill('DUP');
    await page.getByTestId('clubs-save-button').click();

    // 409 on the clubKey field — NOT the slug field's error.
    await expect(fieldErrors(page, page.locator('#clubKey'))).toBeVisible();
    await expect(fieldErrors(page, page.locator('#clubSlug'))).toHaveCount(0);
  });
});

// ═════════════════════════════════════════════════════════════════════════════
// CROSS-FIELD + TRANSLATED ERRORS (T-06 / T-08).
// ═════════════════════════════════════════════════════════════════════════════
test.describe('J-26 cross-field validator + translated messages (mock inner loop)', () => {
  // Error-path logic, not wiring — the cross-field rule itself is covered by
  // instructor-observer-exclusive.validator.spec.ts (client) and
  // FlightTypeDomainTest (the domain guard in FlightType.updateFlags).
  // covered-by: FlightTypeDomainTest
  test(
    '[key-error] flight-type Instructor × Observer both set → blocked by the client cross-field validator',
    { tag: '@helper' },
    async ({ page }) => {
      // T-06: the group-level instructorObserverExclusiveValidator marks the
      // form invalid → inline alert in the roles section + Save disabled; no
      // request ever leaves the client (the domain guard's 400 is the raw-API
      // safety net).
      await page.route('**/api/v1/flight-types', (route) =>
        route.fulfill({ json: mockFlightTypes }),
      );

      await enterSection(page, '/flight-types');
      await page.getByTestId('flight-types-new-button').click();
      await expect(page.getByTestId('flight-types-edit-form')).toBeVisible();

      await page.locator('#FlightTypeName').fill('XOR-Test');
      await page.getByTestId('flight-types-flag-instructor').check();
      await page.getByTestId('flight-types-flag-observer').check();

      // Both set → cross-field error visible in the roles section + Save blocked.
      await expect(page.getByTestId('flight-types-section-roles').getByRole('alert')).toBeVisible();
      await expect(page.getByTestId('flight-types-save-button').locator('button')).toBeDisabled();

      await page.screenshot({
        path: 'screenshots/forms/11-instructor-observer-blocked.png',
        fullPage: true,
      });

      // Unchecking one flag clears the block — the operator can recover inline.
      await page.getByTestId('flight-types-flag-observer').uncheck();
      await expect(page.getByTestId('flight-types-section-roles').getByRole('alert')).toHaveCount(
        0,
      );
      await expect(page.getByTestId('flight-types-save-button').locator('button')).toBeEnabled();
    },
  );

  test.fixme('[happy] af-field-errors renders TRANSLATED text — no raw common.errors.* key visible', async ({
    page,
  }) => {
    // T-08 thickens: af-field-errors goes through transloco (today the raw
    // i18n key renders verbatim). Representative probe: trip a required error
    // and assert the rendered text is the German message, not the key.
    await page.route('**/api/v1/clubs', (route) => route.fulfill({ json: mockClubs }));

    await enterSection(page, '/clubs');
    await page.getByTestId('clubs-table').getByRole('row').nth(1).click();
    await expect(page.getByTestId('clubs-edit-form')).toBeVisible();

    await page.locator('#clubName').fill('');
    const errors = fieldErrors(page, page.locator('#clubName'));
    await expect(errors).toBeVisible();
    // No raw dotted i18n key anywhere in the rendered error line.
    await expect(errors).not.toContainText(/common\.errors\./);
  });

  test.fixme('[happy] profile Account languageId required validator restored (legacy parity)', async ({
    page,
  }) => {
    // T-08 thickens. Profile is in the user-menu chrome (af-nav-user), not a
    // nav section. Clearing the language select must trip required + block Save.
    await page.goto('/start?lang=de');
    await page.getByTestId('af-nav-user').click();
    await page.getByRole('link', { name: /profil/i }).click();
    await expect(page).toHaveURL(/\/profile/);
    // T-08 finalizes: clear languageId → required error + Save disabled.
  });
});

// ═════════════════════════════════════════════════════════════════════════════
// AS-YOU-TYPE DEBOUNCED TRIO (T-10 / T-11) — the J-6b bar on EVERY edit form,
// asserted on the representative aircraft / person / flight-type forms.
// ═════════════════════════════════════════════════════════════════════════════
test.describe('J-26 as-you-type debounced inline validation trio (mock inner loop)', () => {
  test.fixme('[happy] aircraft edit shows a debounced inline error while typing + clears on valid (T-10)', async ({
    page,
  }) => {
    await page.route('**/api/v1/aircraft**', (route) => route.fulfill({ json: mockAircraft }));

    await enterSection(page, '/aircraft');
    await page.getByTestId('aircraft-new-button').click();
    await expect(page.getByTestId('aircraft-edit-form')).toBeVisible();
    // T-10 finalizes: type → clear a required field WITHOUT blur → the inline
    // error appears after the ~200ms debounce; re-typing a valid value clears it.
  });

  test.fixme('[happy] person edit shows a debounced inline error while typing + clears on valid (T-11)', async ({
    page,
  }) => {
    await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: mockPersons }));

    await enterSection(page, '/persons');
    await page.getByTestId('persons-table').getByRole('row').nth(1).click();
    await expect(page.getByTestId('person-form')).toBeVisible();

    const lastname = page.getByTestId('lastname-input').locator('input');
    await lastname.fill('');
    await expect(fieldErrors(page, page.getByTestId('lastname-input'))).toBeVisible();
    await lastname.fill('Pilot');
    await expect(fieldErrors(page, page.getByTestId('lastname-input'))).toHaveCount(0);
  });

  test.fixme('[happy] flight-type edit shows a debounced inline error while typing + clears on valid (T-11)', async ({
    page,
  }) => {
    await page.route('**/api/v1/flight-types', (route) => route.fulfill({ json: mockFlightTypes }));

    await enterSection(page, '/flight-types');
    await page.getByTestId('flight-types-new-button').click();
    await expect(page.getByTestId('flight-types-edit-form')).toBeVisible();

    const name = page.locator('#FlightTypeName');
    await name.fill('X');
    await name.fill('');
    await expect(fieldErrors(page, name)).toBeVisible();
    await name.fill('Schulflug 2');
    await expect(fieldErrors(page, name)).toHaveCount(0);
  });
});

// ═════════════════════════════════════════════════════════════════════════════
// SAVE-GATING (T-09 / T-13).
// ═════════════════════════════════════════════════════════════════════════════
test.describe('J-26 save-gating tracks form validity (mock inner loop)', () => {
  test.fixme('[edge] flight edit: Save gated on the client required validators (flightDate/aircraft/pilot)', async ({
    page,
  }) => {
    // T-13 thickens (+ records the dead-FlightValidator wire-or-delete verdict).
    await page.route('**/api/v1/flights**', (route) => route.fulfill({ json: [] }));

    await enterSection(page, '/flights');
    // In-app: the New-flight CTA opens the stepper form.
    await expect(page.getByTestId('flight-form')).toBeVisible();
    // Required fields empty → the submit control is disabled until
    // flightDate + aircraft + pilot are present.
    await expect(page.getByTestId('flight-submit-sticky').locator('button')).toBeDisabled();
  });

  test.fixme('[edge] reservation Save disable state never disagrees with form validity (async second-crew race)', async ({
    page,
  }) => {
    // T-09 thickens: while the async second-crew validator is PENDING the form
    // is invalid — the Save button must be disabled in that window too (today
    // it shows enabled while form.invalid and the click dead-ends).
    await page.route('**/api/v1/aircraft-reservations**', (route) => route.fulfill({ json: [] }));

    await enterSection(page, '/reservations');
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    // In-app: open the create form from the calendar, then assert the disable
    // state tracks validity across the async validator's pending phase.
  });
});
