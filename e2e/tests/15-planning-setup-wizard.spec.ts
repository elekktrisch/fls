// Spec #15: /planningsetup wizard. Submit Sat+Sun recurrence over a date
// range, assert ≥1 planning-day row appears.
//
// TODO testid: `planning-setup-form`, `planning-setup-submit`,
// `setup-startdate-input`, `setup-enddate-input`.

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
  // Controller uses Clubs.query() (buggy — see TEST_WRITING.md §6); wait for
  // $scope.locations only, we'll set LocationId ourselves.
  await page.waitForFunction(() => {
    const w = window as unknown as {
      angular?: { element: (n: Element) => { scope: () => Record<string, unknown> } };
    };
    if (!w.angular) return false;
    const form = document.querySelector('form[role="form"]');
    if (!form) return false;
    const s = w.angular.element(form).scope() as {
      locations?: unknown[];
      setup?: { LocationId?: string };
    };
    return !!s && Array.isArray(s.locations) && s.locations.length > 0 && !!s.setup;
  }, undefined, { timeout: 30_000 });
}

async function countPlanningRows(page: Page): Promise<number> {
  return page.locator('tbody [data-testid="row"]').count();
}

test('planning-setup:wizard bulk-creates planning days for date range', async ({ loggedInPage }) => {
  const page = loggedInPage;

  await gotoRoute(page, PLANNING_LIST_ROUTE);
  const baseline = await countPlanningRows(page);

  await gotoRoute(page, SETUP_ROUTE);
  await waitForSetupReady(page);

  // Inject date range + LocationId on $scope (Pikaday + selectize aren't drivable).
  const submitted = await page.evaluate(({ startIso, endIso }) => {
    const w = window as unknown as {
      angular: {
        element: (n: Element) => {
          scope: () => {
            setup: Record<string, unknown>;
            locations: Array<{ LocationId: string; IcaoCode?: string }>;
            $apply: (fn?: () => void) => void;
          };
        };
      };
    };
    const form = document.querySelector('form[role="form"]');
    if (!form) throw new Error('planning-setup form not found');
    const s = w.angular.element(form).scope();
    s.setup.StartDate = new Date(startIso);
    s.setup.EndDate = new Date(endIso);
    if (!s.setup.LocationId) {
      const loc = s.locations.find(l => l.IcaoCode === 'LSZK') ?? s.locations[0];
      s.setup.LocationId = loc.LocationId;
    }
    // $apply lives on the scope, not on the element wrapper — see TEST_WRITING.md §6.
    s.$apply();
    return {
      hasLocation: !!s.setup.LocationId,
      everySat: !!s.setup.EverySaturday,
      everySun: !!s.setup.EverySunday,
    };
  }, { startIso: START_DATE_ISO, endIso: END_DATE_ISO });

  expect(submitted.hasLocation, 'setup.LocationId should be set (we inject LSZK if controller did not)').toBe(true);
  expect(submitted.everySat && submitted.everySun, 'default Sat+Sun recurrence should be preserved').toBe(true);

  // 4. Submit via the form's actual submit button (ng-submit="generate(setup)").
  await page.locator(`${SETUP_FORM_SELECTOR} button[type="submit"]`).click();

  await page.waitForURL(/#\/planning(\?|$)/, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: 15_000 });

  // List defaults to Day.From=today; our 2026-05 range is in window.
  await expect(
    page.locator('tbody [data-testid="row"]').first(),
    'at least one planning day row should appear after wizard submit',
  ).toBeVisible({ timeout: 15_000 });
  const after = await countPlanningRows(page);
  expect(after, `expected ≥1 planning day created (baseline=${baseline})`).toBeGreaterThanOrEqual(1);
  await screenshot(loggedInPage, '15-planning-setup-wizard-01');
});
