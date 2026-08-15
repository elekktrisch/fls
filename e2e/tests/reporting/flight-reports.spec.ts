
import { expect, gotoRoute, screenshot, test } from "../../fixtures";
import { testId } from "../../test-id";
import { ensureGliderFlight, getBearerToken } from "../../test-data";

test.setTimeout(90_000);

test("flight-reports: pre-canned location-this-year renders tabular output for seeded flights", async ({
  loggedInPage,
}, testInfo) => {
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  await ensureGliderFlight(loggedInPage.request, token, { comment: id.name });

  await gotoRoute(loggedInPage, "/flightreports");
  const myReportsLink = loggedInPage.locator(
    'a[href="#/flightreports/location/location-flights-this-year"]',
  );
  await expect(myReportsLink).toBeVisible({ timeout: 10_000 });

  await gotoRoute(
    loggedInPage,
    "/flightreports/location/location-flights-this-year",
  );

  const filterPanel = loggedInPage.locator(".filter-criteria-panel");
  await expect(filterPanel).toBeVisible({ timeout: 60_000 });

  const fromDate = filterPanel.locator(".filter-value").first();
  await expect(fromDate).not.toBeEmpty();

  const summaryTable = loggedInPage
    .locator("table.fls")
    .filter({
      has: loggedInPage.locator("th >> text=/Total|Anzahl|Starts/i"),
    })
    .first();
  await expect(summaryTable).toBeVisible({ timeout: 10_000 });

  const summaryRows = summaryTable
    .locator("tr")
    .filter({ has: loggedInPage.locator("td") });
  const summaryRowCount = await summaryRows.count();
  expect(
    summaryRowCount,
    "expected at least one FlightReportSummaries row for seeded flights in current year",
  ).toBeGreaterThanOrEqual(1);

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
