import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const TESTIDS = {
  customForm: 'report-custom-form',
  customFrom: 'report-custom-from',
  customTo: 'report-custom-to',
  customGliderToggle: 'report-custom-glider',
  customMotorToggle: 'report-custom-motor',
  customTowToggle: 'report-custom-tow',
  customPersonSelect: 'report-custom-person',
  customLocationSelect: 'report-custom-location',
  customApply: 'report-custom-apply',
  summaryTable: 'report-summary-table',
  summaryRow: 'report-summary-row',
  flightsTable: 'report-flights-table',
  flightsRow: 'report-flights-row',
} as const;

const mockReportResult = {
  items: [
    {
      flightId: 'fl-019e30c3-2c00-7001-8000-000000000001',
      flightDate: '2026-06-01',
      immatriculation: 'HB-GLI',
      pilotName: 'Anna Pilot',
      secondCrewName: '',
      isSoloFlight: false,
      flightTypeName: 'Training',
      startLocation: 'LSZK',
      ldgLocation: 'LSZK',
      startDateTime: '2026-06-01T08:00:00Z',
      ldgDateTime: '2026-06-01T09:30:00Z',
      flightDuration: '01:30',
      flightComment: '',
      towFlight: null,
    },
  ],
  summaries: [
    {
      groupBy: 'Training',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '01:30',
    },
    {
      groupBy: 'Total',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '01:30',
    },
  ],
};

async function stubReportBackend(page: Page): Promise<void> {
  await page.route('**/api/v1/flightreports/page/**', (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockReportResult),
    }),
  );
  await page.route('**/api/v1/persons**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        { id: 'pn-019e30c3-2c00-7001-8000-000000000001', firstname: 'Anna', lastname: 'Pilot' },
      ]),
    }),
  );
  await page.route('**/api/v1/locations**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        { id: 'loc-1', ownerClubId: CLUB_A_ID, locationName: 'Birrfeld', icaoCode: 'LSZK' },
      ]),
    }),
  );
}

const MAX_URL_ENCODING_LAYERS = 3;

function decodeFilterFromUrl(url: string): Record<string, unknown> {
  let encoded = new URL(url).pathname.split('/').at(-2) ?? '';
  for (let layer = 0; layer < MAX_URL_ENCODING_LAYERS; layer++) {
    try {
      return JSON.parse(encoded) as Record<string, unknown>;
    } catch {
      encoded = decodeURIComponent(encoded);
    }
  }
  throw new Error(`could not decode filter from ${url}`);
}

test.describe('flight reports custom builder — full contract (J-7 mock inner loop)', () => {
  test('declares the custom-builder testid contract', () => {
    for (const id of [
      TESTIDS.customForm,
      TESTIDS.customFrom,
      TESTIDS.customTo,
      TESTIDS.customGliderToggle,
      TESTIDS.customMotorToggle,
      TESTIDS.customTowToggle,
      TESTIDS.customApply,
    ]) {
      expect(id).toMatch(/^report-custom-/);
    }
    expect(TESTIDS.customPersonSelect).not.toBe(TESTIDS.customLocationSelect);
  });

  test('[happy] location builder: date range + toggles → Apply → filter round-trips + results render', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/custom/location/%7B%7D/edit');

    const form = page.getByTestId(TESTIDS.customForm);
    await expect(form).toBeVisible();

    await expect(page.getByTestId(TESTIDS.customLocationSelect)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.customPersonSelect)).toHaveCount(0);

    await page.getByTestId(TESTIDS.customFrom).locator('input').fill('2026-01-01');
    await page.getByTestId(TESTIDS.customTo).locator('input').fill('2026-12-31');

    await page.getByTestId(TESTIDS.customTowToggle).check();

    await page.screenshot({ path: 'screenshots/reporting/05-custom-builder.png', fullPage: true });

    await page.getByTestId(TESTIDS.customApply).locator('button').click();

    await expect(page).toHaveURL(/\/flightreports\/custom\/location\/.+\/(apply|view)/);
    const filter = decodeFilterFromUrl(page.url());
    expect(filter['flightDateFrom']).toBe('2026-01-01');
    expect(filter['flightDateTo']).toBe('2026-12-31');
    expect(filter['gliderFlights']).toBe(true);
    expect(filter['motorFlights']).toBe(true);
    expect(filter['towFlights']).toBe(true);

    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.summaryRow).filter({ hasText: 'Total' })).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsRow).first()).toBeVisible();

    await page.screenshot({
      path: 'screenshots/reporting/06-custom-results.png',
      fullPage: true,
    });
  });

  test('[happy] person builder renders the person selector, not the location one', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/custom/person/%7B%7D/edit');

    await expect(page.getByTestId(TESTIDS.customForm)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.customPersonSelect)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.customLocationSelect)).toHaveCount(0);

    await page.getByTestId(TESTIDS.customFrom).locator('input').fill('2026-01-01');
    await page.getByTestId(TESTIDS.customTo).locator('input').fill('2026-06-30');
    await page.getByTestId(TESTIDS.customApply).locator('button').click();

    await expect(page).toHaveURL(/\/flightreports\/custom\/person\/.+\/(apply|view)/);
    const filter = decodeFilterFromUrl(page.url());
    expect(filter['flightDateFrom']).toBe('2026-01-01');
    expect(filter['flightDateTo']).toBe('2026-06-30');
    expect(filter['towFlights'], 'the person category leaves Tow off by default').toBe(false);

    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
  });

  test('[edge] Apply is disabled until the required From/To range is set', async ({ page }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/custom/location/%7B%7D/edit');

    await expect(page.getByTestId(TESTIDS.customApply).locator('button')).toBeDisabled();
    await page.getByTestId(TESTIDS.customFrom).locator('input').fill('2026-01-01');
    await page.getByTestId(TESTIDS.customTo).locator('input').fill('2026-12-31');
    await expect(page.getByTestId(TESTIDS.customApply).locator('button')).toBeEnabled();
  });
});
