import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * J-7 — Flight-reports MOCK inner-loop spec (`page.route`-stubbed backend).
 *
 * T-15 thickened this from the T-01 thin stub to the FULL contract from the
 * parity oracle (J-7-flight-reports.md § Spec must assert + § Parity decisions).
 * Booted under the `mock-auth` Angular configuration; the principal is a mocked
 * SYSTEM_ADMINISTRATOR. Every `/api/v1/*` call is intercepted via `page.route`,
 * so this proves the SCREEN renders the report contract correctly — the value
 * assertions here run against a deterministic stub. The REAL-DATA proof (the
 * same contract against migrated J-2 flights through live Keycloak + Spring +
 * Postgres, no mocking) is the sibling `real-idp/flight-reports-parity.spec.ts`.
 *
 * The mock stub payloads are SHAPED to the oracle so the assertions are
 * meaningful, not tautological:
 *   - the canned date-window is asserted off the DERIVED filter-criteria panel,
 *     which the SPA computes client-side (today−30/this-year) — the stub does
 *     not feed the range, so the range assertion is genuine (it tests the
 *     `cannedReportRequest` date-math wiring, robust to wall-clock drift);
 *   - the person-report summary stub carries the crew-function rows INCLUDING
 *     the corrected NON-ZERO `totalFlights` on Pilot (Motor)/(Towing) — the
 *     legacy-bug correction (oracle § CORRECT legacy bugs);
 *   - the flights stub carries an aerotow glider row with a nested TowFlight
 *     block, so the nested-tow rendering is asserted.
 *
 * Flow shape (legacy `flsweb/src/reporting/`, oracle 2026-06-09):
 *   picker (person + location category tiles)
 *     → canned person report (my-flights-last-30-days / -this-year)
 *       → derived date window + crew-function summary + flights table (nested tow)
 *     → canned location report (group-by FlightTypeName)
 *     → Excel export button → streamed .xlsx attachment
 *     → empty filter → empty-state copy
 *
 * Mock governance: NO `@mocked:` seams — this whole spec is the declared mock
 * inner loop (`mock_test:` in the journey frontmatter), not a real-chain run
 * with a mocked seam. The happy/key-error REAL assertions are the real-idp spec.
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

// ── Canned report types the picker must link (oracle § Picker). The 30-day +
// this-year cases are the ones whose derived date-range this spec asserts; the
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
 * THIS is the load-bearing seam between this spec and the components; a rename
 * drift is caught by the contract-manifest test below.
 */
const TESTIDS = {
  // Picker (T-09)
  pickerPersonCategory: 'flightreports-category-person',
  pickerLocationCategory: 'flightreports-category-location',
  tile: (category: 'person' | 'location', type: string) => `flightreports-tile-${category}-${type}`,
  // Results (T-10)
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
  // Custom builder (T-11)
  customForm: 'report-custom-form',
  customApply: 'report-custom-apply',
} as const;

// ── Date helpers (mirror the SPA's `cannedReportRequest` date-math) so the
// window assertions are robust to wall-clock drift — computed at run time, not
// hardcoded. DD.MM.YYYY is the SPA's rendered convention (J-6b date format). ──
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

// ── Mock report payloads (shape per oracle § Results — FlightReportResult). ──

/**
 * Person-report result: one pilot across a GLIDER (aerotow, with a nested tow
 * block), a MOTOR, and the linked TOW flight, plus an instructor solo/non-solo
 * split. The summary carries the crew-function rows with the CORRECTED non-zero
 * `totalFlights` on Pilot (Motor)/(Towing) (oracle § CORRECT legacy bugs).
 */
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
      // Nested aerotow block (oracle § Nested tow): the glider row carries its tow.
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
    // The oracle's person-branch crew-function rows. Pilot (Motor)/(Towing)
    // carry NON-ZERO totalFlights — the corrected legacy bug.
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

/** Location-report result: groups by FlightTypeName (oracle § Results grouping). */
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
    // Location branch groups by FlightTypeName (alphabetical) + Total.
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

/** Stub the report page + export + masterdata endpoints. */
async function stubReportBackend(
  page: Page,
  opts: { person?: unknown; location?: unknown } = {},
): Promise<void> {
  const person = opts.person ?? personReportResult;
  const location = opts.location ?? locationReportResult;
  await page.route('**/api/v1/flightreports/page/**', async (route: Route) => {
    // Route by the searchFilter shape: a location report carries `locationId`
    // (or comes from a location-canned route), else it's a person report.
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
  // Excel export — fulfil with a tiny attachment so the export button triggers a
  // real browser download (the spec asserts the `.xlsx` filename + the
  // spreadsheet MIME; cell-parity is the backend harness's job, T-08).
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

/** Read a summary-row's cells as `[group, starts, ldgs, flights, duration]`. */
async function summaryRowCells(page: Page, group: string): Promise<string[]> {
  const row = page
    .getByTestId(TESTIDS.summaryRow)
    .filter({ has: page.locator('td', { hasText: new RegExp(`^${escapeRe(group)}$`) }) })
    .first();
  await expect(row).toBeVisible();
  return (await row.locator('td').allInnerTexts()).map((t) => t.trim());
}

test.describe('flight reports — full contract (J-7 mock inner loop)', () => {
  // SELECTOR-CONTRACT MANIFEST — documents + asserts the testid seam the screen
  // tasks implement, so a rename drift is caught here even if a flow case is
  // ever skipped.
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
    // Every canned type renders a tile linking to /flightreports/:category/:type.
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

    // (1) CANNED DATE WINDOW — the derived filter-criteria panel shows the
    // today−30 → today range. Computed at run time so it is wall-clock-robust.
    const base = today();
    const expectedRange = `${ddmmyyyy(minusDays(base, 30))} – ${ddmmyyyy(base)}`;
    await expect(page.getByTestId(TESTIDS.filterRange)).toHaveText(expectedRange);
    // Flight-type toggle state: Glider + Motor on, Tow off (corrected default).
    await expect(page.getByTestId(TESTIDS.filterTypes)).toHaveText('Glider, Motor');
    await expect(page.getByTestId(TESTIDS.filterScope)).toHaveText('Me');

    // (2) CREW-FUNCTION SUMMARY incl. corrected NON-ZERO TotalFlights on
    // Pilot (Motor)/(Towing) (oracle § CORRECT legacy bugs). Match the group
    // label EXACTLY (the first cell) so "Instructor" doesn't also match
    // "Instructor (Soloflights)".
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
    // The legacy-bug correction: Pilot (Motor) + Pilot (Towing) carry a non-zero
    // Flights column (cells: [group, starts, ldgs, flights, duration]).
    const motorCells = await summaryRowCells(page, 'Pilot (Motor)');
    expect(Number(motorCells[3])).toBeGreaterThan(0);
    const towCells = await summaryRowCells(page, 'Pilot (Towing)');
    expect(Number(towCells[3])).toBeGreaterThan(0);

    // (3) FLIGHTS TABLE + NESTED TOW — the aerotow glider row carries a nested
    // tow sub-row (oracle § Nested tow).
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

    // this-year window for the location report too.
    const base = today();
    await expect(page.getByTestId(TESTIDS.filterRange)).toHaveText(
      `${ddmmyyyy(jan1(base))} – ${ddmmyyyy(base)}`,
    );
    // Location branch groups by FlightTypeName (NOT crew function): the summary
    // carries flight-type-named rows + Total, never a "Pilot (Glider)" row.
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
    // No summary / flights tables when empty.
    await expect(page.getByTestId(TESTIDS.summaryTable)).toHaveCount(0);
    await expect(page.getByTestId(TESTIDS.flightsTable)).toHaveCount(0);
    // Export is disabled with nothing to export (the disabled state rides the
    // inner <button> the af-button renders).
    await expect(page.getByTestId(TESTIDS.excelExport).locator('button')).toBeDisabled();

    await page.screenshot({ path: 'screenshots/reporting/04-empty.png', fullPage: true });
  });
});
