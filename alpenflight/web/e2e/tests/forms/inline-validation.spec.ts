import { type Locator, type Page } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';


const LOCATION_BERN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
const AC_ID = '019e30c3-2c00-7001-8000-00000000a001';
const PILOT_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const TYPE_FLIGHT_ID = '019e30c3-2c00-7001-8000-0000000000d1';

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

async function wirePlanningCreate(page: Page, { conflict }: { conflict: boolean }): Promise<void> {
  await page.route('**/api/v1/locations', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/persons', (route) => route.fulfill({ json: mockPersons }));
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: mockTypes }),
  );
  await page.route('**/api/v1/aircraft-reservations/day/**', (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route('**/api/v1/planning-days/validate', (route) =>
    route.fulfill({
      json: conflict
        ? { valid: false, field: 'planningDate', message: 'planning.day.duplicate' }
        : { valid: true },
    }),
  );
}

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
    await date.fill(dayKeyFromToday(3));
    await expect(dateFieldErrors(page)).toHaveCount(0);
  });

  test('a non-trivial rule is server-validated; its message surfaces inline without a full submit', async ({
    page,
  }) => {
    await wirePlanningCreate(page, { conflict: true });
    await gotoDe(page, '/planning/new/edit');
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(3));
    await selectAfOption(page, 'planning-location-select', LOCATION_BERN_ID);

    await expect(page.getByTestId('planning-date-server-error')).toBeVisible();
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

    await expect(page.getByTestId('reservation-start-server-error')).toHaveCount(0);
    await expect(page.getByTestId('reservation-save-button').locator('button')).toBeEnabled();
  });
});
