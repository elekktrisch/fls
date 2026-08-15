import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';


const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const LOCATION_BERN_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
const AC_ID = '019e30c3-2c00-7001-8000-00000000a001';
const SEED_DAY_ID = '019e30c3-2c00-7001-8000-000000000e01';
const SEED_DAY_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000f01';

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

async function wirePlanningDay(page: Page): Promise<void> {
  await page.route('**/api/v1/locations', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/persons', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: [] }),
  );
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

test.describe('J-6b planning read-only + Edit toggle (mock-auth inner loop)', () => {
  test('read-only view renders ALL fields disabled (not just Save hidden)', async ({ page }) => {
    await wirePlanningDay(page);
    await gotoDe(page, `/planning/${SEED_DAY_ID}/view`);

    await expect(page.getByTestId('planning-edit-form')).toBeVisible();

    await page.screenshot({ path: 'screenshots/planning/05-readonly.png', fullPage: true });

    await expect(page.getByTestId('planning-date').locator('input')).toBeDisabled();
    await expect(page.getByTestId('planning-remarks').locator('input')).toBeDisabled();
    await expect(
      page.getByTestId('planning-location-select').locator('nz-select'),
      'the location select renders disabled in read-only mode',
    ).toHaveClass(/ant-select-disabled/);
    await expect(page.getByTestId('planning-save-button')).toHaveCount(0);
  });

  test('Edit affordance flips read-only → edit mode (fields editable, Save returns)', async ({
    page,
  }) => {
    await wirePlanningDay(page);
    await gotoDe(page, `/planning/${SEED_DAY_ID}/view`);

    const editToggle = page.getByTestId('planning-edit-toggle');
    await expect(editToggle).toBeVisible();
    await editToggle.locator('button').click();
    await expect(page).toHaveURL(new RegExp(`/planning/${SEED_DAY_ID}/edit`));

    await expect(page.getByTestId('planning-date').locator('input')).toBeEnabled();
    await expect(page.getByTestId('planning-remarks').locator('input')).toBeEnabled();
    await expect(page.getByTestId('planning-save-button')).toBeVisible();
    await expect(page.getByTestId('planning-edit-toggle')).toHaveCount(0);
  });
});

test.describe('J-6b reservation Cancel returns to planning (mock-auth inner loop)', () => {
  test('edit planning-day → open inline reservation → Cancel returns to the planning-day edit form', async ({
    page,
  }) => {
    await wirePlanningDay(page);
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

    await expect(page).toHaveURL(
      new RegExp(`/reservations/${SEED_DAY_RESERVATION_ID}/edit\\?returnUrl=`),
    );
    await expect(page.getByTestId('reservation-edit-form')).toBeVisible();

    await page.getByTestId('reservation-edit-cancel').locator('button').click();
    await expect(page).toHaveURL(new RegExp(`/planning/${SEED_DAY_ID}/edit`));
    await expect(page.getByTestId('planning-edit-form')).toBeVisible();
  });
});
