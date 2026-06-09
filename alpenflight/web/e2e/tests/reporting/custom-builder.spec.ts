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

  test('custom builder: set date range + flight-type toggles + selector → Apply → results render + filter round-trips', async ({
    page,
  }) => {
    await stubReportBackend(page);
    await page.goto('/flightreports/custom/location/%7B%7D/edit');

    const form = page.getByTestId(TESTIDS.customForm);
    await expect(form).toBeVisible();

    // The location category renders the location selector, not the person one.
    await expect(page.getByTestId(TESTIDS.customLocationSelect)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.customPersonSelect)).toHaveCount(0);

    // Date range (From/To) — the testid is on the <af-input>; fill the inner input.
    await page.getByTestId(TESTIDS.customFrom).locator('input').fill('2026-01-01');
    await page.getByTestId(TESTIDS.customTo).locator('input').fill('2026-12-31');

    // Flight-type toggles — Tow ON (defaults are Glider+Motor on, Tow off).
    await page.getByTestId(TESTIDS.customTowToggle).check();

    await page.screenshot({ path: 'screenshots/reporting/03-custom-builder.png', fullPage: true });

    // Apply encodes the filter into the route + navigates to the apply view.
    await page.getByTestId(TESTIDS.customApply).locator('button').click();

    // Filter round-trips through the route param: the encoded segment carries
    // the From/To/flags so the results URL reflects the applied filter (the AC).
    await expect(page).toHaveURL(/\/flightreports\/custom\/location\/.+\/(apply|view)/);
    const url = new URL(page.url());
    let encoded = url.pathname.split('/').at(-2) ?? '';
    // The router percent-encodes the path segment; `URL.pathname` leaves it
    // encoded — decode until it parses as the filter JSON (one or two passes).
    let roundTripped: Record<string, unknown> | null = null;
    for (let i = 0; i < 3 && roundTripped === null; i++) {
      try {
        roundTripped = JSON.parse(encoded) as Record<string, unknown>;
      } catch {
        encoded = decodeURIComponent(encoded);
      }
    }
    expect(roundTripped).not.toBeNull();
    roundTripped = roundTripped ?? {};
    expect(roundTripped.flightDateFrom).toBe('2026-01-01');
    expect(roundTripped.flightDateTo).toBe('2026-12-31');
    expect(roundTripped.towFlights).toBe(true);

    // The results view renders the filtered set from the decoded filter.
    await expect(page.getByTestId(TESTIDS.summaryTable)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();

    await page.screenshot({
      path: 'screenshots/reporting/04-custom-results.png',
      fullPage: true,
    });
  });
});
