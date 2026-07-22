// e2e/tests/16-flight-reports-generation.spec.ts
//
// Plan row #16: Per-pilot / pre-canned flight report renders with data
// derived from seeded flights.
//
// Strategy
// --------
// The /flightreports picker (flsweb/src/reporting/flightreports.html) is a
// grid of anchor tags that link to /flightreports/<category>/<type>; the
// controller (FlightReportsController.js) maps :type to a date-range filter
// (today / yesterday / this year / previous year / last N days / last N
// months) and POSTs `/api/v1/flightreports/page/<start>/<size>` to populate
// an ng-table + a `FlightReportSummaries` table (see flightreportresults.html).
//
// We exercise both surfaces:
//   1. Verify the picker page renders (at least one report link visible).
//   2. Drive a pre-canned report. We use `location-flights-this-year`, whose
//      server filter counts only flights whose StartLocationId/LdgLocationId
//      equals the club's HomebaseId (FlightReportService location branch). The
//      seeded flight is therefore anchored to the club's ACTUAL homebase (see
//      ensureGliderFlight) and dated today, so the year-wide window counts it
//      regardless of wall-clock drift or which location is currently homebase.
//   3. Assert the rendered tables: filter-criteria panel populated,
//      summaries table has >=1 grouped row, and the flights ng-table has
//      >=1 data row. HighCharts is out of scope (the controller hides it
//      anyway -- `$scope.showChart = false`).
//
// Selector / testid notes
// -----------------------
// flightreportresults.html has no data-testid markers on its two tables
// (the summary one is a static `<table class="fls">`, the flights one is
// ng-table's `<table ng-table="tableParams">`). The summary table is the
// only `table.fls` that is NOT an ng-table on this view, so we anchor to
// it via `table.fls:not([ng-table])`. The flights table is the `ng-table`.
// Both .row counts skip header rows by filtering for cells with rendered
// text bound from the controller (`flight-cells with td[ng-bind]`).
//
// TODO testid: add `data-testid="report-summary-table"`,
//              `data-testid="report-flights-table"` and `row` on each
// `<tr ng-repeat>` in flightreportresults.html so this spec can lean on
// the existing `[data-testid="row"]` contract from SELECTORS.md instead of
// shape-based selectors.

import { expect, gotoRoute, screenshot, test } from "../../fixtures";
import { testId } from "../../test-id";
import { ensureGliderFlight, getBearerToken } from "../../test-data";

// Report page calls /api/v1/flightreports/page which scans every flight in
// the club; under accumulated legacy-suite state a single render can take > 30s.
// One re-navigate poll cycle plus the initial picker + report render fits well
// inside this budget; the earlier 240s was a latent 10-min-cap bomb (this is a
// data assertion, not a slow one).
test.setTimeout(90_000);

test("flight-reports: pre-canned location-this-year renders tabular output for seeded flights", async ({
  loggedInPage,
}, testInfo) => {
  // Self-contained: ensure at least one glider flight from THIS test exists
  // for the current year, anchored to the club's homebase (ensureGliderFlight
  // resolves it from /clubs/my) so the location-scoped report genuinely counts
  // it. Don't depend on seed-fixture flights surviving the accumulating state.
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  await ensureGliderFlight(loggedInPage.request, token, { comment: id.name });

  // 1. Picker page — landing for /flightreports. Confirms the route loads
  //    and the navigation links the controller's switch-case maps from.
  await gotoRoute(loggedInPage, "/flightreports");
  const myReportsLink = loggedInPage.locator(
    'a[href="#/flightreports/location/location-flights-this-year"]',
  );
  await expect(myReportsLink).toBeVisible({ timeout: 10_000 });

  // 2. Drive the pre-canned "location flights this year" report. Direct
  //    navigation avoids a Bootstrap stacking-context click race on the
  //    icon-stack anchor.
  await gotoRoute(
    loggedInPage,
    "/flightreports/location/location-flights-this-year",
  );

  // 3. Filter-criteria panel: ng-show="!!FlightReportFilterCriteria"
  //    becomes truthy once /api/v1/flightreports/page resolves. That
  //    endpoint scans all club flights and can take long under load.
  const filterPanel = loggedInPage.locator(".filter-criteria-panel");
  await expect(filterPanel).toBeVisible({ timeout: 60_000 });

  // From the filter criteria block: From/To/GliderFlights/MotorFlights/
  // TowFlights labels are visible regardless of locale (we read by *value*
  // rendered through ng-bind / translate filters, not by labels).
  const fromDate = filterPanel.locator(".filter-value").first();
  await expect(fromDate).not.toBeEmpty();

  // 4. Flight Report Summary table. The template renders a non-ng-table
  //    `<table class="fls">` with the summary header + one row per
  //    `FlightReportSummaries` entry. Filter for rows that have a populated
  //    `TotalFlights` cell (ng-bind populates it; header row has none).
  const summaryTable = loggedInPage
    .locator("table.fls")
    .filter({
      has: loggedInPage.locator("th >> text=/Total|Anzahl|Starts/i"),
    })
    .first();
  await expect(summaryTable).toBeVisible({ timeout: 10_000 });

  // Each summary row has 5 `<td ng-bind="...">` cells. Header rows have
  // `<th>`, not `<td>`. So counting `tr` that contain `>=1 <td>` excludes
  // the header. We also require at least one of the rows to surface a
  // non-zero TotalFlights count (the 4th `<td>` per template) to prove the
  // assertion is data-derived and not just empty placeholders.
  const summaryRows = summaryTable
    .locator("tr")
    .filter({ has: loggedInPage.locator("td") });
  const summaryRowCount = await summaryRows.count();
  expect(
    summaryRowCount,
    "expected at least one FlightReportSummaries row for seeded flights in current year",
  ).toBeGreaterThanOrEqual(1);

  // The seeded glider flight is dated today at the club's homebase, so it falls
  // inside this report's window (FlightDate startOf('year')..today) AND matches
  // the location branch's StartLocationId/LdgLocationId == HomebaseId filter
  // (FlightReportService), so the TotalFlights column (4th td, summary.TotalFlights
  // — flightreportresults.html:81) MUST reach >=1. Re-navigate between polls so a
  // slow /api/v1/flightreports/page render (accumulating club scan) forces a fresh
  // getData rather than spinning on a still-loading DOM.
  await expect
    .poll(
      async () => {
        await gotoRoute(
          loggedInPage,
          "/flightreports/location/location-flights-this-year",
        );
        await expect(filterPanel).toBeVisible({ timeout: 60_000 });
        const freshTotals = loggedInPage
          .locator("table.fls")
          .filter({
            has: loggedInPage.locator("th >> text=/Total|Anzahl|Starts/i"),
          })
          .first()
          .locator("tr")
          .filter({ has: loggedInPage.locator("td") })
          .locator("td:nth-child(4)");
        const totalsText = await freshTotals.allInnerTexts();
        return totalsText
          .map((s) => parseInt(s.trim(), 10))
          .filter((n) => !Number.isNaN(n))
          .reduce((a, b) => a + b, 0);
      },
      {
        message:
          "expected at least one flight in the per-group totals (seeded glider at club homebase)",
        timeout: 20_000,
        intervals: [2_000, 5_000],
      },
    )
    .toBeGreaterThanOrEqual(1);

  // 5. Flights ng-table: per-flight rows under `<table ng-table="tableParams">`.
  //    Filter to <tr> that contain at least one rendered `td[ng-bind]` so we
  //    skip ng-table's auto-generated header / filter / pager rows.
  const flightsTable = loggedInPage.locator('table[ng-table="tableParams"]');
  await expect(flightsTable).toBeVisible({ timeout: 10_000 });
  const flightRows = flightsTable.locator("tbody tr").filter({
    has: loggedInPage.locator("td[ng-bind]"),
  });
  await expect
    .poll(async () => flightRows.count(), {
      message: "expected at least one flight row rendered from seeded data",
      timeout: 10_000,
    })
    .toBeGreaterThanOrEqual(1);

  // 6. Spot-check: at least one flight row carries a non-empty pilot-name
  //    cell. The flight table renders `flight.PilotName` via `td[ng-bind="flight.PilotName"]`.
  const pilotNames = await flightsTable
    .locator('tbody td[ng-bind="flight.PilotName"]')
    .allInnerTexts();
  const hasPilot = pilotNames.some((t) => t.trim().length > 0);
  expect(
    hasPilot,
    "expected at least one rendered PilotName cell to be non-empty",
  ).toBe(true);
  await screenshot(loggedInPage, "flight-reports-01");
});
