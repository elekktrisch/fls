import { expect, test, type Locator, type Page } from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';
import { enterViaNav } from '../_helpers/nav';

/**
 * J-26 VALIDATION HARDENING — mock inner-loop spec.
 *
 * ALL CASES ARE ACTIVE. Authored as a thin `test.fixme` stub at T-01 (structure,
 * chrome-entry flow, and selectors), each case was un-fixme'd by its fix task
 * (T-05..T-13) as the behavior landed and thickened to full real assertions by
 * T-27. No `test.fixme` remains — every case runs (and must stay green) on the
 * per-push gate.
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
 * ── CASES (J-26 "Spec must assert" §forms/validation-hardening) ─────────────
 *   [happy]     af-field-errors renders TRANSLATED text, never a raw
 *               `common.errors.*` key (T-08) + profile languageId required (T-08)
 *   [happy]     as-you-type debounced (~200ms) trio: aircraft (T-10),
 *               person (T-11), flight-type (T-11)
 *   [edge]      flight edit: Save gated on client required validators (T-13)
 *   [edge]      reservation Save disable state tracks form validity across the
 *               async second-crew validator (T-09)
 *
 * The three duplicate-key / cross-field error-path cases (dup FlightCode 409,
 * dup clubKey 409, Instructor×Observer XOR) are owned cheaper by their backend
 * twins — FlightTypeDuplicateCodeIT, ClubsControllerIT, FlightTypeDomainTest —
 * so they are not re-asserted here.
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
const COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';
// V2-seeded language id (LANGUAGE_BY_LOCALE.de — src/app/shared/ui/locale).
const LANGUAGE_ID_DE = '019e2e15-2c00-77d0-8000-0000000007d0';

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
  // Masterdata sections now nest under the Masterdata nav group (J-8 T-22a);
  // enterViaNav opens that dropdown first for nested paths and clicks top-level
  // sections (e.g. /flights, /reservations, /clubs) directly.
  await enterViaNav(page, sectionPath);
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
// CROSS-FIELD + TRANSLATED ERRORS (T-08).
// ═════════════════════════════════════════════════════════════════════════════
test.describe('J-26 cross-field validator + translated messages (mock inner loop)', () => {
  test('[happy] af-field-errors renders TRANSLATED text — no raw common.errors.* key visible', async ({
    page,
  }) => {
    // T-08: af-field-errors renders its mapped keys through transloco (the raw
    // i18n key used to render verbatim). Representative probe: trip a required
    // error and assert the rendered text is the German message, not the key.
    await page.route('**/api/v1/countries**', (route) =>
      route.fulfill({ json: [{ id: COUNTRY_ID, iso2Code: 'CH', name: 'Switzerland' }] }),
    );
    await page.route('**/api/v1/club-states**', (route) =>
      route.fulfill({ json: [{ id: CLUB_STATE_ID, code: 'ACTIVE', name: 'Active' }] }),
    );
    await page.route('**/api/v1/clubs', (route) => route.fulfill({ json: mockClubs }));

    await enterSection(page, '/clubs');
    // Row primary cell is a link (af-data-table renders a list, not a table).
    await page.getByTestId('club-row-seed-club').click();
    await expect(page.getByTestId('clubs-edit-form')).toBeVisible();

    const name = page.locator('#clubName');
    await expect(name).toHaveValue('Seed Club'); // hydrated before we clear it
    await name.fill('');
    await name.blur(); // errors render once the field is touched (S-007 gate)

    const errors = fieldErrors(page, name);
    await expect(errors).toBeVisible();
    // The TRANSLATED German message (lang pinned via ?lang=de), and no raw
    // dotted i18n key anywhere in the rendered error line.
    await expect(errors).toHaveText('Eingabe erforderlich.');
    await expect(errors).not.toContainText(/common\.errors\./);

    await page.screenshot({
      path: 'screenshots/forms/13-translated-required-error.png',
      fullPage: true,
    });
  });

  test('[happy] profile Account languageId required validator restored (legacy parity)', async ({
    page,
  }) => {
    // T-08. Profile is in the user-menu chrome (af-nav-user), not a nav
    // section. Clearing the language select must trip required + block Save
    // (legacy profile.html:61 marked the language selectize `required`).
    // `personId: null` keeps the Personal/Pilot/Notifications tabs disabled, so
    // GET /api/v1/me is the only call the screen fires under mock-auth.
    await page.route('**/api/v1/me', (route) =>
      route.fulfill({
        json: {
          id: 'mock-sysadmin',
          personId: null,
          clubId: CLUB_ID,
          roles: ['SYSTEM_ADMINISTRATOR', 'CLUB_ADMINISTRATOR'],
          firstName: 'Mock',
          lastName: 'Sysadmin',
          email: 'mock@local',
          username: 'mock-sysadmin',
          friendlyName: 'Mock Sysadmin',
          phoneNumber: '',
          languageId: LANGUAGE_ID_DE,
          languageCode: 'de',
        },
      }),
    );

    await page.goto('/start?lang=de');
    await page.getByTestId('af-nav-user').click();
    // The dropdown entry is an <a role="menuitem"> (explicit role overrides
    // the implicit link role).
    await page.getByRole('menuitem', { name: /profil/i }).click();
    await expect(page).toHaveURL(/\/profile/);

    // Hydrated: the seeded language renders + Save is enabled (form valid).
    const language = page.getByTestId('profile-account-language');
    await expect(language).toContainText('Deutsch');
    const save = page.getByTestId('profile-account-save').locator('button');
    await expect(save).toBeEnabled();

    // Clear the select (nz-select clear affordance shows on hover) → required
    // error inline via af-field-errors (translated, T-08a) + Save disabled.
    await language.hover();
    await language.locator('nz-select-clear').click();

    const errors = fieldErrors(page, language);
    await expect(errors).toBeVisible();
    await expect(errors).toHaveText('Eingabe erforderlich.');
    await expect(errors).not.toContainText(/common\.errors\./);
    await expect(save).toBeDisabled();

    await page.screenshot({
      path: 'screenshots/forms/14-profile-language-required.png',
      fullPage: true,
    });

    // Re-picking a language recovers inline — error clears, Save re-enables.
    await language.click();
    await page.getByTestId(`af-select-option-${LANGUAGE_ID_DE}`).click();
    await expect(errors).toHaveCount(0);
    await expect(save).toBeEnabled();
  });
});

// ═════════════════════════════════════════════════════════════════════════════
// AS-YOU-TYPE DEBOUNCED TRIO (T-10 / T-11) — the J-6b bar on EVERY edit form,
// asserted on the representative aircraft / person / flight-type forms.
// ═════════════════════════════════════════════════════════════════════════════
test.describe('J-26 as-you-type debounced inline validation trio (mock inner loop)', () => {
  test('[happy] aircraft edit shows a debounced inline error while typing + clears on valid (T-10)', async ({
    page,
  }) => {
    await page.route('**/api/v1/aircraft**', (route) => route.fulfill({ json: mockAircraft }));

    await enterSection(page, '/aircraft');
    await page.getByTestId('aircraft-new-button').click();
    await expect(page.getByTestId('aircraft-edit-form')).toBeVisible();

    // T-10: the J-6b as-you-type bar adopted on aircraft — type an INVALID value
    // into a required field WITHOUT blurring; the inline error appears after the
    // ~200ms `liveFieldErrors` debounce. Re-typing a VALID value clears it.
    // `immatriculation` carries required + pattern (`^[A-Z0-9-]{2,15}$`); the
    // touched-only binding showed nothing while typing — this proves it now does.
    const immat = page.locator('#Immatriculation');
    // A single lowercase char fails BOTH minLength(2) and the upper-only pattern,
    // so the field is invalid the moment it has content (no blur needed).
    await immat.fill('a');
    await expect(fieldErrors(page, immat)).toBeVisible();
    // A valid immatriculation clears the inline error (debounced).
    await immat.fill('HB-3000');
    await expect(fieldErrors(page, immat)).toHaveCount(0);
  });

  test('[happy] person edit shows a debounced inline error while typing + clears on valid (T-11)', async ({
    page,
  }) => {
    // Detail GET (`/persons/{id}`) is registered FIRST so the broader list glob
    // (registered AFTER, last-wins in Playwright) does not shadow it — the edit
    // page loads a SINGLE person, not the array the list returns.
    await page.route(`**/api/v1/persons/${PERSON_ID}`, (route) =>
      route.fulfill({ json: mockPersons[0] }),
    );
    await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: mockPersons }));
    await page.route('**/api/v1/member-states**', (route) => route.fulfill({ json: [] }));

    await enterSection(page, '/persons');
    // The persons list is an af-data-table rendered as a LIST (not a <table>) —
    // the row primary cell is a routerLink testid `person-row-<id>` (J-26 T-08
    // corrected the same stub assumption on /clubs).
    await page.getByTestId(`person-row-${PERSON_ID}`).click();
    await expect(page.getByTestId('person-form')).toBeVisible();

    const lastname = page.getByTestId('lastname-input').locator('input');
    await lastname.fill('');
    const lastnameErrors = fieldErrors(page, page.getByTestId('lastname-input'));
    await expect(lastnameErrors).toBeVisible();
    // T-08b: the inline as-you-type error must render the TRANSLATED German prose
    // (lang pinned via ?lang=de in enterSection), never the raw `common.errors.*`
    // i18n key — the af-field-errors transloco fix (T-08). `lastname` carries only
    // `required` + `maxLength(100)`, so an empty value trips required ALONE → a
    // single message. This guards the CLIENT-validator-key → translated path on a
    // live, debounced inline error (the blur-based /clubs probe above covers the
    // touched-on-blur path; this one covers as-you-type).
    await expect(lastnameErrors).toHaveText('Eingabe erforderlich.');
    await expect(lastnameErrors).not.toContainText(/common\.errors\./);
    await lastname.fill('Pilot');
    await expect(fieldErrors(page, page.getByTestId('lastname-input'))).toHaveCount(0);
  });

  test('[happy] flight-type edit shows a debounced inline error while typing + clears on valid (T-11)', async ({
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
  test('[edge] flight edit: Save gated on the client required validators (flightDate/aircraft/pilot)', async ({
    page,
  }) => {
    // T-13: the flight form grew `Validators.required` on flightDate +
    // glider.aircraftId + glider.pilotPersonId, and the header/sticky Save
    // bind `[disabled]="saving() || formInvalid()"`. The new-template here is
    // deliberately MINIMAL (date + start-type only, NO aircraft / crew) so the
    // form opens INVALID — proving Save is gated, not merely defaulted valid.
    const FT_GLIDER_ID = FLIGHT_TYPE_ID;
    const START_TYPE_WINCH = '019e2e15-2c00-7fa0-8000-000000000fa0'; // non-towing → no tow step
    // Playwright matches routes in REVERSE registration order (last wins): the
    // broad `flights**` catch-all is registered FIRST so the specific
    // new-template + last-context handlers (registered AFTER) win for their
    // paths — otherwise `flights**` shadows `/flights/new-template` and the form
    // hydrates with a NULL flightDate (the gate would then never lift).
    await page.route('**/api/v1/flights**', (route) => route.fulfill({ json: { items: [] } }));
    await page.route('**/api/v1/flights/new-template', (route) =>
      route.fulfill({
        json: {
          flightAircraftType: 'GLIDER',
          flightDate: '2026-07-01',
          startTypeId: START_TYPE_WINCH,
          crew: [],
          isSoloFlight: false,
          noStartTimeInformation: false,
          noLdgTimeInformation: false,
        },
      }),
    );
    await page.route('**/api/v1/flights/last-context**', (route) =>
      route.fulfill({ status: 404, json: {} }),
    );
    // Masterdata the wizard's selects read (aircraft / persons / flight-types /
    // locations stores). One glider aircraft + one pilot so the operator can
    // satisfy the required fields and watch Save enable.
    await page.route('**/api/v1/aircraft**', (route) => route.fulfill({ json: mockAircraft }));
    await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: mockPersons }));
    await page.route('**/api/v1/flight-types', (route) => route.fulfill({ json: mockFlightTypes }));
    await page.route('**/api/v1/locations', (route) => route.fulfill({ json: [] }));

    await enterSection(page, '/flights');
    // The /flights section lands on the LIST; the New-flight CTA opens the
    // stepper at /flights/new (chrome entry, never a bare goto).
    await page.getByTestId('flights-new-button').click();
    await expect(page.getByTestId('flight-form')).toBeVisible();

    // The sticky Save lives on the LAST step; flightDate is templated but
    // aircraft + pilot are empty → the form is INVALID, so Save is disabled.
    // (Header + sticky bind the same `formInvalid()` gate; the sticky is the
    // <lg slot the stub named.)
    const headerSave = page.getByTestId('flight-submit-header').locator('button');
    await expect(headerSave).toBeDisabled();

    // Fill the two missing required fields on the Glider step.
    await page.getByTestId('flight-step-1').click();
    await selectAfOption(page, 'flight-edit-glider-aircraft', AIRCRAFT_ID);
    await selectAfOption(page, 'flight-edit-glider-pilot', PERSON_ID);
    void FT_GLIDER_ID;

    // All three required fields present → Save enables (the gate tracked
    // validity across the transition).
    await expect(headerSave).toBeEnabled();

    await page.screenshot({
      path: 'screenshots/forms/15-flight-save-gated.png',
      fullPage: true,
    });
  });

  test('[edge] reservation Save disable state never disagrees with form validity (async second-crew race)', async ({
    page,
  }) => {
    // T-09: the conditional second-crew validator flips the form back to INVALID
    // the moment a MULTI-SEAT aircraft is picked (`nrOfSeats > 1` requires a
    // second crew member). The Save button must track the live form STATUS, so
    // it can NEVER read enabled while the form is invalid (or an async leg is
    // pending) — the J-7 T-20 race where the button showed enabled for a beat and
    // a click dead-ended silently in `onSubmit`.
    const RES_AIRCRAFT_ID = '019e30c3-2c00-7001-8000-00000000a002';
    const RES_TYPE_ID = '019e30c3-2c00-7001-8000-0000000000d1';
    const RES_LOCATION_ID = '019e30c3-2c00-7001-8000-0000000000c1';
    // Multi-seat aircraft → second crew is REQUIRED once it is selected.
    await page.route('**/api/v1/aircraft/picker**', (route) =>
      route.fulfill({
        json: [
          {
            id: RES_AIRCRAFT_ID,
            immatriculation: 'HB-MULTI',
            isTowingAircraft: false,
            nrOfSeats: 2,
          },
        ],
      }),
    );
    await page.route('**/api/v1/persons', (route) => route.fulfill({ json: mockPersons }));
    await page.route('**/api/v1/locations', (route) =>
      route.fulfill({
        json: [{ id: RES_LOCATION_ID, locationName: 'Bern-Belp', isAirfield: true }],
      }),
    );
    await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
      route.fulfill({
        json: [{ id: RES_TYPE_ID, name: 'Flight', active: true, instructorRequired: false }],
      }),
    );
    // Reservation read endpoints. Playwright matches routes in REVERSE
    // registration order (last wins), so the broad catch-all is registered FIRST
    // and the specific `/page/` + `/validate` handlers AFTER it, ensuring they win
    // for their paths. The `/validate` pre-check answers `valid:true`
    // (ReservationValidationResult shape) so the overlap slot does not block Save.
    await page.route('**/api/v1/aircraft-reservations**', (route) => route.fulfill({ json: [] }));
    await page.route('**/api/v1/aircraft-reservations/page/**', (route) =>
      route.fulfill({ json: { items: [], pageStart: 0, pageSize: 20, totalRows: 0 } }),
    );
    await page.route('**/api/v1/aircraft-reservations/validate**', (route) =>
      route.fulfill({ json: { valid: true } }),
    );

    // Chrome entry: nav → calendar → New reservation → form.
    await enterSection(page, '/reservations');
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    await page.getByTestId('reservations-new-button').click();
    await expect(page.getByTestId('reservation-edit-form')).toBeVisible();

    const saveButton = page.getByTestId('reservation-save-button').locator('button');

    // Empty form → disabled.
    await expect(saveButton).toBeDisabled();

    // Fill EVERY base required field, leaving second-crew empty and picking the
    // multi-seat aircraft LAST — the exact ordering that triggered the race
    // (picker resolves `nrOfSeats`, validator flips, disable binding must follow).
    await selectAfOption(page, 'reservation-type-select', RES_TYPE_ID);
    await selectAfOption(page, 'reservation-pilot-select', PERSON_ID);
    await selectAfOption(page, 'reservation-location-select', RES_LOCATION_ID);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-01');
    await page.getByTestId('reservation-start-time').locator('input').fill('10:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('11:00');
    await selectAfOption(page, 'reservation-aircraft-select', RES_AIRCRAFT_ID);

    // Second-crew is now required (multi-seat) but empty → form is INVALID, so
    // Save STAYS disabled. The race fix means it never flips enabled in between:
    // assert it is consistently disabled while the field is required-and-empty.
    await expect(saveButton).toBeDisabled();

    // Supplying the second crew member makes the form VALID → Save enables. The
    // disable state tracked validity across the whole transition.
    await selectAfOption(page, 'reservation-second-crew-select', PERSON_ID);
    await expect(saveButton).toBeEnabled();
  });
});
