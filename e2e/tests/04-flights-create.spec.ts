/**
 * Spec #04: flights-create  (PLAN.md row #04)
 *
 * Drives the AngularJS glider-flight "new" form end-to-end:
 *   1. Open /flights, click the "+" toolbar button (no testid — we lean on
 *      semantic locators, see contract gaps below).
 *   2. Wait for the new-flight form at /flights/new to hydrate.
 *   3. Fill the minimum-required glider fields. The controller's
 *      `initForNewFlight` (FlightsController.js:190-215) pre-fills FlightDate,
 *      StartType, StartLocation, LdgLocation and FlightTypeId from the club's
 *      defaults — we only have to add Pilot, Aircraft, and start/landing
 *      times. Aircraft + Pilot are selectize widgets, which are notoriously
 *      hostile to Playwright; we mutate the AngularJS scope directly (same
 *      pattern as e2e/tests/09-public-flows.spec.ts).
 *   4. Submit. The controller's $scope.save -> $scope.cancel chain returns
 *      to NavigationCache.cancellingLocation which is set to /flights on
 *      entry — so we end up back on the list.
 *   5. Assert the freshly inserted flight appears in the list (row count
 *      increased, plus the comment we set on the new flight shows up).
 *
 * Contract gaps (no template edits made in this batch — see SELECTORS.md
 * "Note to the rewrite team"):
 *   - TODO testid: `flights-new-button` on the "+" toolbar button in
 *     flights.html. Currently no marker, so the spec locates it by
 *     `getByRole('button', { name: ... })` + a `fa-plus` fallback.
 *   - TODO testid: `flight-form` on the <form> in flight-edit-form.html
 *     (mirrors the public-form pattern: <X>-form / submit / success-message).
 *   - TODO testid: `flight-save` on the SAVE button so we don't have to
 *     locate it by translated label.
 */

import { test, expect, gotoRoute } from '../fixtures';
import type { Page } from '@playwright/test';

const FLIGHTS_LIST = '/flights';
const SECONDARY_TIMEOUT = 15_000;

// Anchor used by `_test-fixture.sql` (see SERVER.md sec. 2). We don't actually
// need to age this flight, so we'll just set FlightDate = today. The flights
// list defaults to a today-only filter (FlightsController.js:51-54), so the
// new row will appear without us having to widen the filter.

async function waitForFlightFormReady(page: Page): Promise<void> {
  // The form hosts a `fls-busy-indicator` (data-testid="busy-indicator") that's
  // visible while loadMasterdata() is in flight. Wait for it to clear.
  await page.locator('input#FlightDate').waitFor({ state: 'visible', timeout: SECONDARY_TIMEOUT });
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: SECONDARY_TIMEOUT });
  // Wait until the AngularJS scope has hydrated the dropdown master data —
  // gliderAircrafts + gliderPilots are what we need to drive the form.
  await page.waitForFunction(() => {
    const w = window as unknown as { angular?: { element: (n: Element) => { scope: () => Record<string, unknown> } } };
    if (!w.angular) return false;
    const formEl = document.querySelector('form[name="flightDetailsForm"]');
    if (!formEl) return false;
    const s = w.angular.element(formEl).scope() as {
      gliderAircrafts?: unknown[];
      gliderPilots?: unknown[];
      flightDetails?: { GliderFlightDetailsData?: unknown };
    };
    return Array.isArray(s.gliderAircrafts) && s.gliderAircrafts.length > 0
      && Array.isArray(s.gliderPilots) && s.gliderPilots.length > 0
      && !!s.flightDetails && !!s.flightDetails.GliderFlightDetailsData;
  }, undefined, { timeout: SECONDARY_TIMEOUT });
}

async function countTodayFlightRows(page: Page): Promise<number> {
  return page.locator('tbody [data-testid="row"]').count();
}

test('flights:create new glider flight via UI shows up in list', async ({ loggedInPage, freshDb }) => {
  // freshDb is the worker-scoped fixture (see fixtures.ts). It re-seeds the
  // FLSTest database to a deterministic baseline so we know exactly how many
  // rows are on the today-filtered list before we add ours: zero, because
  // every fixture flight is anchored 30 days before 2026-01-01.
  void freshDb;

  // 1. Land on the flights list and capture the baseline row count.
  await gotoRoute(loggedInPage, FLIGHTS_LIST);
  const baselineRows = await countTodayFlightRows(loggedInPage);

  // 2. Click the "+" new-flight button. No data-testid yet (see header
  //    block) — locate by the FontAwesome `fa-plus` glyph the template uses
  //    inside the toolbar (flights.html:17-20).
  const newButton = loggedInPage.locator('.fls-new-button button:has(span.fa-plus)');
  await expect(newButton, 'new-flight toolbar button must be visible on /flights').toBeVisible();
  await newButton.click();
  await loggedInPage.waitForURL(/#\/flights\/new$/, { timeout: SECONDARY_TIMEOUT });
  await waitForFlightFormReady(loggedInPage);

  // 3. Inject the required form values directly on the AngularJS scope. This
  //    is the same approach as 09-public-flows.spec.ts: selectize wraps the
  //    underlying control in a custom DOM tree that's painful to drive
  //    deterministically, and the model binding is the source of truth.
  const uniqueComment = `e2e-flight-${Date.now()}`;
  const flightInjection = await loggedInPage.evaluate((comment) => {
    const w = window as unknown as {
      angular: {
        element: (n: Element) => {
          scope: () => Record<string, unknown> & {
            flightDetails: {
              FlightDate?: Date | string;
              StartType?: string | number;
              GliderFlightDetailsData: Record<string, unknown>;
              TowFlightDetailsData?: Record<string, unknown> | null;
            };
            gliderAircrafts: Array<{ AircraftId: string; Immatriculation: string; NrOfSeats: number; HasEngine?: boolean }>;
            gliderPilots: Array<{ PersonId: string; Lastname: string }>;
            gliderFlightTypes: Array<{ FlightTypeId: string; FlightCode: string; IsPassengerFlight?: boolean; InstructorRequired?: boolean; ObserverPilotOrInstructorRequired?: boolean }>;
            times: { gliderStart: string; gliderLanding: string };
            gliderAircraftSelectionChanged: (reset?: boolean) => void;
            flightTypeChanged: () => void;
            startTypeChanged: () => void;
            formatGliderStart: () => void;
            formatGliderLanding: () => void;
          };
          $apply: () => void;
        };
      };
    };
    const formEl = document.querySelector('form[name="flightDetailsForm"]');
    if (!formEl) throw new Error('flightDetailsForm not found');
    const ngEl = w.angular.element(formEl);
    const s = ngEl.scope();

    // Pick a 2-seater glider WITHOUT an engine — avoids the engine-counter
    // required-field block in the form (HasEngine=false skips that section,
    // see flight-edit-glider-form.html:394+). Seed data: HB-3407 "Duo Discus"
    // (Schempp-Hirth, NrOfSeats=2) — see _test-fixture.sql:308.
    const glider = s.gliderAircrafts.find(a => a.Immatriculation === 'HB-3407')
      ?? s.gliderAircrafts.find(a => a.NrOfSeats >= 2 && !a.HasEngine)
      ?? s.gliderAircrafts[0];
    if (!glider) throw new Error('no glider aircraft seeded');

    // Pick a non-passenger, non-instructor-required, non-observer-required
    // flight type so the conditional pax/instructor fields stay hidden and
    // don't add new `required` constraints. Fall back to the controller's
    // pre-selected default if nothing matches.
    const flightType = s.gliderFlightTypes.find(t =>
      !t.IsPassengerFlight && !t.InstructorRequired && !t.ObserverPilotOrInstructorRequired,
    ) ?? s.gliderFlightTypes[0];

    const pilot = s.gliderPilots[0];
    if (!pilot) throw new Error('no glider pilot seeded');

    // Today; the today-only list filter on /flights will surface this row.
    s.flightDetails.FlightDate = new Date();
    // StartType = 3 -> Self-launch; needsTowplane() returns true only for
    // StartType == 1 (FlightsController.js:419), so 3 keeps the tow form
    // optional and we avoid touching TowFlightDetailsData.
    s.flightDetails.StartType = '3';

    const gld = s.flightDetails.GliderFlightDetailsData;
    gld.AircraftId = glider.AircraftId;
    gld.PilotPersonId = pilot.PersonId;
    if (flightType) gld.FlightTypeId = flightType.FlightTypeId;
    gld.NrOfLdgs = 1;
    gld.FlightComment = comment;
    // IsSoloFlight=true so single-seater requirements collapse and we don't
    // need a co-pilot. (For HB-3407 NrOfSeats=2 this is also fine.)
    gld.IsSoloFlight = true;

    // Times are bound to $scope.times, not directly on flightDetails.
    s.times.gliderStart = '10:00';
    s.times.gliderLanding = '10:30';

    // Tow data is irrelevant for self-launch — clear any auto-populated
    // TowFlightDetailsData so prepareForSaving sees a falsy AircraftId and
    // drops the whole tow block (FlightsController.js:375-377).
    s.flightDetails.TowFlightDetailsData = {};

    // Re-run the controller hooks that update derived state.
    s.startTypeChanged();
    s.gliderAircraftSelectionChanged(false);
    s.flightTypeChanged();
    s.formatGliderStart();
    s.formatGliderLanding();
    ngEl.$apply();

    return {
      aircraft: glider.Immatriculation,
      pilot: pilot.Lastname,
      flightTypeCode: flightType?.FlightCode ?? null,
    };
  }, uniqueComment);

  test.info().annotations.push({
    type: 'fixture-selection',
    description: `glider=${flightInjection.aircraft} pilot=${flightInjection.pilot} flightCode=${flightInjection.flightTypeCode}`,
  });

  // 4. Submit. There's no testid on the SAVE button, so locate the form's
  //    submit button (the only type="submit" inside the flight form).
  const submitButton = loggedInPage.locator('form[name="flightDetailsForm"] button[type="submit"]').first();
  await expect(submitButton, 'SAVE button should be enabled once the form is valid').toBeEnabled({ timeout: SECONDARY_TIMEOUT });
  await submitButton.click();

  // The controller's save() -> cancel() chain navigates to
  // NavigationCache.cancellingLocation, which was set to /flights when the
  // list controller first mounted.
  await loggedInPage.waitForURL(/#\/flights(\?|$)/, { timeout: SECONDARY_TIMEOUT });
  await loggedInPage.waitForLoadState('domcontentloaded');
  await loggedInPage.waitForTimeout(500);
  await loggedInPage.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: SECONDARY_TIMEOUT });

  // 5. Assert: row count went up by exactly one AND our unique comment is
  //    rendered in the list. The comment column is on tbody rows under the
  //    `FlightComment` ng-bind (flights.html:123-128).
  const afterRows = await countTodayFlightRows(loggedInPage);
  expect(afterRows, `expected list to grow by one (was ${baselineRows})`).toBe(baselineRows + 1);
  await expect(
    loggedInPage.locator(`tbody [data-testid="row"]:has-text("${uniqueComment}")`),
    'newly-created flight row should be visible in the today list',
  ).toHaveCount(1);
});
