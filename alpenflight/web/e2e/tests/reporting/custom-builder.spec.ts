import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * J-7 T-01 — Custom-builder SCREEN-SHAPE stub (mock inner loop).
 *
 * THIN-ASSERTION STUB, sibling of `flight-reports.spec.ts`. Commits the shape of
 * the custom report builder (`/flightreports/custom/:category/:filter/edit`) —
 * the date-range inputs, the three flight-type toggles, the conditional
 * person/location selector, and the Apply → results flow — so T-11 implements
 * against a fixed `data-testid` contract and T-15 thickens the assertions to the
 * filter-round-trips-through-the-route-param case from the oracle.
 *
 * The flow case is `test.fixme` until T-11 builds the form (the route does not
 * mount yet). What runs today is the testid-contract assertion. Booted under
 * `mock-auth`; `/api/v1/*` intercepted via `page.route`.
 *
 * Custom builder shape (legacy `flightreport-custom-configuration.html`, oracle
 * § Custom builder): From/To date range + Glider/Motor/Tow checkboxes + a
 * conditional selector — LocationId (category=location) or FlightCrewPersonId
 * (category=person). Apply builds the route's JSON filter + calls the page
 * endpoint. Built to the as-you-type bar (debounced liveFieldErrors), kept
 * low-CRAP (T-11; no `*-edit.page.ts` form-mapping complexity replicated).
 *
 * Reference: docs/modernization/stories/J-7-flight-reports.md (§ Spec must assert).
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

// Custom-builder testids (the T-11 seam). Kept aligned with `flight-reports.spec.ts`.
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
  flightsTable: 'report-flights-table',
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

test.describe('flight reports custom builder — screen shape (J-7 T-01 stub)', () => {
  test('declares the custom-builder testid contract T-11 implements', () => {
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
    // The conditional selector differs by category (person ↔ location).
    expect(TESTIDS.customPersonSelect).not.toBe(TESTIDS.customLocationSelect);
  });

  test.fixme('custom builder: set date range + flight-type toggles + selector → Apply → results render + filter round-trips', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/custom/location/%7B%7D/edit');

    const form = page.getByTestId(TESTIDS.customForm);
    await expect(form).toBeVisible();

    // Date range (From/To).
    await page.getByTestId(TESTIDS.customFrom).fill('2026-01-01');
    await page.getByTestId(TESTIDS.customTo).fill('2026-12-31');

    // Flight-type toggles — Tow ON (defaults are Glider+Motor on, Tow off).
    await page.getByTestId(TESTIDS.customTowToggle).click();

    // Location category → location selector.
    await page.getByTestId(TESTIDS.customLocationSelect).click();

    // Apply builds the route filter param + calls the page endpoint.
    await page.getByTestId(TESTIDS.customApply).click();

    // Filter round-trips through the route param (T-15 asserts the encoded
    // From/To/flags/LocationId all reappear on the results URL).
    await expect(page).toHaveURL(/\/flightreports\/custom\/location\/.+\/(apply|view)/);
    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();

    await page.screenshot({
      path: 'screenshots/reporting/03-custom-results.png',
      fullPage: true,
    });
  });
});
