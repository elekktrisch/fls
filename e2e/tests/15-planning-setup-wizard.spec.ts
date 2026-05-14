/**
 * Spec #15: planning-setup-wizard  (PLAN.md row #15)
 *
 * Drives /planningsetup end-to-end and asserts it bulk-creates planning days.
 *
 * Wizard model (PlanningDaySetupController.js + planning-setup.html):
 *   - $scope.setup = { EverySaturday: true, EverySunday: true } (pre-set).
 *   - Clubs.query() pre-fills setup.LocationId = result.HomebaseId.
 *   - StartDate / EndDate are <fls-date-picker required>.
 *   - generate(setup) POSTs to /api/v1/planningdays/create/rule
 *     (PlanningService.PlanningDaysRuleBased.runSetup) then nav→ /planning.
 *
 * Strategy:
 *   1. freshDb baseline. _test-fixture.sql seeds NO PlanningDay rows (grep
 *      confirmed), so /planning starts empty.
 *   2. Open /planningsetup, wait for clubs + locations to hydrate
 *      ($scope.setup.LocationId is set async from HomebaseId).
 *   3. Mutate $scope.setup.StartDate / EndDate directly (same model-write
 *      pattern as 04-flights-create.spec.ts — Pikaday is awkward to type
 *      into; the ng-model binding is the source of truth).
 *   4. Submit, wait for /planning redirect, assert ≥ 1 row appeared.
 *
 * Range chosen: 2026-05-16 (Sat) .. 2026-05-31 (Sun), default Sat+Sun
 * recurrence preserved. PLAN.md row #15 only requires ≥ 1 created.
 *
 * Contract gaps (no shared infra modified — see SELECTORS.md):
 *   - TODO testid: `planning-setup-form` on the <form>, `planning-setup-submit`
 *     on the Generate button, `setup-startdate-input` / `setup-enddate-input`
 *     on the two <fls-date-picker> inputs. Falls back to semantic selectors.
 */

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const SETUP_ROUTE = '/planningsetup';
const PLANNING_LIST_ROUTE = '/planning';
const SETUP_FORM_SELECTOR = 'form[role="form"]';

// Saturday 2026-05-16 through Sunday 2026-05-31 — covers 3 Sat + 3 Sun under
// the default recurrence the controller pre-sets.
const START_DATE_ISO = '2026-05-16T00:00:00Z';
const END_DATE_ISO   = '2026-05-31T00:00:00Z';

async function waitForSetupReady(page: Page): Promise<void> {
  await page.locator(SETUP_FORM_SELECTOR).waitFor({ state: 'visible' });
  // The controller fetches Clubs.query() and Locations.getLocations()
  // asynchronously; both must resolve before submit so $scope.setup.LocationId
  // is populated (from result.HomebaseId) and the <selectize> location
  // dropdown's `options` are non-empty. The form's `required` on location
  // would otherwise block submit silently.
  await page.waitForFunction(() => {
    const w = window as unknown as {
      angular?: { element: (n: Element) => { scope: () => Record<string, unknown> } };
    };
    if (!w.angular) return false;
    const form = document.querySelector('form[role="form"]');
    if (!form) return false;
    const s = w.angular.element(form).scope() as {
      myClub?: { HomebaseId?: string };
      locations?: unknown[];
      setup?: { LocationId?: string };
    };
    return !!s && !!s.myClub && Array.isArray(s.locations) && s.locations.length > 0
      && !!s.setup && !!s.setup.LocationId;
  }, undefined, { timeout: 15_000 });
}

async function countPlanningRows(page: Page): Promise<number> {
  return page.locator('tbody [data-testid="row"]').count();
}

test('planning-setup:wizard bulk-creates planning days for date range', async ({ freshLoggedInPage: loggedInPage }) => {
  // freshDb: re-seeded DB. _test-fixture.sql does not seed any PlanningDay
  // rows (grep -in 'PlanningDay' finds only a notification-pref reference,
  // never an INSERT), so /planning starts empty for this worker.
  const page = loggedInPage;

  // 1. Baseline row count. The static seed (`4 or 5 Insert Test Data.sql`
  //    line ~1033) inserts a handful of PlanningDays for the test club, so
  //    the list is NOT empty after freshDb — we just diff after the wizard.
  await gotoRoute(page, PLANNING_LIST_ROUTE);
  const baseline = await countPlanningRows(page);

  // 2. Open the wizard and wait for its async hydrations to finish.
  await gotoRoute(page, SETUP_ROUTE);
  await waitForSetupReady(page);

  // 3. Inject the date range on the controller's scope. Pikaday + the
  //    fls-date-picker directive bind ng-model="setup.StartDate / .EndDate"
  //    to Date objects (DatePickerInputDirective:44 — `new Date(filteredDate + 'T00:00:00Z')`),
  //    so writing Date objects directly is faithful to the production flow.
  //    The default $scope.setup.EverySaturday / EverySunday booleans (set in
  //    the controller's constructor) stay true.
  const submitted = await page.evaluate(({ startIso, endIso }) => {
    const w = window as unknown as {
      angular: {
        element: (n: Element) => {
          scope: () => { setup: Record<string, unknown> };
          $apply: (fn?: () => void) => void;
        };
      };
    };
    const form = document.querySelector('form[role="form"]');
    if (!form) throw new Error('planning-setup form not found');
    const ngEl = w.angular.element(form);
    const s = ngEl.scope();
    s.setup.StartDate = new Date(startIso);
    s.setup.EndDate = new Date(endIso);
    ngEl.$apply();
    return {
      hasLocation: !!s.setup.LocationId,
      everySat: !!s.setup.EverySaturday,
      everySun: !!s.setup.EverySunday,
    };
  }, { startIso: START_DATE_ISO, endIso: END_DATE_ISO });

  expect(submitted.hasLocation, 'setup.LocationId should be auto-populated from myClub.HomebaseId').toBe(true);
  expect(submitted.everySat && submitted.everySun, 'default Sat+Sun recurrence should be preserved').toBe(true);

  // 4. Submit via the form's actual submit button (ng-submit="generate(setup)").
  await page.locator(`${SETUP_FORM_SELECTOR} button[type="submit"]`).click();

  // The controller's success path is $location.path('/planning'). Wait for
  // the hash route transition + the planning list to re-render.
  await page.waitForURL(/#\/planning(\?|$)/, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: 15_000 });

  // 5. The list defaults to a `Day.From = today` filter (PlanningDaysController:13).
  //    Our generated range is in May 2026 (≥ today on the current fixture
  //    clock 2026-05-14), so all newly created rows are within the default
  //    filter window. Assert at least one row appeared.
  await expect(
    page.locator('tbody [data-testid="row"]').first(),
    'at least one planning day row should appear after wizard submit',
  ).toBeVisible({ timeout: 15_000 });
  const after = await countPlanningRows(page);
  expect(after, `expected ≥1 planning day created (baseline=${baseline})`).toBeGreaterThanOrEqual(1);
  await screenshot(loggedInPage, '15-planning-setup-wizard-01');
});
