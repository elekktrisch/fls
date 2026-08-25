import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';
import {
  afDatePickerInputs,
  typeDateOnlyAfterTheOverlayPanelExists,
} from '../_helpers/af-date-picker';
import { formatDdMmYyyy, isoDateFromLocal } from '../../../src/app/shared/util/date/format-date';

import { fillKcLogin } from './_helpers/kc-form';
import {
  assertLocalhostIssuer,
  findUserByUsername,
  listRealmRoleNamesOf,
} from './_helpers/keycloak-admin';
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

const SEAT_COUNT_V62_PROVISIONS = 10;
const SEAT_CLUB_ID_PREFIX_V62_BUILDS = '019e30c3-2c00-7001-8000-0000000de';
const SEAT_PASSWORD_THE_COMMITTED_REALM_EXPORT_CARRIES = 'alpenflight-demo-seat-dev-2026!';
const SEAT_REALM_ROLE = 'CLUB_ADMINISTRATOR';

const DEMO_SEAT_ACCESS_TOKEN_STORAGE_KEY = 'alpenflight.demo-seat-access-token';

function seatUsername(seat: number): string {
  return `demo${seat}`;
}

function seatEmail(seat: number): string {
  return `${seatUsername(seat)}@example.com`;
}

function seatClubId(seat: number): string {
  return `${SEAT_CLUB_ID_PREFIX_V62_BUILDS}${String(seat).padStart(3, '0')}`;
}

interface SeatAccessTokenClaims {
  clubId?: string;
  preferred_username?: string;
  given_name?: string;
  email?: string;
  realm_access?: { roles?: string[] };
}

function decodeAccessTokenPayload(bearer: string): SeatAccessTokenClaims {
  const payload = bearer.replace(/^Bearer /i, '').split('.')[1];
  if (!payload) {
    throw new Error(`authorization header is not a JWT: '${bearer.slice(0, 24)}…'`);
  }
  return JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as SeatAccessTokenClaims;
}

interface MeProjection {
  id: string | null;
  clubId: string | null;
  username: string | null;
  roles: string[];
}

const SEEDED_FLIGHT_ROWS_PER_SEAT = 24;
const SEEDED_FLIGHT_ROWS_OVER_THE_LAST_30_DAYS_FLOOR = 20;
const SEEDED_AIRCRAFT_ROWS_FLOOR = 3;
const SEEDED_RESERVATIONS_OVER_THE_NEXT_14_DAYS_FLOOR = 5;

const FLIGHT_HISTORY_WINDOW_DAYS = 30;
const RESERVATION_WINDOW_DAYS = 14;
const WEEK_PAGES_THAT_COVER_A_FOURTEEN_DAY_WINDOW = 3;

const SEEDED_THREE_HOUR_FLIGHT_RENDERED_DURATION = '03:00';

const RESET_RUNS_BEFORE_THE_EXPIRED_LEASE_MUST_HAVE_BEEN_RECLAIMED = 3;

const ONE_COLD_LEASE_PLUS_FOUR_POPULATED_SCREENS_TIMEOUT_MS = 180_000;
const TWO_COLD_LEASES_PLUS_A_WRITE_ROUND_TRIP_TIMEOUT_MS = 240_000;
const A_LEASE_A_WRITE_A_REAL_LOGIN_AND_THE_RESET_RUNS_TIMEOUT_MS = 420_000;
const THE_RESET_PURGES_AND_RE_SEEDS_EVERY_STALE_SEAT_TIMEOUT_MS = 180_000;
const THE_APPLICATION_BOOTS_FROM_A_COLD_DOCUMENT_TIMEOUT_MS = 45_000;

const TESTIDS = {
  landingDemoCta: 'landing-cta-demo',
  demoPage: 'demo-page',
  demoStart: 'demo-start',
  demoBanner: 'demo-banner',
  demoBannerCta: 'demo-banner-cta',
  clubAdminDashboard: 'start-variant-clubadmin',
  todayFlightsValue: 'start-tile-today-flights-value',
  todayFlightsError: 'start-tile-today-flights-error',
  pendingValidationValue: 'start-tile-pending-validation-value',
  pendingValidationError: 'start-tile-pending-validation-error',
  flightsTable: 'flights-table',
  flightsDateRange: 'flights-date-range',
  flightForm: 'flight-form',
  flightStepNext: 'flight-step-next',
  flightStepGlider: 'flight-step-glider',
  flightComment: 'flight-edit-glider-comment',
  flightSubmit: 'flight-submit-header',
  aircraftTable: 'aircraft-table',
  reservationsWeekGrid: 'reservations-week-grid',
  reservationsViewWeek: 'reservations-view-week',
  reservationsNextWeek: 'reservations-next-week',
  reservationsPeriodLabel: 'reservations-period-label',
  jobRow: 'job-row',
  jobRowCron: 'job-row-cron',
  jobRowStatus: 'job-row-status',
  jobRunNow: 'job-run-now',
  jobRunResult: 'job-run-result',
  jobRunResultStatus: 'job-run-result-status',
};

const FLIGHT_ROW_SELECTOR = '[data-testid^="flights-row-"]:not([data-testid^="flights-row-link-"])';
const FLIGHT_DURATION_TESTID_PREFIX = 'flights-duration-';
const AIRCRAFT_ROW_SELECTOR = '[data-testid^="aircraft-row-"]';
const RESERVATION_WEEK_CELL_TESTID_PREFIX = 'reservations-week-cell-';
const DAY_KEY_LENGTH = 'YYYY-MM-DD'.length;

const RECLAIMED_EXPIRED_SEATS_IN_THE_RESET_SUMMARY = /(\d+) expired seats reclaimed/;

interface DrivenSession {
  context: BrowserContext;
  page: Page;
}

function daysFromToday(days: number): Date {
  const shifted = new Date();
  shifted.setDate(shifted.getDate() + days);
  return shifted;
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
  await session.page.waitForURL((url) => url.pathname === START_PATH, { timeout: 60_000 });
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

async function seatClaimsTheTabHolds(page: Page): Promise<SeatAccessTokenClaims> {
  const heldSeatToken = await page.evaluate(
    (storageKey) => sessionStorage.getItem(storageKey),
    DEMO_SEAT_ACCESS_TOKEN_STORAGE_KEY,
  );
  if (heldSeatToken === null) {
    throw new Error('the tab holds no demo seat token, so the lease never completed');
  }
  return decodeAccessTokenPayload(heldSeatToken);
}

async function waitForTheFlightsScreenOfAColdDocumentLoad(page: Page): Promise<void> {
  await expect(
    page.getByTestId(TESTIDS.flightsTable),
    'a cold document load boots the whole application again, and the development server ' +
      'serves every lazy chunk of the flights screen on that boot',
  ).toBeVisible({ timeout: THE_APPLICATION_BOOTS_FROM_A_COLD_DOCUMENT_TIMEOUT_MS });
}

async function widenTheRangeToTheSeededFlightHistory(page: Page): Promise<void> {
  const theListReloadsForTheWiderRange = page.waitForResponse(
    (response) => {
      const requested = new URL(response.url());
      return (
        response.request().method() === 'GET' &&
        requested.pathname === '/api/v1/flights' &&
        requested.searchParams.get('from') !== requested.searchParams.get('to') &&
        response.status() === 200
      );
    },
    { timeout: 30_000 },
  );
  const range = afDatePickerInputs(page, TESTIDS.flightsDateRange);
  await typeDateOnlyAfterTheOverlayPanelExists(
    page,
    range.first(),
    formatDdMmYyyy(daysFromToday(-FLIGHT_HISTORY_WINDOW_DAYS)),
  );
  await typeDateOnlyAfterTheOverlayPanelExists(
    page,
    range.nth(1),
    formatDdMmYyyy(daysFromToday(0)),
  );
  await theListReloadsForTheWiderRange;
  await expect(page.locator(FLIGHT_ROW_SELECTOR).first()).toBeVisible();
}

async function openTheSeededFlightHistory(page: Page): Promise<void> {
  await page.goto(`${FLIGHTS_PATH}?lang=en`);
  await waitForTheFlightsScreenOfAColdDocumentLoad(page);
  await widenTheRangeToTheSeededFlightHistory(page);
}

async function openTheSeededThreeHourFlight(page: Page): Promise<string> {
  const durationOfTheNewestThreeHourFlight = page
    .locator(`[data-testid^="${FLIGHT_DURATION_TESTID_PREFIX}"]`)
    .filter({ hasText: SEEDED_THREE_HOUR_FLIGHT_RENDERED_DURATION })
    .first();
  await expect(
    durationOfTheNewestThreeHourFlight,
    'the sandbox seeder writes one three-hour cross-country glider flight on every flying day, ' +
      'and it is the one row both seats render identically',
  ).toBeVisible();
  const durationTestId = await durationOfTheNewestThreeHourFlight.getAttribute('data-testid');
  const flightId = (durationTestId ?? '').replace(FLIGHT_DURATION_TESTID_PREFIX, '');
  await page.getByTestId(`flights-row-${flightId}`).click();
  await expect(page.getByTestId(TESTIDS.flightForm)).toBeVisible();
  await page.getByTestId(TESTIDS.flightStepNext).click();
  await expect(page.getByTestId(TESTIDS.flightStepGlider)).toBeVisible();
  return flightId;
}

function flightCommentInput(page: Page) {
  return page.getByTestId(TESTIDS.flightComment).locator('input');
}

async function writeTheFlightCommentAndSave(
  page: Page,
  flightId: string,
  comment: string,
): Promise<void> {
  const theBackendAcceptsTheChange = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === `/api/v1/flights/${flightId}` &&
      response.status() === 200,
    { timeout: 30_000 },
  );
  await flightCommentInput(page).fill(comment);
  await page.getByTestId(TESTIDS.flightSubmit).click();
  await theBackendAcceptsTheChange;
  await expect(page).toHaveURL(/\/flights(\?|$)/);
}

async function reservationsTheWeekGridRendersOverTheNextFourteenDays(page: Page): Promise<number> {
  const dayKeysOfTheWindow = new Set(
    Array.from({ length: RESERVATION_WINDOW_DAYS }, (_, day) =>
      isoDateFromLocal(daysFromToday(day + 1)),
    ),
  );
  await expect(page.getByTestId(TESTIDS.reservationsViewWeek)).toBeVisible({
    timeout: THE_APPLICATION_BOOTS_FROM_A_COLD_DOCUMENT_TIMEOUT_MS,
  });
  await page.getByTestId(TESTIDS.reservationsViewWeek).click();
  await expect(page.getByTestId(TESTIDS.reservationsWeekGrid)).toBeVisible();
  const alreadyCountedCells = new Set<string>();
  let reservations = 0;
  for (let weekPage = 0; weekPage < WEEK_PAGES_THAT_COVER_A_FOURTEEN_DAY_WINDOW; weekPage++) {
    const renderedCells = await page
      .locator(`[data-testid^="${RESERVATION_WEEK_CELL_TESTID_PREFIX}"]`)
      .evaluateAll((cells) =>
        cells.map((cell) => ({
          key: cell.getAttribute('data-testid') ?? '',
          text: (cell.textContent ?? '').trim(),
        })),
      );
    for (const cell of renderedCells) {
      const dayKey = cell.key.slice(-DAY_KEY_LENGTH);
      if (!dayKeysOfTheWindow.has(dayKey) || alreadyCountedCells.has(cell.key)) {
        continue;
      }
      alreadyCountedCells.add(cell.key);
      const reservationsTheCellRenders = /^(\d+)\s*·/.exec(cell.text);
      if (reservationsTheCellRenders !== null) {
        reservations += Number.parseInt(reservationsTheCellRenders[1]!, 10);
      }
    }
    if (weekPage < WEEK_PAGES_THAT_COVER_A_FOURTEEN_DAY_WINDOW - 1) {
      const periodBeforeThePage = await page
        .getByTestId(TESTIDS.reservationsPeriodLabel)
        .innerText();
      await page.getByTestId(TESTIDS.reservationsNextWeek).click();
      await expect(page.getByTestId(TESTIDS.reservationsPeriodLabel)).not.toHaveText(
        periodBeforeThePage,
      );
    }
  }
  return reservations;
}

async function runTheSandboxResetOnce(page: Page): Promise<number> {
  const resetRow = page.getByTestId(TESTIDS.jobRow).filter({ hasText: SANDBOX_RESET_JOB });
  const theJobReports = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/admin/jobs/${SANDBOX_RESET_JOB}/run`,
    { timeout: THE_RESET_PURGES_AND_RE_SEEDS_EVERY_STALE_SEAT_TIMEOUT_MS },
  );
  await resetRow.getByTestId(TESTIDS.jobRunNow).click();
  const reported = await theJobReports;
  expect(reported.status()).toBe(200);
  await expect(page.getByTestId(TESTIDS.jobRunResult)).toBeVisible({
    timeout: THE_RESET_PURGES_AND_RE_SEEDS_EVERY_STALE_SEAT_TIMEOUT_MS,
  });
  await expect(page.getByTestId(TESTIDS.jobRunResultStatus)).toContainText('COMPLETED');
  await expect(resetRow.getByTestId(TESTIDS.jobRowStatus)).toContainText('COMPLETED');
  const summary = await page.getByTestId(TESTIDS.jobRunResult).innerText();
  const reclaimed = RECLAIMED_EXPIRED_SEATS_IN_THE_RESET_SUMMARY.exec(summary);
  expect(
    reclaimed,
    'the reset job reports how many expired seats it reclaimed, and the console renders that summary',
  ).not.toBeNull();
  return Number.parseInt(reclaimed![1]!, 10);
}

const NAV_PROFILE_LINK = 'a[role="menuitem"][href="/profile"]';

interface SeatSessionWithItsFirstBearer {
  session: DrivenSession;
  bearer: string;
}

async function loginAsSeatPrincipal(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
  seat: number,
): Promise<SeatSessionWithItsFirstBearer> {
  const session = await newRecordedContext(browser, baseURL, testInfo);
  const bearerOfTheFirstAuthorizedCall = session.page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
    { timeout: 90_000 },
  );
  await session.page.goto('/?lang=en');
  await session.page.getByTestId('landing-topbar-sign-in').click();
  await session.page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(
    session.page,
    seatEmail(seat),
    SEAT_PASSWORD_THE_COMMITTED_REALM_EXPORT_CARRIES,
  );
  await session.page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  const bearer = (await bearerOfTheFirstAuthorizedCall).headers()['authorization']!;
  return { session, bearer };
}

test.describe('demo seat principals — one Keycloak user per seat (real chain)', () => {
  test('[T-06] a seat principal mints a token that carries CLUB_ADMINISTRATOR and its own seat club, and the just-in-time materializer writes its user row', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { session: seat, bearer } = await loginAsSeatPrincipal(browser, baseURL!, testInfo, 1);
    try {
      const claims = decodeAccessTokenPayload(bearer);
      expect(
        claims.realm_access?.roles ?? [],
        'the seat token must carry CLUB_ADMINISTRATOR — every demo screen is role-gated',
      ).toContain(SEAT_REALM_ROLE);
      expect(
        claims.clubId,
        'the seat token must carry the clubId of seat 1, and never another seat club',
      ).toBe(seatClubId(1));
      expect(claims.preferred_username).toBe(seatUsername(1));
      expect(
        claims.given_name ?? '',
        'the materializer refuses a token without given_name, and the seat then reads nothing',
      ).not.toBe('');
      expect(
        claims.email ?? '',
        'the materializer refuses a token without email, and the seat then reads nothing',
      ).not.toBe('');

      const meResponse = await seat.page.request.get('/api/v1/me', {
        headers: { authorization: bearer },
      });
      expect(meResponse.status()).toBe(200);
      const me = (await meResponse.json()) as MeProjection;
      expect(
        me.id,
        'the just-in-time materializer wrote the seat its own t_user row on the first token use',
      ).not.toBeNull();
      expect(me.clubId, 'the user row binds the seat to its own club').toBe(`clb-${seatClubId(1)}`);
      expect(me.username).toBe(seatUsername(1));
      expect(me.roles).toContain(SEAT_REALM_ROLE);

      await seat.page.getByTestId('af-nav-user').click();
      await seat.page.locator(NAV_PROFILE_LINK).click();
      await seat.page.keyboard.press('Escape');
      await expect(seat.page.getByTestId('profile-account-form')).toBeVisible();
      await expect(seat.page.getByTestId('profile-account-username').locator('input')).toHaveValue(
        seatUsername(1),
      );
      await expect(seat.page.getByTestId('profile-account-clubId').locator('input')).toHaveValue(
        `clb-${seatClubId(1)}`,
      );
      await expect(
        seat.page.getByTestId('profile-account-notificationEmail').locator('input'),
      ).toHaveValue(seatEmail(1));
      await seat.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-seat-principal.png`,
        fullPage: true,
      });
    } finally {
      await seat.context.close();
      await proofVideo(seat.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'happy',
        caption:
          'The seat principal demo1 signs in against the real identity provider. Its token ' +
          'carries CLUB_ADMINISTRATOR and the club of seat 1. The account screen reads the row ' +
          'the just-in-time materializer wrote: the seat username, its own club and its email.',
      });
    }
  });

  test('[T-06] every seat principal keeps its identity fields and carries its own seat club', async () => {
    assertLocalhostIssuer();
    const clubIdsTheSeatsCarry: string[] = [];

    for (let seat = 1; seat <= SEAT_COUNT_V62_PROVISIONS; seat++) {
      const username = seatUsername(seat);
      const principal = await findUserByUsername(username);
      expect(
        principal,
        `seat ${seat} has a club and a t_demo_seat row, but no Keycloak principal — ` +
          'a visitor would lease a seat it cannot sign in to',
      ).toBeDefined();

      expect(
        principal!.email ?? '',
        `${username} lost its email. An attributes-only PUT /users/{id} to Keycloak is ` +
          'field-selective: it NULLs email, firstName and lastName. The principal then ' +
          'vanishes from every email lookup, and the materializer refuses its token.',
      ).not.toBe('');
      expect(
        principal!.firstName ?? '',
        `${username} lost its firstName — the same field-selective PUT trap`,
      ).not.toBe('');
      expect(
        principal!.lastName ?? '',
        `${username} lost its lastName — the same field-selective PUT trap`,
      ).not.toBe('');

      expect(principal!.enabled).toBe(true);
      expect(
        principal!.emailVerified,
        'the realm sets verifyEmail, so an unverified seat principal cannot complete a login',
      ).toBe(true);
      expect(
        principal!.requiredActions ?? [],
        'a required action hands the visitor an account form instead of the sandbox',
      ).toHaveLength(0);

      const clubIdAttribute = principal!.attributes?.['clubId']?.[0];
      expect(
        clubIdAttribute,
        `${username} must carry the club of seat ${seat}, and never another seat club`,
      ).toBe(seatClubId(seat));
      clubIdsTheSeatsCarry.push(clubIdAttribute!);

      expect(await listRealmRoleNamesOf(principal!.id)).toContain(SEAT_REALM_ROLE);
    }

    expect(
      new Set(clubIdsTheSeatsCarry).size,
      'two seat principals on one club let one visitor read the other visitor’s sandbox',
    ).toBe(SEAT_COUNT_V62_PROVISIONS);
  });
});

test.describe('demo mode — a visitor with no account enters a private sandbox club (real chain)', () => {
  test.describe.configure({ mode: 'serial' });

  test('AC-1 AC-2 AC-3 a visitor with no account reads a populated sandbox club, and every demo screen carries the banner', async ({
    browser,
    baseURL,
  }, testInfo) => {
    testInfo.setTimeout(ONE_COLD_LEASE_PLUS_FOUR_POPULATED_SCREENS_TIMEOUT_MS);
    const visitor = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    try {
      await expect(visitor.page.getByTestId(TESTIDS.clubAdminDashboard)).toBeVisible({
        timeout: THE_APPLICATION_BOOTS_FROM_A_COLD_DOCUMENT_TIMEOUT_MS,
      });
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      await expect(visitor.page.getByTestId(TESTIDS.pendingValidationValue)).not.toBeEmpty();
      await visitor.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-dashboard.png`,
        fullPage: true,
      });
      await expect(
        visitor.page.getByTestId(TESTIDS.pendingValidationError),
        'a tile that failed to load proves no sandbox data',
      ).toHaveCount(0);
      await expect(
        visitor.page.getByTestId(TESTIDS.todayFlightsError),
        'a tile that failed to load proves no sandbox data',
      ).toHaveCount(0);
      expect(
        await readTileValue(visitor.page, TESTIDS.pendingValidationValue),
        'every seeded flight still waits for validation, so this tile reads the seeded flight count',
      ).toBeGreaterThanOrEqual(SEEDED_FLIGHT_ROWS_PER_SEAT);
      expect(
        Number.isInteger(await readTileValue(visitor.page, TESTIDS.todayFlightsValue)),
        'the today tile reads the run date. The seeder writes its newest flying day two days ' +
          'before the run date, so this tile reads a real count and never an error.',
      ).toBe(true);

      await openTheSeededFlightHistory(visitor.page);
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      await visitor.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-flights.png`,
        fullPage: true,
      });
      expect(
        await visitor.page.locator(FLIGHT_ROW_SELECTOR).count(),
        'the logbook of the last 30 days holds the seeded flights',
      ).toBeGreaterThanOrEqual(SEEDED_FLIGHT_ROWS_OVER_THE_LAST_30_DAYS_FLOOR);

      await visitor.page.goto(`${AIRCRAFT_PATH}?lang=en`);
      await expect(visitor.page.getByTestId(TESTIDS.aircraftTable)).toBeVisible({
        timeout: THE_APPLICATION_BOOTS_FROM_A_COLD_DOCUMENT_TIMEOUT_MS,
      });
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      expect(
        await visitor.page.locator(AIRCRAFT_ROW_SELECTOR).count(),
        'the aircraft register holds the seeded fleet',
      ).toBeGreaterThanOrEqual(SEEDED_AIRCRAFT_ROWS_FLOOR);

      await visitor.page.goto(`${RESERVATIONS_PATH}?lang=en`);
      await expect(visitor.page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
      expect(
        await reservationsTheWeekGridRendersOverTheNextFourteenDays(visitor.page),
        'the reservation weeks of the next 14 days hold the seeded reservations',
      ).toBeGreaterThanOrEqual(SEEDED_RESERVATIONS_OVER_THE_NEXT_14_DAYS_FLOOR);

      await visitor.page.getByTestId(TESTIDS.demoBannerCta).click();
      await expect(visitor.page).toHaveURL(SIGNUP_MIGRATE_PATH_PATTERN);
    } finally {
      await visitor.context.close();
      await proofVideo(visitor.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'happy',
        caption:
          'A visitor with no account selects the demo action on the landing page and lands on a ' +
          'populated club. The dashboard tiles read the sandbox counts, the logbook of the last ' +
          '30 days holds at least 20 seeded flights, the aircraft register holds the seeded ' +
          'fleet, and the reservation weeks of the next 14 days hold at least 5 seeded ' +
          'reservations. Every screen carries the demo banner, and the banner action opens the ' +
          'migrate signup.',
      });
    }
  });

  test('AC-4 AC-5 a second visitor gets a different seat and never reads the first visitor’s change', async ({
    browser,
    baseURL,
  }, testInfo) => {
    testInfo.setTimeout(TWO_COLD_LEASES_PLUS_A_WRITE_ROUND_TRIP_TIMEOUT_MS);
    const visitorA = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    const visitorB = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    try {
      const seatOfVisitorA = await seatClaimsTheTabHolds(visitorA.page);
      const seatOfVisitorB = await seatClaimsTheTabHolds(visitorB.page);
      expect(
        seatOfVisitorB.clubId,
        'two visitors on one seat club read each other’s sandbox',
      ).not.toBe(seatOfVisitorA.clubId);

      await openTheSeededFlightHistory(visitorA.page);
      const flightsVisitorAReads = await visitorA.page.locator(FLIGHT_ROW_SELECTOR).count();
      const flightOfVisitorA = await openTheSeededThreeHourFlight(visitorA.page);
      const seededComment = await flightCommentInput(visitorA.page).inputValue();
      expect(seededComment, 'the sandbox seeder writes this flight a comment').not.toBe('');

      const changeOnlyVisitorAMakes = `visitor-a-${Date.now().toString(36)}`;
      await writeTheFlightCommentAndSave(visitorA.page, flightOfVisitorA, changeOnlyVisitorAMakes);

      await visitorA.page.reload();
      await waitForTheFlightsScreenOfAColdDocumentLoad(visitorA.page);
      await widenTheRangeToTheSeededFlightHistory(visitorA.page);
      expect(await openTheSeededThreeHourFlight(visitorA.page)).toBe(flightOfVisitorA);
      await expect(
        flightCommentInput(visitorA.page),
        'the change of the demo visitor stays after a page reload',
      ).toHaveValue(changeOnlyVisitorAMakes);

      await openTheSeededFlightHistory(visitorB.page);
      await expect(
        visitorB.page.getByTestId(`flights-row-${flightOfVisitorA}`),
        'the flight the first visitor changed exists in the database, and the second visitor ' +
          'must not read it',
      ).toHaveCount(0);
      expect(
        await visitorB.page.locator(FLIGHT_ROW_SELECTOR).count(),
        'the second visitor reads the flights of its own seat only, never both seats',
      ).toBe(SEEDED_FLIGHT_ROWS_PER_SEAT);
      expect(flightsVisitorAReads).toBe(SEEDED_FLIGHT_ROWS_PER_SEAT);

      const flightOfVisitorB = await openTheSeededThreeHourFlight(visitorB.page);
      expect(flightOfVisitorB, 'each seat holds its own copy of the seeded flight').not.toBe(
        flightOfVisitorA,
      );
      await expect(
        flightCommentInput(visitorB.page),
        'the second seat holds the seeded value',
      ).toHaveValue(seededComment);
      await visitorB.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-isolation.png`,
        fullPage: true,
      });
      await expect(
        flightCommentInput(visitorB.page),
        'and it never holds the value the first visitor wrote',
      ).not.toHaveValue(changeOnlyVisitorAMakes);
    } finally {
      await visitorA.context.close();
      await visitorB.context.close();
      await proofVideo(visitorB.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'happy',
        caption:
          'Two demo visitors hold two different seats. The first visitor changes a flight and ' +
          'reads the change back after a page reload. The second visitor opens its own copy of ' +
          'the same seeded flight and reads the seeded value. The row the first visitor changed ' +
          'is absent from the logbook of the second visitor, and that logbook holds the 24 rows ' +
          'of one seat, not the 48 rows of two.',
      });
    }
  });

  test('AC-6 a system administrator runs sandbox-reset and the seeded value comes back', async ({
    browser,
    baseURL,
  }, testInfo) => {
    testInfo.setTimeout(A_LEASE_A_WRITE_A_REAL_LOGIN_AND_THE_RESET_RUNS_TIMEOUT_MS);
    const visitor = await enterDemoFromTheLandingPage(browser, baseURL!, testInfo);
    const sysadmin = await loginAsSysadmin(browser, baseURL!, testInfo);
    try {
      await openTheSeededFlightHistory(visitor.page);
      const flightTheVisitorChanges = await openTheSeededThreeHourFlight(visitor.page);
      const seededComment = await flightCommentInput(visitor.page).inputValue();
      expect(seededComment, 'the sandbox seeder writes this flight a comment').not.toBe('');

      const changeTheResetMustRemove = `before-reset-${Date.now().toString(36)}`;
      await writeTheFlightCommentAndSave(
        visitor.page,
        flightTheVisitorChanges,
        changeTheResetMustRemove,
      );
      await openTheSeededFlightHistory(visitor.page);
      await openTheSeededThreeHourFlight(visitor.page);
      await expect(flightCommentInput(visitor.page)).toHaveValue(changeTheResetMustRemove);
      await visitor.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-flight-changed.png`,
        fullPage: true,
      });

      await sysadmin.page.goto(`${JOBS_PATH}?lang=en`);
      const resetRow = sysadmin.page
        .getByTestId(TESTIDS.jobRow)
        .filter({ hasText: SANDBOX_RESET_JOB });
      await expect(resetRow, 'the registry surfaces the sandbox reset job').toHaveCount(1);
      await expect(resetRow.getByTestId(TESTIDS.jobRowCron)).not.toBeEmpty();

      let seatsTheResetReclaimed = 0;
      let commentAfterTheReset = changeTheResetMustRemove;
      for (
        let run = 0;
        run < RESET_RUNS_BEFORE_THE_EXPIRED_LEASE_MUST_HAVE_BEEN_RECLAIMED &&
        commentAfterTheReset !== seededComment;
        run++
      ) {
        seatsTheResetReclaimed += await runTheSandboxResetOnce(sysadmin.page);
        await openTheSeededFlightHistory(visitor.page);
        await openTheSeededThreeHourFlight(visitor.page);
        commentAfterTheReset = await flightCommentInput(visitor.page).inputValue();
      }

      expect(
        seatsTheResetReclaimed,
        'the reset reclaims the seat whose lease expired, and it reports that it did',
      ).toBeGreaterThanOrEqual(1);
      await expect(
        flightCommentInput(visitor.page),
        'the reclaimed seat holds the seeded value again',
      ).toHaveValue(seededComment);
      await visitor.page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-demo-flight-restored.png`,
        fullPage: true,
      });
      await expect(
        flightCommentInput(visitor.page),
        'and the change the visitor made is gone',
      ).not.toHaveValue(changeTheResetMustRemove);
    } finally {
      await sysadmin.context.close();
      await visitor.context.close();
      await proofVideo(sysadmin.page, testInfo, {
        journey: JOURNEY_THIS_SPEC_PROVES,
        acTag: 'happy',
        caption:
          'A system administrator opens the scheduled-jobs console and runs sandbox-reset. The ' +
          'job completes and it reports the seats it reclaimed. The demo visitor opens the same ' +
          'seeded flight again and reads the seeded value. The change of that visitor is gone.',
      });
    }
  });
});
