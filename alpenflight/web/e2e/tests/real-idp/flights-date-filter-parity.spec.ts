import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type ConsoleMessage,
  type Page,
  type TestInfo,
} from '@playwright/test';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * The /flights date-range filter hardening proof (live Keycloak + real Spring
 * backend + real Postgres). The store already defaults the list to today..today
 * (legacy parity, `flight.store.ts`) and the list correctly FETCHES today, but
 * the visible date-range CONTROL is broken: the inputs don't render the today
 * default on load, interacting throws a burst of browser-console errors, and the
 * from-field shows a stray underline. This spec drives the three control states
 * (initial today-default · mouse edit · keyboard entry) under a zero-console-
 * error guard — the functional proof the controls work, not just that the
 * screenshot looks plausible. It does NOT seed flights: the filter renders and
 * misbehaves independently of whether the list has rows, so an empty clean-seed
 * logbook is enough to exercise (and prove) the control.
 *
 * Red-first by design: with the binding bug live, the today-default never
 * reaches the inputs (initial state fails) and the interaction errors trip the
 * console guard (mouse + keyboard fail). T-03/T-04 fix the binding + styling;
 * T-05 thickens these asserts and populates the gallery.
 */

/** The /flights range picker renders two inputs inside one .ant-picker. */
function rangeInputs(page: Page) {
  return page.getByTestId('flights-date-range').locator('input');
}

/** The CDK overlay panel the range picker opens into. */
function pickerOverlay(page: Page) {
  return page.locator('.cdk-overlay-container .ant-picker-panel-container');
}

/** Today's date in the picker's display format (DEFAULT_DATE_FORMAT, dd.MM.yyyy). */
function todayDisplay(): string {
  const d = new Date();
  return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`;
}

/** Today's ISO date (local) — the store's today..today list-default bound. */
function isoToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
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
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/**
 * The functional proof the controls work: subscribe to the page's `console`
 * (level 'error') + `pageerror` streams and collect every uncaught browser
 * error across the filter-interaction flow. The test asserts the collection is
 * empty at the end — the broken control emits a burst of errors on interaction,
 * so this is the assertion that goes RED while the bug is live and GREEN once
 * the binding is fixed. Returns the live array (read it after the interaction).
 */
function captureConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') {
      errors.push(`console.error: ${msg.text()}`);
    }
  });
  page.on('pageerror', (err: Error) => {
    errors.push(`pageerror: ${err.message}`);
  });
  return errors;
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
    const errors = captureConsoleErrors(page);
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();
      const inputs = rangeInputs(page);
      await expect(inputs.first()).toBeVisible();

      // The store filters the list to today..today (legacy parity); the visible
      // control must AGREE — both inputs render today, not an empty placeholder.
      // Live bug: the [value]-signal binding bypasses writeValue() so the default
      // never reaches the inputs and they load blank (this assert is red today).
      await expect(inputs.first()).toHaveValue(todayDisplay());
      await expect(inputs.nth(1)).toHaveValue(todayDisplay());

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-date-initial.png`,
        fullPage: true,
      });

      // No uncaught browser error fired while loading + reading the control.
      expect(errors, `uncaught browser errors on initial load:\n${errors.join('\n')}`).toEqual([]);
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
    const errors = captureConsoleErrors(page);
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

      // The picked range reaches the store→server wiring (a from != to refetch).
      await refetch;

      // No uncaught browser error fired across the whole mouse interaction — the
      // live bug throws a burst here, so this assert is red today.
      expect(errors, `uncaught browser errors on mouse edit:\n${errors.join('\n')}`).toEqual([]);
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
    const errors = captureConsoleErrors(page);
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      // Type a from + to date into the two fields (display format dd.MM.yyyy),
      // committing each with Enter. Keyboard entry is the second control path the
      // operator reported broken; the from/to are the picker's two inputs.
      const refetch = rangeRefetch(page);
      const inputs = rangeInputs(page);
      const from = todayDisplay();
      const to = (() => {
        const d = new Date();
        d.setDate(d.getDate() + 7);
        return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`;
      })();

      await inputs.first().click();
      await inputs.first().fill(from);
      await page.keyboard.press('Enter');
      await inputs.nth(1).fill(to);
      await page.keyboard.press('Enter');

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-date-keyboard.png`,
        fullPage: true,
      });

      // The typed range reaches the store→server wiring (a from != to refetch).
      await refetch;

      // No uncaught browser error fired across the whole keyboard interaction —
      // the live bug throws here too, so this assert is red today.
      expect(errors, `uncaught browser errors on keyboard entry:\n${errors.join('\n')}`).toEqual(
        [],
      );

      // The store default bound today; the typed `from` keeps it (parity anchor).
      expect(from).toBe(`${isoToday().split('-').reverse().join('.')}`);
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
