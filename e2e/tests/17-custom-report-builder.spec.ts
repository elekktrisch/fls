/**
 * Spec #17: custom-report-builder  (PLAN.md row #17)
 *
 * Drives the /flightreports/custom/:category/:filter/:mode branch of the
 * FlightReports controller (flsweb/src/reporting/FlightReportsModule.js):
 *
 *   /flightreports/custom/:category/:filter/edit   -> configuration page
 *   /flightreports/custom/:category/:filter/apply  -> result page
 *
 * The flow:
 *   1. Land on the edit page with an empty filter `{}` (URL-encoded into the
 *      :filter route param — controller parses it via JSON.parse).
 *   2. Wait for the controller to hydrate dropdown master data ($scope.md.persons
 *      / $scope.md.locations are populated via the $q.all in
 *      FlightReportsController.js:91-107).
 *   3. Mutate $scope.custom directly to set a date range that covers the
 *      seeded historical flight (anchor - 30d = 2025-12-02 per
 *      _test-fixture.sql:303) + flight-type flags + LocationId. The
 *      `fls-date-range-picker` directive is bound via ng-model="custom.FlightDate",
 *      so writing the model wins (same pattern used by 04-flights-create.spec.ts
 *      for selectize controls).
 *   4. Click the OK button (translation key "OK"). $scope.applyCriteria()
 *      JSON-stringifies $scope.custom and navigates to .../apply, which is
 *      where the report actually runs.
 *   5. Wait for the busy indicator to clear and assert:
 *      (a) the URL transitioned to /apply,
 *      (b) the filter-criteria panel rendered with the configured From date,
 *      (c) the FlightReportSummaries table has at least one row OR the
 *          flights ng-table has at least one row. (Conservative — the
 *          regression target is "the page rendered after submit", not the
 *          specific number of flights.)
 *
 * Conservative on purpose (complexity L per PLAN.md): we deliberately do NOT
 * exercise the selectize person/location pickers via DOM clicks. Scope
 * injection is the model source of truth and survives Angular digest races.
 *
 * Contract gaps (no template edits in this batch — see SELECTORS.md "Note to
 * the rewrite team"):
 *   - TODO testid: `report-config-form` on the .filter-criteria-panel <div>
 *     in flightreport-custom-configuration.html.
 *   - TODO testid: `report-apply` on the OK button (currently located by
 *     ng-click="applyCriteria()" attribute selector).
 *   - TODO testid: `report-summary-table` on the summary <table> and
 *     `report-results` on the results ng-table in flightreportresults.html.
 */

import { test, expect, gotoRoute, screenshot } from '../fixtures';
import type { Page } from '@playwright/test';

const SECONDARY_TIMEOUT = 15_000;

// Category "location" — the seeded historical flight (HB-3407, 2025-12-02)
// has StartLocationId/LdgLocationId = @lszk (the homebase). The controller's
// initial branch for category=location surfaces the LocationId picker.
const CATEGORY = 'location';

// The fixture anchor is 2026-01-01; the historical flight sits at anchor - 30d
// = 2025-12-02. Pick a generous window that brackets it without depending on
// the current wall clock.
const FROM_DATE = '2025-01-01';
const TO_DATE = '2026-12-31';

async function waitForConfigScopeReady(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const w = window as unknown as {
      angular?: { element: (n: Element) => { scope: () => Record<string, unknown> } };
    };
    if (!w.angular) return false;
    const panel = document.querySelector('.filter-criteria-panel');
    if (!panel) return false;
    const s = w.angular.element(panel).scope() as {
      custom?: Record<string, unknown>;
      md?: { persons?: unknown[]; locations?: unknown[] };
      myClub?: { HomebaseId?: string };
    };
    return !!s
      && !!s.custom
      && !!s.md
      && Array.isArray(s.md.locations) && s.md.locations.length > 0
      && !!s.myClub && !!s.myClub.HomebaseId;
  }, undefined, { timeout: SECONDARY_TIMEOUT });
}

test('flightreports:custom builder applies filter and renders results', async ({ freshLoggedInPage: loggedInPage }) => {
  // freshDb gives us the deterministic 2026-01-01 anchor with the seeded
  // historical glider flight at 2025-12-02 (see _test-fixture.sql:295-357).

  // 1. Navigate to the custom-config edit page. AngularJS's $location.path()
  //    URL-encodes `{}` to `%7B%7D`, so do the same here.
  await gotoRoute(loggedInPage, `/flightreports/custom/${CATEGORY}/%7B%7D/edit`);
  await waitForConfigScopeReady(loggedInPage);
  await screenshot(loggedInPage, 'flightreports-custom-edit');

  // 2. Inject the filter onto $scope.custom. The date-range-picker is bound
  //    via ng-model="custom.FlightDate", and the location selectize is bound
  //    via ng-model="custom.LocationId" — writing the model directly is the
  //    safe path past both widgets.
  const injection = await loggedInPage.evaluate(({ from, to }) => {
    const w = window as unknown as {
      angular: {
        element: (n: Element) => {
          scope: () => {
            custom: Record<string, unknown>;
            myClub: { HomebaseId?: string };
            md: { locations: Array<{ LocationId: string; IcaoCode?: string }> };
          };
          $apply: () => void;
        };
      };
    };
    const panel = document.querySelector('.filter-criteria-panel');
    if (!panel) throw new Error('filter-criteria-panel not found');
    const ngEl = w.angular.element(panel);
    const s = ngEl.scope();
    s.custom = s.custom || {};
    s.custom.FlightDate = { From: from, To: to };
    s.custom.GliderFlights = true;
    s.custom.MotorFlights = true;
    s.custom.TowFlights = true;
    // Filter on the club's homebase — covers the seeded historical flight.
    s.custom.LocationId = s.myClub.HomebaseId;
    ngEl.$apply();
    return { locationId: s.custom.LocationId as string };
  }, { from: FROM_DATE, to: TO_DATE });

  expect(injection.locationId, 'club homebase id should resolve from scope').toBeTruthy();

  // 3. Submit the criteria. The OK button calls applyCriteria(); no testid
  //    yet (see header), so target the ng-click attribute directly.
  const okButton = loggedInPage.locator('button[ng-click="applyCriteria()"]').first();
  await expect(okButton, 'OK / apply button must be visible on edit page').toBeVisible();
  await okButton.click();

  // 4. The controller pushes #/flightreports/custom/<category>/<json>/apply.
  //    Wait for the URL transition, then for the result-page busy indicator.
  await loggedInPage.waitForURL(/#\/flightreports\/custom\/.+\/apply$/, { timeout: SECONDARY_TIMEOUT });
  await loggedInPage.waitForLoadState('domcontentloaded');
  await loggedInPage.waitForTimeout(500);
  await loggedInPage.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: SECONDARY_TIMEOUT });

  // 5a. Filter-criteria panel rendered with the configured date.
  //     `FlightReportFilterCriteria.FlightDate.From | date:'dd.MM.yyyy'` —
  //     2025-01-01 -> "01.01.2025".
  const fromCell = loggedInPage.locator('.filter-criteria-panel .filter-value').first();
  await expect(fromCell, 'filter-criteria panel must render the From date').toBeVisible();
  await expect(fromCell).toHaveText(/01\.01\.2025/);

  // 5b. The result page should render *some* tabular content — either a
  //     summary row or a flights ng-table row. The seeded historical flight
  //     falls inside the date window + homebase filter, so we expect at
  //     least one of these to be non-empty. Conservative OR-assertion
  //     because the rules engine / aggregation logic is out of scope for
  //     this spec — we're verifying the builder pipeline, not the report
  //     contents.
  const summaryRows = loggedInPage.locator('table.fls').first().locator('tr').filter({
    has: loggedInPage.locator('td'),
  });
  const flightRows = loggedInPage.locator('table[ng-table="tableParams"] tbody tr').filter({
    has: loggedInPage.locator('td'),
  });
  const summaryCount = await summaryRows.count();
  const flightCount = await flightRows.count();
  expect(
    summaryCount + flightCount,
    `expected at least one row in summary (${summaryCount}) or flights (${flightCount}) table after applying the filter`,
  ).toBeGreaterThan(0);

  await screenshot(loggedInPage, 'flightreports-custom-results');
});
