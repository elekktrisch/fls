import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';

import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';
import { formatDdMmYyyy, isoDateFromLocal } from '../../../src/app/shared/util/date/format-date';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * The /flights date-range filter hardening proof (live Keycloak + real Spring
 * backend + real Postgres). The store defaults the list to today..today (legacy
 * parity, `flight.store.ts`) and the list correctly FETCHES today; this spec
 * proves the visible date-range CONTROL agrees, across three states under a
 * zero-console-error guard — the functional proof the control works, not just
 * that the screenshot looks plausible:
 *   - initial: both inputs render today..today on load (not an empty placeholder);
 *   - mouse: opening the picker and selecting a from + to date refetches the list
 *     for the new (multi-day) range and the inputs reflect it;
 *   - keyboard: typed dd.MM.yyyy dates COMMIT on Enter, refetch the list for the
 *     typed range, and stick in the inputs.
 *
 * It does NOT seed flights: the filter renders and misbehaves independently of
 * whether the list has rows, so an empty clean-seed logbook exercises (and
 * proves) the control.
 */

/** The /flights range picker renders two inputs inside one .ant-picker. */
function rangeInputs(page: Page) {
  return page.getByTestId('flights-date-range').locator('input');
}

/** The CDK overlay panel the range picker opens into. */
function pickerOverlay(page: Page) {
  return page.locator('.cdk-overlay-container .ant-picker-panel-container');
}

/**
 * A date rendered in the picker's display format — the SAME `formatDdMmYyyy`
 * (`dd.MM.yyyy`) the app uses for `DEFAULT_DATE_FORMAT`, so the expected value is
 * the production renderer's output, not a parallel hand-rolled string the test
 * could drift from.
 */
function display(d: Date): string {
  return formatDdMmYyyy(d);
}

/** `n` days after today (local calendar). */
function daysFromToday(n: number): Date {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return d;
}

/**
 * Per-test recorded context — the `real-idp` project's `video: 'on'` only
 * governs Playwright's auto-created `page`; this spec drives its own
 * `browser.newContext()`, so pass `recordVideo` explicitly to land the green
 * run's `.webm` for the proof gallery (mirrors the J-2 flight specs).
 */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  // Guard every page this context opens, not just the fixture-injected one.
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/**
 * A /flights list refetch for a range OTHER than today..today — the signal that
 * an edit reached the store→server wiring. Matches a GET /api/v1/flights with
 * from != to (a multi-day window), so the today-default initial fetch never
 * satisfies it.
 */
function rangeRefetch(page: Page) {
  return page.waitForResponse(
    (r) => {
      const u = new URL(r.url());
      return (
        r.request().method() === 'GET' &&
        u.pathname === '/api/v1/flights' &&
        u.searchParams.get('from') !== null &&
        u.searchParams.get('to') !== null &&
        u.searchParams.get('from') !== u.searchParams.get('to') &&
        r.status() === 200
      );
    },
    { timeout: 20_000 },
  );
}

test.describe('Flights date-range filter — control hardening (real-idp)', () => {
  // One realm + one backend; provision once, run the states in order. Serial
  // keeps the isolated sessions from racing (mirrors the J-2 flight specs).
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    // A real KC club login exceeds the 45s per-test budget on a slow CI box.
    testInfo.setTimeout(180_000);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Spec-scoped admin-username tag ('fdr') keeps this fixture's club admin
    // disjoint from the other flight specs in one `playwright test` invocation
    // (ux_user_username_lower_alive).
    fixture = await provisionTwoClubs(browser, baseURL, 'fdr');
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('[happy] the date-range inputs render today..today on initial /flights load', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    // The shared guard's auto fixture only watches the injected `page`; this
    // spec drives its OWN context page, so opt it into the same per-test
    // collector — the teardown then fails the test on any uncaught browser
    // error (the functional proof the control works, RED while the bug is live).
    watchConsoleErrors(page, testInfo);
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();
      const inputs = rangeInputs(page);
      await expect(inputs.first()).toBeVisible();

      // The store filters the list to today..today (legacy parity); the visible
      // control must AGREE — both inputs render today, not an empty placeholder.
      const today = display(new Date());
      await expect(inputs.first()).toHaveValue(today);
      await expect(inputs.nth(1)).toHaveValue(today);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-date-initial.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2c',
        caption:
          'J-2c · today-default visible · on initial /flights load the date-range filter inputs render ' +
          "today..today (the store's legacy-parity default surfaces in the visible control, not an empty " +
          'placeholder) — with zero uncaught browser-console errors',
        acTag: 'happy',
      });
    }
  });

  test('[happy] editing the range by MOUSE refetches the list with zero console errors', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      // Open the picker and click a from + a to day cell (two in-view, non-
      // disabled cells) → a multi-day range. Cell clicks are the reliable commit
      // path under zoneless ng-zorro (a typed-Enter range never emits the
      // ngModelChange — proven in flights-list.spec.ts).
      const refetch = rangeRefetch(page);
      await rangeInputs(page).first().click();
      const overlay = pickerOverlay(page);
      await expect(overlay).toBeVisible();
      const cells = overlay.locator(
        '.ant-picker-cell-in-view:not(.ant-picker-cell-disabled) .ant-picker-cell-inner',
      );
      const count = await cells.count();
      await cells.first().click();
      await cells.nth(count - 1).click();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-date-mouse.png`,
        fullPage: true,
      });

      // The picked range reaches the store→server wiring with a multi-day window
      // (from != to) AND the inputs now render that picked range, not the
      // today..today default — the rendered control actually changed.
      const response = await refetch;
      const params = new URL(response.url()).searchParams;
      expect(params.get('from')).not.toBe(params.get('to'));
      const today = display(new Date());
      const renderedFrom = await rangeInputs(page).first().inputValue();
      const renderedTo = await rangeInputs(page).nth(1).inputValue();
      expect(`${renderedFrom}..${renderedTo}`).not.toBe(`${today}..${today}`);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2c',
        caption:
          'J-2c · mouse edit · opening the date-range picker and selecting a from + to date updates the ' +
          'filter and refetches the list — with zero uncaught browser-console errors (the functional ' +
          'proof the control works)',
        acTag: 'happy',
      });
    }
  });

  test('[happy] entering dates by KEYBOARD refetches the list with zero console errors', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      // Type a from + to date into the two fields (display format dd.MM.yyyy),
      // committing each with Enter — the path that must commit a typed date rather
      // than silently revert it. The from/to are the picker's two inputs.
      const refetch = rangeRefetch(page);
      const inputs = rangeInputs(page);
      const fromDate = daysFromToday(0);
      const toDate = daysFromToday(7);
      const from = display(fromDate);
      const to = display(toDate);

      await inputs.first().click();
      await inputs.first().fill(from);
      await page.keyboard.press('Enter');
      await inputs.nth(1).fill(to);
      await page.keyboard.press('Enter');

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-date-keyboard.png`,
        fullPage: true,
      });

      // The typed range reaches the store→server wiring (a from != to refetch),
      // and the server saw the ISO bounds of the dates we typed — proof the typed
      // values committed all the way through, not just that *some* refetch fired.
      const response = await refetch;
      const params = new URL(response.url()).searchParams;
      expect(params.get('from')).toBe(isoDateFromLocal(fromDate));
      expect(params.get('to')).toBe(isoDateFromLocal(toDate));

      // The committed dd.MM.yyyy values stick in the inputs — a typed date no
      // longer reverts after Enter.
      await expect(inputs.first()).toHaveValue(from);
      await expect(inputs.nth(1)).toHaveValue(to);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2c',
        caption:
          'J-2c · keyboard entry · typing a from + to date into the date-range fields updates the filter ' +
          'and refetches the list — with zero uncaught browser-console errors (the functional proof the ' +
          'control works by keyboard, not only mouse)',
        acTag: 'happy',
      });
    }
  });
});
