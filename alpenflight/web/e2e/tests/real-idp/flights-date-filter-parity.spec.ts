import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';

import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';
import { formatDdMmYyyy, isoDateFromLocal } from '../../../src/app/shared/util/date/format-date';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

function rangeInputs(page: Page) {
  return page.getByTestId('flights-date-range').locator('input');
}

function pickerOverlay(page: Page) {
  return page.locator('.cdk-overlay-container .ant-picker-panel-container');
}

const REAL_LOGIN_TIMEOUT_MS = 180_000;

const DATE_FILTER_SPEC_ADMIN_USERNAME_TAG = 'fdr';

function daysFromToday(n: number): Date {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return d;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

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
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(REAL_LOGIN_TIMEOUT_MS);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL, DATE_FILTER_SPEC_ADMIN_USERNAME_TAG);
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('[happy] the date-range inputs render today..today on initial /flights load', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();
      const inputs = rangeInputs(page);
      await expect(inputs.first()).toBeVisible();

      const today = formatDdMmYyyy(new Date());
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

      const refetch = rangeRefetch(page);
      await rangeInputs(page).first().click();
      const overlay = pickerOverlay(page);
      await expect(overlay).toBeVisible();
      const selectableDayCells = overlay.locator(
        '.ant-picker-cell-in-view:not(.ant-picker-cell-disabled) .ant-picker-cell-inner',
      );
      const selectableDayCellCount = await selectableDayCells.count();
      await selectableDayCells.first().click();
      await selectableDayCells.nth(selectableDayCellCount - 1).click();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-date-mouse.png`,
        fullPage: true,
      });

      const response = await refetch;
      const params = new URL(response.url()).searchParams;
      expect(params.get('from')).not.toBe(params.get('to'));
      const today = formatDdMmYyyy(new Date());
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

      const refetch = rangeRefetch(page);
      const inputs = rangeInputs(page);
      const fromDate = daysFromToday(0);
      const toDate = daysFromToday(7);
      const from = formatDdMmYyyy(fromDate);
      const to = formatDdMmYyyy(toDate);

      await inputs.first().click();
      await inputs.first().fill(from);
      await page.keyboard.press('Enter');
      await inputs.nth(1).fill(to);
      await page.keyboard.press('Enter');

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-date-keyboard.png`,
        fullPage: true,
      });

      const response = await refetch;
      const params = new URL(response.url()).searchParams;
      expect(params.get('from')).toBe(isoDateFromLocal(fromDate));
      expect(params.get('to')).toBe(isoDateFromLocal(toDate));

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
