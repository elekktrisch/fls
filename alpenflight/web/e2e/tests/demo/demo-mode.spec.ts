import { type Page, type Route } from '@playwright/test';
import {
  test,
  expect,
  allowConsoleErrors,
  consoleErrorAllowanceForStatusesOnEndpoint,
} from '../_helpers/console-guard';

// @mocked: http — the demo-session lease and the demo screens, mock-auth inner loop; the real chain runs in e2e/tests/real-idp/demo-sandbox.spec.ts

const DEMO_PATH = '/demo';
const START_PATH = '/start';
const FLIGHTS_PATH = '/flights';
const AIRCRAFT_PATH = '/aircraft';
const RESERVATIONS_PATH = '/reservations';

const DEMO_SESSION_ENDPOINT_PATHNAME = '/api/v1/public/demo-session';
const DEMO_SESSION_ENDPOINT_GLOB = `**${DEMO_SESSION_ENDPOINT_PATHNAME}`;

const TESTIDS = {
  landingDemoCta: 'landing-cta-demo',
  demoPage: 'demo-page',
  demoStart: 'demo-start',
  demoSeatBusy: 'demo-seat-busy',
  demoSeatBusyReason: 'demo-seat-busy-reason',
  demoBanner: 'demo-banner',
  demoBannerCta: 'demo-banner-cta',
  clubAdminDashboard: 'start-variant-clubadmin',
  flightsTable: 'flights-table',
  aircraftTable: 'aircraft-table',
  reservationsDayGrid: 'reservations-day-grid',
};

const DEMO_SCREENS_A_SEAT_PRINCIPAL_REACHES = [
  { path: FLIGHTS_PATH, testId: TESTIDS.flightsTable },
  { path: AIRCRAFT_PATH, testId: TESTIDS.aircraftTable },
  { path: RESERVATIONS_PATH, testId: TESTIDS.reservationsDayGrid },
];

const FUNNEL_CONSOLE_PREFIX = '[funnel]';

const VIEWPORT_TOO_SHORT_FOR_A_SCREEN_TO_FIT_SO_EVERY_SCREEN_SCROLLS = {
  width: 1280,
  height: 240,
};

const PIXELS_TO_SCROLL_PAST_THE_BANNERS_RESTING_PLACE = 1200;

const VIEWPORT_THE_OTHER_CASES_USE = { width: 1280, height: 720 };

const SEAT_CLUB_ID_V62_BUILDS_FOR_SEAT_ONE = '019e30c3-2c00-7001-8000-0000000de001';

const SEAT_TOKEN_CLAIMS_KEYCLOAK_MINTS_FOR_A_DIRECT_GRANT = {
  sub: 'e2f3a0c0-a001-4a2e-9c6e-22f3a0c0a001',
  preferred_username: 'demo1',
  email: 'demo1@example.com',
  given_name: 'Demo',
  family_name: 'Seat 1',
  clubId: SEAT_CLUB_ID_V62_BUILDS_FOR_SEAT_ONE,
  realm_access: { roles: ['CLUB_ADMINISTRATOR'] },
};

const UNSIGNED_TOKEN_SHAPED_LIKE_THE_ONE_KEYCLOAK_RETURNS_FOR_A_DIRECT_GRANT = [
  'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9',
  Buffer.from(JSON.stringify(SEAT_TOKEN_CLAIMS_KEYCLOAK_MINTS_FOR_A_DIRECT_GRANT)).toString(
    'base64url',
  ),
  'demo-seat-signature-placeholder',
].join('.');

const DEMO_SESSION_GRANTED_BODY = {
  accessToken: UNSIGNED_TOKEN_SHAPED_LIKE_THE_ONE_KEYCLOAK_RETURNS_FOR_A_DIRECT_GRANT,
  expiresInSeconds: 900,
  leaseExpiresAt: '2099-01-01T00:00:00Z',
};

const DEMO_POOL_EXHAUSTED_PROBLEM_BODY = {
  type: 'urn:alpenflight:problem:demo-pool-exhausted',
  title: 'No demo seat is free',
  status: 503,
  detail: 'All demo seats are in use. Please try again later.',
  instance: DEMO_SESSION_ENDPOINT_PATHNAME,
};

function grantsADemoSeat(leasedEndpointCalls: string[]) {
  return async (route: Route): Promise<void> => {
    leasedEndpointCalls.push(route.request().method());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(DEMO_SESSION_GRANTED_BODY),
    });
  };
}

async function refusesEveryDemoSeat(route: Route): Promise<void> {
  await route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify(DEMO_POOL_EXHAUSTED_PROBLEM_BODY),
  });
}

function recordsTheFunnelEvents(page: Page): string[] {
  const emitted: string[] = [];
  page.on('console', (message) => {
    if (message.text().startsWith(FUNNEL_CONSOLE_PREFIX)) {
      emitted.push(message.text());
    }
  });
  return emitted;
}

async function openTheDemoPageFromTheLanding(page: Page): Promise<void> {
  await page.goto('/?lang=en');
  await page.getByTestId(TESTIDS.landingDemoCta).click();
  await expect(page).toHaveURL(new RegExp(`${DEMO_PATH}(\\?|$|/)`));
  await expect(page.getByTestId(TESTIDS.demoPage)).toBeVisible();
}

test.describe('demo mode — the /demo front door and the demo banner (mocked inner loop)', () => {
  test('the landing demo action reaches /demo, and /demo offers a start action', async ({
    page,
  }) => {
    await openTheDemoPageFromTheLanding(page);

    await expect(page.getByTestId(TESTIDS.demoStart)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.demoSeatBusy)).toHaveCount(0);
    await page.screenshot({ path: 'screenshots/demo/01-entry.png', fullPage: true });
  });

  test('the start action leases a seat and lands the visitor on /start with the demo banner', async ({
    page,
  }) => {
    const leasedEndpointCalls: string[] = [];
    await page.route(DEMO_SESSION_ENDPOINT_GLOB, grantsADemoSeat(leasedEndpointCalls));

    await openTheDemoPageFromTheLanding(page);
    await page.getByTestId(TESTIDS.demoStart).click();

    await expect(page).toHaveURL(new RegExp(`${START_PATH}(\\?|$)`));
    await expect(page.getByTestId(TESTIDS.clubAdminDashboard)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
    expect(leasedEndpointCalls, 'the front door leases the seat with one POST').toEqual(['POST']);
    await page.screenshot({ path: 'screenshots/demo/02-start.png', fullPage: true });
  });

  test('the demo banner rides every demo screen and its action opens the migrate signup', async ({
    page,
  }) => {
    const funnelEvents = recordsTheFunnelEvents(page);
    await page.route(DEMO_SESSION_ENDPOINT_GLOB, grantsADemoSeat([]));

    await openTheDemoPageFromTheLanding(page);
    await page.getByTestId(TESTIDS.demoStart).click();
    await expect(page).toHaveURL(new RegExp(`${START_PATH}(\\?|$)`));

    await page.setViewportSize(VIEWPORT_TOO_SHORT_FOR_A_SCREEN_TO_FIT_SO_EVERY_SCREEN_SCROLLS);

    for (const { path, testId } of DEMO_SCREENS_A_SEAT_PRINCIPAL_REACHES) {
      await page.goto(`${path}?lang=en`);
      await expect(page.getByTestId(testId)).toBeVisible();
      await expect(
        page.getByTestId(TESTIDS.demoBanner),
        `the demo banner is permanent, so ${path} carries it too`,
      ).toBeVisible();

      await page.mouse.wheel(0, PIXELS_TO_SCROLL_PAST_THE_BANNERS_RESTING_PLACE);
      await expect
        .poll(() => page.evaluate(() => window.scrollY), {
          message: `${path} must scroll, else the permanence assertion passes vacuously`,
        })
        .toBeGreaterThan(0);
      const bannerAfterTheScroll = await page.getByTestId(TESTIDS.demoBanner).boundingBox();
      expect(
        bannerAfterTheScroll?.y,
        `the demo banner is permanent, so a scroll on ${path} keeps it at the top of the screen`,
      ).toBeCloseTo(0, 0);
    }

    await page.setViewportSize(VIEWPORT_THE_OTHER_CASES_USE);
    await page.goto(`${START_PATH}?lang=en`);
    await expect(page.getByTestId(TESTIDS.demoBanner)).toBeVisible();
    await page.screenshot({ path: 'screenshots/demo/03-banner.png', fullPage: true });
    await page.getByTestId(TESTIDS.demoBannerCta).click();
    await expect(page).toHaveURL(/\/signup\?.*intent=migrate/);

    expect(
      funnelEvents.filter((line) => line.includes('demo.session_started')),
      'the lease emits one funnel event for the started demo session',
    ).toHaveLength(1);
    expect(
      funnelEvents.filter((line) => line.includes('demo.signup_cta_click')),
      'the banner action emits one funnel event for the migrate signup click',
    ).toHaveLength(1);
  });

  test('a club principal that holds no demo seat reads no demo banner on the same screens', async ({
    page,
  }) => {
    for (const { path, testId } of DEMO_SCREENS_A_SEAT_PRINCIPAL_REACHES) {
      await page.goto(`${path}?lang=en`);
      await expect(page.getByTestId(testId)).toBeVisible();
      await expect(
        page.getByTestId(TESTIDS.demoBanner),
        `${path} carries the banner for a demo seat, so a club principal must read none`,
      ).toHaveCount(0);
    }
  });

  test('an exhausted pool keeps the visitor on /demo and shows a readable reason', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(
      testInfo,
      consoleErrorAllowanceForStatusesOnEndpoint([503], DEMO_SESSION_ENDPOINT_PATHNAME),
    );
    await page.route(DEMO_SESSION_ENDPOINT_GLOB, refusesEveryDemoSeat);

    await openTheDemoPageFromTheLanding(page);
    await page.getByTestId(TESTIDS.demoStart).click();

    await expect(page.getByTestId(TESTIDS.demoSeatBusy)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.demoSeatBusyReason)).toContainText(
      DEMO_POOL_EXHAUSTED_PROBLEM_BODY.detail,
    );
    await expect(page).toHaveURL(new RegExp(`${DEMO_PATH}(\\?|$|/)`));
    await expect(page.getByTestId(TESTIDS.demoBanner)).toHaveCount(0);
    await page.screenshot({ path: 'screenshots/demo/04-seat-busy.png', fullPage: true });
  });
});
