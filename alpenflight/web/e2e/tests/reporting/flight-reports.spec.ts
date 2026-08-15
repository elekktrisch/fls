import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const PERSON_CANNED = [
  'my-flights-today',
  'my-flights-yesterday',
  'my-flights-last-7-days',
  'my-flights-last-30-days',
  'my-flights-last-12-months',
  'my-flights-last-24-months',
  'my-flights-this-year',
  'my-flights-previous-year',
] as const;

const LOCATION_CANNED = [
  'location-flights-today',
  'location-flights-yesterday',
  'location-flights-this-year',
  'location-flights-previous-year',
] as const;

const TESTIDS = {
  pickerPersonCategory: 'flightreports-category-person',
  pickerLocationCategory: 'flightreports-category-location',
  tile: (category: 'person' | 'location', type: string) => `flightreports-tile-${category}-${type}`,
  filterCriteriaPanel: 'report-filter-criteria',
  filterRange: 'report-filter-range',
  filterTypes: 'report-filter-types',
  filterScope: 'report-filter-scope',
  summaryTable: 'report-summary-table',
  summaryRow: 'report-summary-row',
  flightsTable: 'report-flights-table',
  flightsRow: 'report-flights-row',
  flightsTowRow: 'report-flights-tow-row',
  emptyState: 'report-empty',
  excelExport: 'report-excel-export',
  customForm: 'report-custom-form',
  customApply: 'report-custom-apply',
} as const;

function ddmmyyyy(d: Date): string {
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}.${mm}.${d.getFullYear()}`;
}
function today(): Date {
  const n = new Date();
  return new Date(n.getFullYear(), n.getMonth(), n.getDate());
}
function minusDays(base: Date, n: number): Date {
  const d = new Date(base);
  d.setDate(base.getDate() - n);
  return d;
}
function jan1(base: Date): Date {
  return new Date(base.getFullYear(), 0, 1);
}

const personReportResult = {
  items: [
    {
      flightId: 'fl-019e30c3-2c00-7001-8000-000000000001',
      flightDate: '2026-06-01',
      immatriculation: 'HB-GLI',
      pilotName: 'Anna Pilot',
      secondCrewName: 'Beat Copilot',
      isSoloFlight: false,
      flightTypeName: 'Training',
      startLocation: 'LSZK',
      ldgLocation: 'LSZK',
      startDateTime: '2026-06-01T08:00:00Z',
      ldgDateTime: '2026-06-01T09:30:00Z',
      flightDuration: '01:30',
      flightComment: '',
      towFlight: {
        flightId: 'fl-019e30c3-2c00-7001-8000-000000000002',
        immatriculation: 'HB-TOW',
        pilotName: 'Tom Towpilot',
        flightTypeName: 'Tow',
        startLocation: 'LSZK',
        ldgLocation: 'LSZK',
        startDateTime: '2026-06-01T08:00:00Z',
        ldgDateTime: '2026-06-01T08:12:00Z',
        flightDuration: '00:12',
        towedGliderFlightId: 'fl-019e30c3-2c00-7001-8000-000000000001',
      },
    },
    {
      flightId: 'fl-019e30c3-2c00-7001-8000-000000000003',
      flightDate: '2026-06-02',
      immatriculation: 'HB-MOT',
      pilotName: 'Anna Pilot',
      secondCrewName: '',
      isSoloFlight: false,
      flightTypeName: 'Cross-country',
      startLocation: 'LSZK',
      ldgLocation: 'LSGG',
      startDateTime: '2026-06-02T10:00:00Z',
      ldgDateTime: '2026-06-02T12:00:00Z',
      flightDuration: '02:00',
      flightComment: '',
      towFlight: null,
    },
  ],
  summaries: [
    {
      groupBy: 'Pilot (Glider)',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '01:30',
    },
    {
      groupBy: 'Pilot (Motor)',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '02:00',
    },
    {
      groupBy: 'Pilot (Towing)',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '00:12',
    },
    {
      groupBy: 'Copilot',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '01:30',
    },
    {
      groupBy: 'Instructor',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '00:45',
    },
    {
      groupBy: 'Instructor (Soloflights)',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '00:30',
    },
    {
      groupBy: 'Total',
      totalStarts: 6,
      totalLdgs: 6,
      totalFlights: 6,
      totalFlightDuration: '06:27',
    },
  ],
};

const locationReportResult = {
  items: [
    {
      flightId: 'fl-019e30c3-2c00-7001-8000-000000000010',
      flightDate: '2026-03-01',
      immatriculation: 'HB-GLI',
      pilotName: 'Anna Pilot',
      secondCrewName: '',
      isSoloFlight: false,
      flightTypeName: 'Training',
      startLocation: 'LSZK',
      ldgLocation: 'LSZK',
      startDateTime: '2026-03-01T08:00:00Z',
      ldgDateTime: '2026-03-01T09:00:00Z',
      flightDuration: '01:00',
      flightComment: '',
      towFlight: null,
    },
    {
      flightId: 'fl-019e30c3-2c00-7001-8000-000000000011',
      flightDate: '2026-03-02',
      immatriculation: 'HB-MOT',
      pilotName: 'Anna Pilot',
      secondCrewName: '',
      isSoloFlight: false,
      flightTypeName: 'Cross-country',
      startLocation: 'LSZK',
      ldgLocation: 'LSGG',
      startDateTime: '2026-03-02T10:00:00Z',
      ldgDateTime: '2026-03-02T12:00:00Z',
      flightDuration: '02:00',
      flightComment: '',
      towFlight: null,
    },
  ],
  summaries: [
    {
      groupBy: 'Cross-country',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '02:00',
    },
    {
      groupBy: 'Training',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '01:00',
    },
    {
      groupBy: 'Total',
      totalStarts: 2,
      totalLdgs: 2,
      totalFlights: 2,
      totalFlightDuration: '03:00',
    },
  ],
};

async function stubReportBackend(
  page: Page,
  opts: { person?: unknown; location?: unknown } = {},
): Promise<void> {
  const person = opts.person ?? personReportResult;
  const location = opts.location ?? locationReportResult;
  await page.route('**/api/v1/flightreports/page/**', async (route: Route) => {
    const body = route.request().postDataJSON() as
      | { searchFilter?: { locationId?: string; flightCrewPersonId?: string } }
      | undefined;
    const isLocation =
      Boolean(body?.searchFilter?.locationId) && !body?.searchFilter?.flightCrewPersonId;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(isLocation ? location : person),
    });
  });
  await page.route('**/api/v1/flightreports/export/excel/**', (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      headers: { 'content-disposition': 'attachment; filename="FlightReports.xlsx"' },
      body: 'PK',
    }),
  );
  await page.route('**/api/v1/persons**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/locations**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: 'loc-1', ownerClubId: CLUB_A_ID, icaoCode: 'LSZK' }]),
    }),
  );
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const SUMMARY_ROW_CELL_ORDER = ['group', 'starts', 'ldgs', 'flights', 'duration'] as const;
const TOTAL_FLIGHTS_CELL = SUMMARY_ROW_CELL_ORDER.indexOf('flights');

async function summaryRowCells(page: Page, group: string): Promise<string[]> {
  const row = page
    .getByTestId(TESTIDS.summaryRow)
    .filter({ has: page.locator('td', { hasText: new RegExp(`^${escapeRe(group)}$`) }) })
    .first();
  await expect(row).toBeVisible();
  return (await row.locator('td').allInnerTexts()).map((t) => t.trim());
}

test.describe('flight reports — full contract (J-7 mock inner loop)', () => {
  test('declares the screen-shape testid contract the screen tasks implement', () => {
    for (const t of PERSON_CANNED) {
      expect(TESTIDS.tile('person', t)).toBe(`flightreports-tile-person-${t}`);
    }
    for (const t of LOCATION_CANNED) {
      expect(TESTIDS.tile('location', t)).toBe(`flightreports-tile-location-${t}`);
    }
    for (const id of [
      TESTIDS.filterCriteriaPanel,
      TESTIDS.summaryTable,
      TESTIDS.flightsTable,
      TESTIDS.excelExport,
      TESTIDS.customForm,
      TESTIDS.customApply,
    ]) {
      expect(id).toMatch(/^report-/);
    }
  });

  test('[happy] picker renders person + location category tiles, each linking a canned :type', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports');

    await expect(page.getByTestId(TESTIDS.pickerPersonCategory)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.pickerLocationCategory)).toBeVisible();
    for (const t of PERSON_CANNED) {
      const tile = page.getByTestId(TESTIDS.tile('person', t));
      await expect(tile).toBeVisible();
      await expect(tile).toHaveAttribute('href', `/flightreports/person/${t}`);
    }
    for (const t of LOCATION_CANNED) {
      const tile = page.getByTestId(TESTIDS.tile('location', t));
      await expect(tile).toBeVisible();
      await expect(tile).toHaveAttribute('href', `/flightreports/location/${t}`);
    }

    await page.screenshot({ path: 'screenshots/reporting/01-picker.png', fullPage: true });
  });

  test('[happy] canned person report (last-30-days): derived window + crew-function summary + nested tow', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/person/my-flights-last-30-days');

    const base = today();
    const expectedRange = `${ddmmyyyy(minusDays(base, 30))} – ${ddmmyyyy(base)}`;
    await expect(page.getByTestId(TESTIDS.filterRange)).toHaveText(expectedRange);
    await expect(page.getByTestId(TESTIDS.filterTypes)).toHaveText('Glider, Motor');
    await expect(page.getByTestId(TESTIDS.filterScope)).toHaveText('Me');

    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    for (const group of [
      'Pilot (Glider)',
      'Pilot (Motor)',
      'Pilot (Towing)',
      'Copilot',
      'Instructor',
      'Instructor (Soloflights)',
      'Total',
    ]) {
      await expect(
        page
          .getByTestId(TESTIDS.summaryRow)
          .filter({ has: page.locator('td', { hasText: new RegExp(`^${escapeRe(group)}$`) }) }),
      ).toHaveCount(1);
    }
    const motorCells = await summaryRowCells(page, 'Pilot (Motor)');
    const motorPilotFlightsCorrectedFromTheLegacyZero = Number(motorCells[TOTAL_FLIGHTS_CELL]);
    expect(motorPilotFlightsCorrectedFromTheLegacyZero).toBeGreaterThan(0);
    const towCells = await summaryRowCells(page, 'Pilot (Towing)');
    const towPilotFlightsCorrectedFromTheLegacyZero = Number(towCells[TOTAL_FLIGHTS_CELL]);
    expect(towPilotFlightsCorrectedFromTheLegacyZero).toBeGreaterThan(0);

    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsRow).first()).toBeVisible();
    const towRow = page.getByTestId(TESTIDS.flightsTowRow).first();
    await expect(towRow).toBeVisible();
    await expect(towRow).toContainText('HB-TOW');
    await expect(towRow).toContainText('Tom Towpilot');

    await page.screenshot({
      path: 'screenshots/reporting/02-canned-person-results.png',
      fullPage: true,
    });
  });

  test('[happy] canned person report (this-year): derived Jan-1 → today window', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/person/my-flights-this-year');

    const base = today();
    const expectedRange = `${ddmmyyyy(jan1(base))} – ${ddmmyyyy(base)}`;
    await expect(page.getByTestId(TESTIDS.filterRange)).toHaveText(expectedRange);
    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();
  });

  test('[happy] canned location report groups the summary by FlightTypeName', async ({ page }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/location/location-flights-this-year');

    const base = today();
    await expect(page.getByTestId(TESTIDS.filterRange)).toHaveText(
      `${ddmmyyyy(jan1(base))} – ${ddmmyyyy(base)}`,
    );
    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    await expect(
      page.getByTestId(TESTIDS.summaryRow).filter({ hasText: 'Training' }),
    ).toBeVisible();
    await expect(
      page.getByTestId(TESTIDS.summaryRow).filter({ hasText: 'Cross-country' }),
    ).toBeVisible();
    await expect(page.getByTestId(TESTIDS.summaryRow).filter({ hasText: 'Total' })).toBeVisible();
    await expect(
      page.getByTestId(TESTIDS.summaryRow).filter({ hasText: /Pilot \(Glider\)/ }),
    ).toHaveCount(0);
    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();

    await page.screenshot({
      path: 'screenshots/reporting/03-canned-location-results.png',
      fullPage: true,
    });
  });

  test('[happy] Excel export button streams an .xlsx spreadsheet attachment', async ({ page }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/person/my-flights-last-30-days');

    const exportBtn = page.getByTestId(TESTIDS.excelExport);
    await expect(exportBtn).toBeVisible();
    await expect(exportBtn.locator('button')).toBeEnabled();
    const downloadPromise = page.waitForEvent('download');
    await exportBtn.click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.xlsx$/);
  });

  test('[edge] a filter matching no flights renders the empty-state + disables export', async ({
    page,
  }) => {
    await stubReportBackend(page, { person: { items: [], summaries: [] } });
    await page.goto('/flightreports/person/my-flights-today');

    await expect(page.getByTestId(TESTIDS.emptyState)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.summaryTable)).toHaveCount(0);
    await expect(page.getByTestId(TESTIDS.flightsTable)).toHaveCount(0);
    await expect(page.getByTestId(TESTIDS.excelExport).locator('button')).toBeDisabled();

    await page.screenshot({ path: 'screenshots/reporting/04-empty.png', fullPage: true });
  });
});
