import { type Page } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';


const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const AC_SAME = '019e30c3-2c00-7001-8000-00000000a001';
const PILOT_ID = '019e30c3-2c00-7001-8000-0000000000b1';
const LOCATION_ID = '019e30c3-2c00-7001-8000-00000000c001';
const TYPE_FLIGHT_ID = '019e30c3-2c00-7001-8000-0000000000d1';
const SEED_RESERVATION_ID = '019e30c3-2c00-7001-8000-000000000e01';

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

async function wireReservations(page: Page, reservations: MockReservation[]): Promise<void> {
  await page.route('**/api/v1/aircraft/picker**', (route) => route.fulfill({ json: mockAircraft }));
  await page.route('**/api/v1/persons', (route) => route.fulfill({ json: mockPersons }));
  await page.route('**/api/v1/locations', (route) => route.fulfill({ json: mockLocations }));
  await page.route('**/api/v1/aircraft-reservation-types**', (route) =>
    route.fulfill({ json: mockTypes }),
  );
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

test.describe('J-6b reservations route + calendar shape (mock-auth inner loop)', () => {
  test('the /reservations route renders the Day/Week calendar', async ({ page }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    await expect(page.getByTestId('reservations-view-toggle')).toBeVisible();
    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
  });
});

test.describe('J-6b reservations calendar hardening (mock-auth inner loop)', () => {
  test('Day/Week toggle renders the SELECTED button legibly (data-selected + dark-ground/light-text)', async ({
    page,
  }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    const toggle = page.getByTestId('reservations-view-toggle');
    await expect(toggle).toBeVisible();

    const dayBtn = page.getByTestId('reservations-view-day');
    const weekBtn = page.getByTestId('reservations-view-week');
    await expect(dayBtn).toHaveAttribute('data-selected', 'true');
    await expect(weekBtn).toHaveAttribute('data-selected', 'false');

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

    await page.screenshot({
      path: 'screenshots/reservations/06-toggle-day-selected.png',
      fullPage: true,
    });

    await weekBtn.click();
    await expect(weekBtn).toHaveAttribute('data-selected', 'true');
    await expect(dayBtn).toHaveAttribute('data-selected', 'false');
  });

  test('DAY view pages by single days; the label is a single DD.MM.YYYY', async ({ page }) => {
    await wireReservations(page, [seedReservation]);
    await gotoDe(page, '/reservations');

    await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    const label = page.getByTestId('reservations-period-label');
    await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}$/);
    await expect(label).toHaveText(TODAY.ddmmyyyy);

    await expect(page.getByTestId('reservations-next-week')).toHaveCount(0);
    await page.getByTestId('reservations-next-day').click();
    await expect(label).not.toHaveText(TODAY.ddmmyyyy);
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

    const label = page.getByTestId('reservations-period-label');
    await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}\s*[–-]\s*\d{2}\.\d{2}\.\d{4}$/);

    await page.screenshot({
      path: 'screenshots/reservations/07-week-range-label.png',
      fullPage: true,
    });

    await expect(page.getByTestId('reservations-next-day')).toHaveCount(0);
    const before = (await label.textContent())?.trim() ?? '';
    await page.getByTestId('reservations-next-week').click();
    await expect(label).not.toHaveText(before);
    await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}\s*[–-]\s*\d{2}\.\d{2}\.\d{4}$/);
    const after = (await label.textContent())?.trim() ?? '';
    expect(weekStartMs(after) - weekStartMs(before)).toBe(7 * 24 * 3600 * 1000);

    await page.getByTestId('reservations-prev-week').click();
    await expect(label).toHaveText(before);
  });
});

function weekStartMs(rangeLabel: string): number {
  const start = rangeLabel.split(/[–-]/)[0]!.trim();
  const [d, m, y] = start.split('.').map(Number);
  return new Date(y!, m! - 1, d!).getTime();
}
