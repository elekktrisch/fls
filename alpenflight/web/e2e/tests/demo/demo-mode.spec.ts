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

const UNSIGNED_TOKEN_SHAPED_LIKE_THE_ONE_KEYCLOAK_RETURNS_FOR_A_DIRECT_GRANT = [
  'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9',
  'eyJzdWIiOiJkZW1vMSIsInByZWZlcnJlZF91c2VybmFtZSI6ImRlbW8xIn0',
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

async function openTheDemoPageFromTheLanding(page: Page): Promise<void> {
  await page.goto('/?lang=en');
  await page.getByTestId(TESTIDS.landingDemoCta).click();
  await expect(page).toHaveURL(new RegExp(`${DEMO_PATH}(\\?|$|/)`));
  await expect(page.getByTestId(TESTIDS.demoPage)).toBeVisible();
}

test.describe('demo mode — the /demo front door and the demo banner (mocked inner loop)', () => {
  test.fixme('the landing demo action reaches /demo, and /demo offers a start action [T-12 unskips this]', async ({
    page,
  }) => {
    await openTheDemoPageFromTheLanding(page);

    await expect(page.getByTestId(TESTIDS.demoStart)).toBeVisible();
    await expect(page.getByTestId(TESTIDS.demoSeatBusy)).toHaveCount(0);
    await page.screenshot({ path: 'screenshots/demo/01-entry.png', fullPage: true });
  });

  test.fixme('the start action leases a seat and lands the visitor on /start with the demo banner [T-12 unskips this]', async ({
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

  test.fixme('the demo banner rides every demo screen and its action opens the migrate signup [T-12 unskips this]', async ({
    page,
  }) => {
    await page.route(DEMO_SESSION_ENDPOINT_GLOB, grantsADemoSeat([]));

    await openTheDemoPageFromTheLanding(page);
    await page.getByTestId(TESTIDS.demoStart).click();
    await expect(page).toHaveURL(new RegExp(`${START_PATH}(\\?|$)`));

    for (const { path, testId } of [
      { path: FLIGHTS_PATH, testId: TESTIDS.flightsTable },
      { path: AIRCRAFT_PATH, testId: TESTIDS.aircraftTable },
      { path: RESERVATIONS_PATH, testId: TESTIDS.reservationsDayGrid },
    ]) {
      await page.goto(`${path}?lang=en`);
      await expect(page.getByTestId(testId)).toBeVisible();
      await expect(
        page.getByTestId(TESTIDS.demoBanner),
        `the demo banner is permanent, so ${path} carries it too`,
      ).toBeVisible();
    }

    await page.getByTestId(TESTIDS.demoBannerCta).click();
    await expect(page).toHaveURL(/\/signup\?.*intent=migrate/);
    await page.screenshot({ path: 'screenshots/demo/03-banner.png', fullPage: true });
  });

  test.fixme('an exhausted pool keeps the visitor on /demo and shows a readable reason [T-12 unskips this]', async ({
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
