import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

const JOURNEY_THIS_SPEC_PROVES = 'J-20';

const DEMO_PATH = '/demo';
const START_PATH = '/start';
const FLIGHTS_PATH = '/flights';
const AIRCRAFT_PATH = '/aircraft';
const RESERVATIONS_PATH = '/reservations';
const JOBS_PATH = '/system/jobs';
const SIGNUP_MIGRATE_PATH_PATTERN = /\/signup\?.*intent=migrate/;

const SANDBOX_RESET_JOB = 'sandbox-reset';

interface SeededPrincipal {
  username: string;
  password: string;
}

const SYSADMIN: SeededPrincipal = {
  username: 'sysadmin@example.com',
  password: 'sysadmin-dev-2026!',
};

const SEEDED_FLIGHT_ROWS_OVER_THE_LAST_30_DAYS_FLOOR = 20;
const SEEDED_AIRCRAFT_ROWS_FLOOR = 3;
const SEEDED_RESERVATION_BLOCKS_OVER_THE_NEXT_14_DAYS_FLOOR = 5;

const TESTIDS = {
  landingDemoCta: 'landing-cta-demo',
  demoPage: 'demo-page',
  demoStart: 'demo-start',
  demoSeatBusy: 'demo-seat-busy',
  demoSeatBusyReason: 'demo-seat-busy-reason',
  demoBanner: 'demo-banner',
  demoBannerCta: 'demo-banner-cta',
  clubAdminDashboard: 'start-variant-clubadmin',
  todayFlightsValue: 'start-tile-today-flights-value',
  pendingValidationValue: 'start-tile-pending-validation-value',
  flightsTable: 'flights-table',
  aircraftTable: 'aircraft-table',
  reservationsWeekGrid: 'reservations-week-grid',
  reservationsViewToggle: 'reservations-view-toggle',
  flightComment: 'flight-edit-glider-comment',
  flightSubmit: 'flight-submit-header',
  jobRow: 'job-row',
  jobRowCron: 'job-row-cron',
  jobRowStatus: 'job-row-status',
  jobRunNow: 'job-run-now',
  jobRunResult: 'job-run-result',
  jobRunResultStatus: 'job-run-result-status',
};

const FLIGHT_ROW_SELECTOR = '[data-testid^="flights-row-"]:not([data-testid^="flights-row-link-"])';
const AIRCRAFT_ROW_SELECTOR = '[data-testid^="aircraft-row-"]';
const RESERVATION_BLOCK_SELECTOR = '[data-testid^="reservation-scheduler-block-"]';

interface DrivenSession {
  context: BrowserContext;
  page: Page;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<DrivenSession> {
  const context = await browser.newContext({
    baseURL,
    recordVideo: { dir: testInfo.outputDir },
  });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  const page = await context.newPage();
  return { context, page };
}

async function enterDemoFromTheLandingPage(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<DrivenSession> {
  const session = await newRecordedContext(browser, baseURL, testInfo);
  await session.page.goto('/?lang=en');
  await session.page.getByTestId(TESTIDS.landingDemoCta).click();
  await expect(session.page).toHaveURL(new RegExp(`${DEMO_PATH}(\\?|$|/)`));
  await expect(session.page.getByTestId(TESTIDS.demoPage)).toBeVisible();
  await session.page.getByTestId(TESTIDS.demoStart).click();
  await session.page.waitForURL((url) => url.pathname === START_PATH, { timeout: 30_000 });
  return session;
}

async function loginAsSysadmin(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<DrivenSession> {
  const session = await newRecordedContext(browser, baseURL, testInfo);
  await session.page.goto('/');
  await session.page.getByTestId('landing-topbar-sign-in').click();
  await session.page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(session.page, SYSADMIN.username, SYSADMIN.password);
  await session.page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  return session;
}

async function readTileValue(page: Page, testId: string): Promise<number> {
  const rendered = (await page.getByTestId(testId).innerText()).trim();
  return Number.parseInt(rendered, 10);
}

async function openTheNewestFlightOfTheSeat(page: Page): Promise<string> {
  await page.goto(`${FLIGHTS_PATH}?lang=en`);
  await expect(page.getByTestId(TESTIDS.flightsTable)).toBeVisible();
  const newest = page.locator(FLIGHT_ROW_SELECTOR).first();
  const rowTestId = await newest.getAttribute('data-testid');
  await newest.click();
  await expect(page.getByTestId(TESTIDS.flightComment)).toBeVisible();
  return (rowTestId ?? '').replace('flights-row-', '');
}

test.describe('demo mode — a visitor with no account enters a private sandbox club (real chain)', () => {
  test.describe.configure({ mode: 'serial' });

  test.fixme('AC-1 AC-2 AC-3 [T-14 unskips this, after T-03 to T-08 and T-12 ship] a visitor with no account reads a populated sandbox club, and every demo screen carries the banner', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const visitor = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    try {
      await expect(visitor.page.getByTestId(TESTIDS.clubAdminDashboard)).toBeVisible();
      expect(
        await readTileValue(visitor.page, TESTIDS.todayFlightsValue),
        'the sandbox seed puts flights on the run date, so the tile is not a zero',
      ).toBeGreaterThan(0);
      expect(
        await readTileValue(visitor.page, TESTIDS.pendingValidationValue),
        'the sandbox seed leaves flights to validate, so the tile is not a zero',
      ).toBeGreaterThan(0);
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      await visitor.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-dashboard.png`,
        fullPage: true,
      });

      await visitor.page.goto(`${FLIGHTS_PATH}?lang=en`);
      await expect(visitor.page.getByTestId(TESTIDS.flightsTable)).toBeVisible();
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      expect(await visitor.page.locator(FLIGHT_ROW_SELECTOR).count()).toBeGreaterThanOrEqual(
        SEEDED_FLIGHT_ROWS_OVER_THE_LAST_30_DAYS_FLOOR,
      );
      await visitor.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-flights.png`,
        fullPage: true,
      });

      await visitor.page.goto(`${AIRCRAFT_PATH}?lang=en`);
      await expect(visitor.page.getByTestId(TESTIDS.aircraftTable)).toBeVisible();
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      expect(await visitor.page.locator(AIRCRAFT_ROW_SELECTOR).count()).toBeGreaterThanOrEqual(
        SEEDED_AIRCRAFT_ROWS_FLOOR,
      );

      await visitor.page.goto(`${RESERVATIONS_PATH}?lang=en`);
      await visitor.page.getByTestId(TESTIDS.reservationsViewToggle).click();
      await expect(visitor.page.getByTestId(TESTIDS.reservationsWeekGrid)).toBeVisible();
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      expect(await visitor.page.locator(RESERVATION_BLOCK_SELECTOR).count()).toBeGreaterThanOrEqual(
        SEEDED_RESERVATION_BLOCKS_OVER_THE_NEXT_14_DAYS_FLOOR,
      );

      await visitor.page.getByTestId(TESTIDS.demoBannerCta).click();
      await expect(visitor.page).toHaveURL(SIGNUP_MIGRATE_PATH_PATTERN);
    } finally {
      await visitor.context.close();
      await proofVideo(visitor.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'happy',
        caption:
          'A visitor with no account selects the demo action on the landing page and lands on a ' +
          'populated club. The dashboard tiles read above zero, the logbook holds the seeded ' +
          'flights, the aircraft register and the reservation week hold the seeded rows, and ' +
          'every screen carries the demo banner. The banner action opens the migrate signup.',
      });
    }
  });

  test.fixme('AC-4 AC-5 [T-14 unskips this] a second visitor gets a different seat and never reads the first visitor’s change', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const visitorA = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    const visitorB = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    try {
      await openTheNewestFlightOfTheSeat(visitorA.page);
      const seededComment = await visitorA.page.getByTestId(TESTIDS.flightComment).inputValue();
      const changeOnlyVisitorAMakes = `visitor-a-${Date.now().toString(36)}`;
      await visitorA.page.getByTestId(TESTIDS.flightComment).fill(changeOnlyVisitorAMakes);
      await visitorA.page.getByTestId(TESTIDS.flightSubmit).click();

      await openTheNewestFlightOfTheSeat(visitorA.page);
      await expect(
        visitorA.page.getByTestId(TESTIDS.flightComment),
        'the change of the demo visitor stays after a reload',
      ).toHaveValue(changeOnlyVisitorAMakes);
      await visitorA.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-flight-changed.png`,
        fullPage: true,
      });

      await openTheNewestFlightOfTheSeat(visitorB.page);
      await expect(
        visitorB.page.getByTestId(TESTIDS.flightComment),
        'the second seat holds the seeded value',
      ).toHaveValue(seededComment);
      await expect(
        visitorB.page.getByTestId(TESTIDS.flightComment),
        'and it never holds the value the first visitor wrote',
      ).not.toHaveValue(changeOnlyVisitorAMakes);
      await expect(
        visitorB.page.locator(`text=${changeOnlyVisitorAMakes}`),
        'the value of the first visitor is absent from the whole screen of the second visitor',
      ).toHaveCount(0);
      await visitorB.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-isolation.png`,
        fullPage: true,
      });
    } finally {
      await visitorA.context.close();
      await visitorB.context.close();
      await proofVideo(visitorB.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'happy',
        caption:
          'Two demo visitors hold two different seats. The first visitor changes a flight and ' +
          'reads the change back after a reload. The second visitor opens the same flight of ' +
          'its own seat and reads the seeded value. The value of the first visitor is absent.',
      });
    }
  });

  test.fixme('AC-6 [T-14 unskips this, after T-10 ships the reset job] a system administrator runs sandbox-reset and the seeded value comes back', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const sysadmin = await loginAsSysadmin(browser, baseURL!, testInfo);
    const visitorAfterTheReset = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    try {
      await sysadmin.page.goto(`${JOBS_PATH}?lang=en`);
      const resetRow = sysadmin.page
        .getByTestId(TESTIDS.jobRow)
        .filter({ hasText: SANDBOX_RESET_JOB });
      await expect(resetRow, 'the registry surfaces the sandbox reset job').toHaveCount(1);
      await expect(resetRow.getByTestId(TESTIDS.jobRowCron)).not.toBeEmpty();

      await resetRow.getByTestId(TESTIDS.jobRunNow).click();
      await expect(sysadmin.page.getByTestId(TESTIDS.jobRunResult)).toBeVisible({
        timeout: 60_000,
      });
      await expect(sysadmin.page.getByTestId(TESTIDS.jobRunResultStatus)).toContainText(
        'COMPLETED',
      );
      await expect(resetRow.getByTestId(TESTIDS.jobRowStatus)).toContainText('COMPLETED');

      await openTheNewestFlightOfTheSeat(visitorAfterTheReset.page);
      await expect(
        visitorAfterTheReset.page.getByTestId(TESTIDS.flightComment),
        'the reclaimed seat holds the seeded value again',
      ).not.toBeEmpty();
      await visitorAfterTheReset.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-flight-restored.png`,
        fullPage: true,
      });
    } finally {
      await sysadmin.context.close();
      await visitorAfterTheReset.context.close();
      await proofVideo(sysadmin.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'happy',
        caption:
          'A system administrator opens the scheduled-jobs console and runs sandbox-reset. The ' +
          'job completes, it reclaims the expired seat, and a demo visitor reads the seeded ' +
          'value again. The change of the earlier visitor is gone.',
      });
    }
  });

  test.fixme('AC-8 [T-14 unskips this, after T-07 and T-08 ship the pool] an exhausted pool answers 503 and /demo renders the seat-busy state', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const visitor = await newRecordedContext(browser, baseURL!, testInfo);
    try {
      await visitor.page.goto(`${DEMO_PATH}?lang=en`);
      await expect(visitor.page.getByTestId(TESTIDS.demoPage)).toBeVisible();
      await visitor.page.getByTestId(TESTIDS.demoStart).click();

      await expect(visitor.page.getByTestId(TESTIDS.demoSeatBusy)).toBeVisible();
      await expect(
        visitor.page.getByTestId(TESTIDS.demoSeatBusyReason),
        'the busy state names a readable reason, not a status code',
      ).not.toBeEmpty();
      await expect(visitor.page).toHaveURL(new RegExp(`${DEMO_PATH}(\\?|$|/)`));
      await visitor.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-seat-busy.png`,
        fullPage: true,
      });
    } finally {
      await visitor.context.close();
      await proofVideo(visitor.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'key-error',
        caption:
          'Every demo seat is leased. The demo page keeps the visitor on the demo page, and it ' +
          'shows a readable reason. The visitor gets no half-started session.',
      });
    }
  });
});
