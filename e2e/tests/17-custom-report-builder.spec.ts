// Spec #17: /flightreports/custom builder. Inject filter on $scope, OK,
// land on /apply, assert a summary or flights row rendered.
//
// TODO testid: `report-config-form`, `report-apply`, `report-summary-table`,
// `report-results`.

import { test, expect, gotoRoute, screenshot } from '../fixtures';
import { testId } from '../test-id';
import { ensureGliderFlight, getBearerToken } from '../test-data';
import type { Page } from '@playwright/test';

const SECONDARY_TIMEOUT = 15_000;

const CATEGORY = 'location';
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
    };
    return !!s
      && !!s.custom
      && !!s.md
      && Array.isArray(s.md.locations) && s.md.locations.length > 0;
  }, undefined, { timeout: SECONDARY_TIMEOUT });
}

test('flightreports:custom builder applies filter and renders results', async ({ loggedInPage }, testInfo) => {
  // Ensure ≥1 LSZK glider flight in the window so the report renders something.
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  await ensureGliderFlight(loggedInPage.request, token, { comment: id.name });

  // AngularJS $location.path() URL-encodes `{}` to `%7B%7D`.
  await gotoRoute(loggedInPage, `/flightreports/custom/${CATEGORY}/%7B%7D/edit`);
  await waitForConfigScopeReady(loggedInPage);
  await screenshot(loggedInPage, 'flightreports-custom-edit');

  const injection = await loggedInPage.evaluate(({ from, to }) => {
    const w = window as unknown as {
      angular: {
        element: (n: Element) => {
          scope: () => {
            custom: Record<string, unknown>;
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
    const lszk = s.md.locations.find(l => l.IcaoCode === 'LSZK') ?? s.md.locations[0];
    s.custom.LocationId = lszk.LocationId;
    ngEl.$apply();
    return { locationId: s.custom.LocationId as string };
  }, { from: FROM_DATE, to: TO_DATE });

  expect(injection.locationId, 'a LocationId should have been picked from md.locations').toBeTruthy();

  const okButton = loggedInPage.locator('button[ng-click="applyCriteria()"]').first();
  await expect(okButton, 'OK / apply button must be visible on edit page').toBeVisible();
  await okButton.click();

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

  // Filter panel rendered with From="01.01.2025".
  const fromCell = loggedInPage.locator('.filter-criteria-panel .filter-value').first();
  await expect(fromCell, 'filter-criteria panel must render the From date').toBeVisible();
  await expect(fromCell).toHaveText(/01\.01\.2025/);

  // Conservative OR: summary OR flights table — pipeline rendered, content is out of scope.
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
