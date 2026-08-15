import { type Locator, type Page } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';
import { enterViaNav } from '../_helpers/nav';

const CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';
const FLIGHT_TYPE_ID = '019e30c3-2c00-7001-8000-0000000000f1';
const PERSON_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const AIRCRAFT_ID = '019e30c3-2c00-7001-8000-00000000a001';
const COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';
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

async function enterSectionThroughNavChromeInGerman(
  page: Page,
  sectionPath: string,
): Promise<void> {
  await page.goto('/start?lang=de');
  await enterViaNav(page, sectionPath);
}

function fieldErrors(page: Page, controlLocator: Locator): Locator {
  return page.locator('af-form-field', { has: controlLocator }).getByRole('alert');
}

test.describe('J-26 cross-field validator + translated messages (mock inner loop)', () => {
  test('[happy] af-field-errors renders TRANSLATED text — no raw common.errors.* key visible', async ({
    page,
  }) => {
    await page.route('**/api/v1/countries**', (route) =>
      route.fulfill({ json: [{ id: COUNTRY_ID, iso2Code: 'CH', name: 'Switzerland' }] }),
    );
    await page.route('**/api/v1/club-states**', (route) =>
      route.fulfill({ json: [{ id: CLUB_STATE_ID, code: 'ACTIVE', name: 'Active' }] }),
    );
    await page.route('**/api/v1/clubs', (route) => route.fulfill({ json: mockClubs }));

    await enterSectionThroughNavChromeInGerman(page, '/clubs');
    await page.getByTestId('club-row-seed-club').click();
    await expect(page.getByTestId('clubs-edit-form')).toBeVisible();

    const name = page.locator('#clubName');
    await expect(name).toHaveValue('Seed Club');
    await name.fill('');
    await name.blur();

    const errors = fieldErrors(page, name);
    await expect(errors).toBeVisible();
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
    const meWithoutPersonIdSoOnlyTheAccountTabLoads = {
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
    };
    await page.route('**/api/v1/me', (route) =>
      route.fulfill({ json: meWithoutPersonIdSoOnlyTheAccountTabLoads }),
    );

    await page.goto('/start?lang=de');
    await page.getByTestId('af-nav-user').click();
    await page.getByRole('menuitem', { name: /profil/i }).click();
    await expect(page).toHaveURL(/\/profile/);

    const language = page.getByTestId('profile-account-language');
    await expect(language).toContainText('Deutsch');
    const save = page.getByTestId('profile-account-save').locator('button');
    await expect(save).toBeEnabled();

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

    await language.click();
    await page.getByTestId(`af-select-option-${LANGUAGE_ID_DE}`).click();
    await expect(errors).toHaveCount(0);
    await expect(save).toBeEnabled();
  });
});

test.describe('J-26 as-you-type debounced inline validation trio (mock inner loop)', () => {
  test('[happy] aircraft edit shows a debounced inline error while typing + clears on valid (T-10)', async ({
    page,
  }) => {
    await page.route('**/api/v1/aircraft**', (route) => route.fulfill({ json: mockAircraft }));

    await enterSectionThroughNavChromeInGerman(page, '/aircraft');
    await page.getByTestId('aircraft-new-button').click();
    await expect(page.getByTestId('aircraft-edit-form')).toBeVisible();

    const immat = page.locator('#Immatriculation');
    await immat.fill('a');
    await expect(fieldErrors(page, immat)).toBeVisible();
    await immat.fill('HB-3000');
    await expect(fieldErrors(page, immat)).toHaveCount(0);
  });

  test('[happy] person edit shows a debounced inline error while typing — translated German, never a raw common.errors.* key — and clears on valid (T-11)', async ({
    page,
  }) => {
    await page.route(`**/api/v1/persons/${PERSON_ID}`, (route) =>
      route.fulfill({ json: mockPersons[0] }),
    );
    await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: mockPersons }));
    await page.route('**/api/v1/member-states**', (route) => route.fulfill({ json: [] }));

    await enterSectionThroughNavChromeInGerman(page, '/persons');
    await page.getByTestId(`person-row-${PERSON_ID}`).click();
    await expect(page.getByTestId('person-form')).toBeVisible();

    const lastname = page.getByTestId('lastname-input').locator('input');
    await lastname.fill('');
    const lastnameErrors = fieldErrors(page, page.getByTestId('lastname-input'));
    await expect(lastnameErrors).toBeVisible();
    await expect(lastnameErrors).toHaveText('Eingabe erforderlich.');
    await expect(lastnameErrors).not.toContainText(/common\.errors\./);
    await lastname.fill('Pilot');
    await expect(fieldErrors(page, page.getByTestId('lastname-input'))).toHaveCount(0);
  });

  test('[happy] flight-type edit shows a debounced inline error while typing + clears on valid (T-11)', async ({
    page,
  }) => {
    await page.route('**/api/v1/flight-types', (route) => route.fulfill({ json: mockFlightTypes }));

    await enterSectionThroughNavChromeInGerman(page, '/flight-types');
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

test.describe('J-26 save-gating tracks form validity (mock inner loop)', () => {
  test('[edge] flight edit: Save gated on the client required validators (flightDate/aircraft/pilot)', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(testInfo, /\b404\b/);
    const FT_GLIDER_ID = FLIGHT_TYPE_ID;
    const START_TYPE_WINCH = '019e2e15-2c00-7fa0-8000-000000000fa0';
    const flightsCatchAllRegisteredFirstSoTheSpecificRoutesBelowWin = '**/api/v1/flights**';
    const newTemplateWithoutAircraftOrCrewSoTheFormOpensInvalid = {
      flightAircraftType: 'GLIDER',
      flightDate: '2026-07-01',
      startTypeId: START_TYPE_WINCH,
      crew: [],
      isSoloFlight: false,
      noStartTimeInformation: false,
      noLdgTimeInformation: false,
    };
    await page.route(flightsCatchAllRegisteredFirstSoTheSpecificRoutesBelowWin, (route) =>
      route.fulfill({ json: { items: [] } }),
    );
    await page.route('**/api/v1/flights/new-template', (route) =>
      route.fulfill({ json: newTemplateWithoutAircraftOrCrewSoTheFormOpensInvalid }),
    );
    await page.route('**/api/v1/flights/last-context**', (route) =>
      route.fulfill({ status: 404, json: {} }),
    );
    await page.route('**/api/v1/aircraft**', (route) => route.fulfill({ json: mockAircraft }));
    await page.route('**/api/v1/persons**', (route) => route.fulfill({ json: mockPersons }));
    await page.route('**/api/v1/flight-types', (route) => route.fulfill({ json: mockFlightTypes }));
    await page.route('**/api/v1/locations', (route) => route.fulfill({ json: [] }));

    await enterSectionThroughNavChromeInGerman(page, '/flights');
    await page.getByTestId('flights-new-button').click();
    await expect(page.getByTestId('flight-form')).toBeVisible();

    const headerSave = page.getByTestId('flight-submit-header').locator('button');
    await expect(headerSave).toBeDisabled();

    await page.getByTestId('flight-step-1').click();
    await selectAfOption(page, 'flight-edit-glider-aircraft', AIRCRAFT_ID);
    await selectAfOption(page, 'flight-edit-glider-pilot', PERSON_ID);
    void FT_GLIDER_ID;

    await expect(headerSave).toBeEnabled();

    await page.screenshot({
      path: 'screenshots/forms/15-flight-save-gated.png',
      fullPage: true,
    });
  });

  test('[edge] reservation Save disable state never disagrees with form validity (async second-crew race)', async ({
    page,
  }) => {
    const MULTI_SEAT_AIRCRAFT_ID = '019e30c3-2c00-7001-8000-00000000a002';
    const RES_TYPE_ID = '019e30c3-2c00-7001-8000-0000000000d1';
    const RES_LOCATION_ID = '019e30c3-2c00-7001-8000-0000000000c1';
    await page.route('**/api/v1/aircraft/picker**', (route) =>
      route.fulfill({
        json: [
          {
            id: MULTI_SEAT_AIRCRAFT_ID,
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
    const reservationsCatchAllRegisteredFirstSoTheSpecificRoutesBelowWin =
      '**/api/v1/aircraft-reservations**';
    await page.route(reservationsCatchAllRegisteredFirstSoTheSpecificRoutesBelowWin, (route) =>
      route.fulfill({ json: [] }),
    );
    await page.route('**/api/v1/aircraft-reservations/page/**', (route) =>
      route.fulfill({ json: { items: [], pageStart: 0, pageSize: 20, totalRows: 0 } }),
    );
    await page.route('**/api/v1/aircraft-reservations/validate**', (route) =>
      route.fulfill({ json: { valid: true } }),
    );

    await enterSectionThroughNavChromeInGerman(page, '/reservations');
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    await page.getByTestId('reservations-new-button').click();
    await expect(page.getByTestId('reservation-edit-form')).toBeVisible();

    const saveButton = page.getByTestId('reservation-save-button').locator('button');

    await expect(saveButton).toBeDisabled();

    await selectAfOption(page, 'reservation-type-select', RES_TYPE_ID);
    await selectAfOption(page, 'reservation-pilot-select', PERSON_ID);
    await selectAfOption(page, 'reservation-location-select', RES_LOCATION_ID);
    await page.getByTestId('reservation-date').locator('input').fill('2026-07-01');
    await page.getByTestId('reservation-start-time').locator('input').fill('10:00');
    await page.getByTestId('reservation-end-time').locator('input').fill('11:00');
    await selectAfOption(page, 'reservation-aircraft-select', MULTI_SEAT_AIRCRAFT_ID);

    await expect(saveButton).toBeDisabled();

    await selectAfOption(page, 'reservation-second-crew-select', PERSON_ID);
    await expect(saveButton).toBeEnabled();
  });
});
