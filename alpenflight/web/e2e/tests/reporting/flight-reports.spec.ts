import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * J-7 T-01 — Flight-reports SCREEN-SHAPE spec (mock inner loop).
 *
 * THIN-ASSERTION STUB. This task (the standing first slot) commits the shape of
 * the `/flightreports` screen — the selectors + the flow — so the later screen
 * tasks (T-09 picker/scaffold, T-10 results, T-11 custom builder) implement
 * against a fixed `data-testid` contract, and T-15 thickens these assertions to
 * the full happy/edge/key-error set from the parity oracle.
 *
 * T-09 (picker + scaffold) and T-10 (results page) have landed, so the picker
 * flow + the canned-results / location / Excel-export / empty-state cases now
 * run live against the mounted screen. The custom-builder flow (T-11) is still
 * `test.fixme` until that form lands; T-15 thickens every case to the full
 * happy/edge/key-error assertion set from the parity oracle. The SELECTOR-
 * CONTRACT manifest still runs as the typo guard between this spec and the
 * components.
 *
 * Booted under the `mock-auth` Angular configuration; the principal is a mocked
 * SYSTEM_ADMINISTRATOR. All `/api/v1/*` calls are intercepted via `page.route` —
 * no live backend (the real chain is the `real-idp/flight-reports-parity.spec.ts`
 * sibling + the journey gate).
 *
 * Flow shape (legacy `flsweb/src/reporting/`, oracle 2026-06-09):
 *   picker (person + location category tiles)
 *     → pick a canned report (my-flights-last-30-days)
 *       → results (filter-criteria panel + summary table + flights table)
 *     → custom builder (date range + flight-type toggles + person/location)
 *       → Apply → results
 *     → Excel export button → streamed .xlsx attachment
 *
 * Reference: docs/modernization/stories/J-7-flight-reports.md (§ Spec must assert).
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

// ── Canned report types the picker must link (oracle § Picker). The 30-day +
// this-year cases are the ones T-15 asserts the derived date-range for; the
// rest are listed so the picker tile-grid contract is complete. ──────────────
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

/**
 * The stable `data-testid` contract the screen tasks (T-09/T-10/T-11) implement.
 * THIS is the load-bearing artifact of the stub: the names below are the seam
 * between this spec and the not-yet-built components. Keep in sync with the spec
 * body; T-15 asserts the data flowing THROUGH these selectors.
 */
const TESTIDS = {
  // Picker (T-09)
  pickerPersonCategory: 'flightreports-category-person',
  pickerLocationCategory: 'flightreports-category-location',
  // tile id pattern: `flightreports-tile-<category>-<type>`
  tile: (category: 'person' | 'location', type: string) => `flightreports-tile-${category}-${type}`,
  // Results (T-10)
  filterCriteriaPanel: 'report-filter-criteria',
  summaryTable: 'report-summary-table',
  summaryRow: 'report-summary-row',
  flightsTable: 'report-flights-table',
  flightsRow: 'report-flights-row',
  emptyState: 'report-empty',
  excelExport: 'report-excel-export',
  // Custom builder (T-11)
  customForm: 'report-custom-form',
  customFrom: 'report-custom-from',
  customTo: 'report-custom-to',
  customGliderToggle: 'report-custom-glider',
  customMotorToggle: 'report-custom-motor',
  customTowToggle: 'report-custom-tow',
  customPersonSelect: 'report-custom-person',
  customLocationSelect: 'report-custom-location',
  customApply: 'report-custom-apply',
} as const;

// ── Mock report payloads (shape per oracle § Results — FlightReportResult). ──
// One grouped summary row + one flight data row, enough for the flow stub to
// render non-empty. T-15 replaces these with the canned-window + grouping-rule
// fixtures and thickens the assertions to the real values.
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
      groupBy: 'Pilot (Glider)',
      totalStarts: 1,
      totalLdgs: 1,
      totalFlights: 1,
      totalFlightDuration: '01:30',
    },
  ],
};

/** Stub the report page endpoint (POST /api/v1/flightreports/page/{start}/{size}). */
async function stubReportBackend(page: Page, result = mockReportResult): Promise<void> {
  await page.route('**/api/v1/flightreports/page/**', (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(result),
    }),
  );
  // Excel export endpoint — fulfil with a tiny attachment so the export button
  // triggers a real browser download (the spec asserts the `.xlsx` filename,
  // not the bytes; cell-parity is the backend harness's job, T-08).
  await page.route('**/api/v1/flightreports/export/excel/**', (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      headers: { 'content-disposition': 'attachment; filename="FlightReports.xlsx"' },
      body: 'PK',
    }),
  );
  // Masterdata the custom-builder selectors read (persons / locations). Empty
  // is fine for the shape stub; T-15 seeds pickable options.
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

test.describe('flight reports — screen shape (J-7 T-01 stub)', () => {
  // SELECTOR-CONTRACT MANIFEST — this is what runs today. It documents + asserts
  // the testid seam the screen tasks implement, so a rename drift is caught here.
  // No DOM is rendered (the screen is unbuilt); the assertion is on the contract
  // object itself so the stub has a green, meaningful anchor before T-09 lands.
  test('declares the screen-shape testid contract the screen tasks implement', () => {
    // Every canned type maps to a tile id (the picker grid contract).
    for (const t of PERSON_CANNED) {
      expect(TESTIDS.tile('person', t)).toBe(`flightreports-tile-person-${t}`);
    }
    for (const t of LOCATION_CANNED) {
      expect(TESTIDS.tile('location', t)).toBe(`flightreports-tile-location-${t}`);
    }
    // The results + custom-builder testids are present + non-empty (the seam).
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

  // ── FLOW STUBS — fixme until T-09/T-10/T-11 build the screen. They encode the
  // EXACT flow + selectors the screen must satisfy; flip `fixme`→`test` (and let
  // T-15 thicken) once the route mounts. ────────────────────────────────────

  test('picker renders person + location category tiles, each linking a canned :type', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports');

    await expect(page.getByTestId(TESTIDS.pickerPersonCategory)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.pickerLocationCategory)).toBeVisible();
    // A person tile + a location tile link to /flightreports/:category/:type.
    const personTile = page.getByTestId(TESTIDS.tile('person', 'my-flights-last-30-days'));
    await expect(personTile).toBeVisible();
    await expect(
      page.getByTestId(TESTIDS.tile('location', 'location-flights-this-year')),
    ).toBeVisible();

    await page.screenshot({ path: 'screenshots/reporting/01-picker.png', fullPage: true });
  });

  test('pick a canned person report → filter-criteria panel + summary table + flights table render', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/person/my-flights-last-30-days');

    // Filter-criteria panel reflects the derived range (T-15 asserts the
    // today−30 → today window + Glider/Motor on, Tow off).
    await expect(page.getByTestId(TESTIDS.filterCriteriaPanel)).toBeVisible();
    // Summary table has ≥1 grouped row.
    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.summaryRow).first()).toBeVisible();
    // Flights table has ≥1 data row.
    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsRow).first()).toBeVisible();

    await page.screenshot({
      path: 'screenshots/reporting/02-canned-results.png',
      fullPage: true,
    });
  });

  test('a canned location report scopes + groups by FlightTypeName', async ({ page }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/location/location-flights-this-year');

    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();
  });

  test('Excel export button streams an .xlsx attachment', async ({ page }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/person/my-flights-last-30-days');

    // The export button triggers a download of the streamed spreadsheet.
    const exportBtn = page.getByTestId(TESTIDS.excelExport);
    await expect(exportBtn).toBeVisible();
    const downloadPromise = page.waitForEvent('download');
    await exportBtn.click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.xlsx$/);
  });

  test('a filter matching no flights renders the empty-state copy', async ({ page }) => {
    await stubReportBackend(page, { items: [], summaries: [] });
    await page.goto('/flightreports/person/my-flights-today');

    await expect(page.getByTestId(TESTIDS.emptyState)).toBeVisible();
  });
});
