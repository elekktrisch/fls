import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { fillKcLogin } from './_helpers/kc-form';


interface SeededPrincipal {
  username: string;
  password: string;
}

const PILOT: SeededPrincipal = {
  username: 'pilot1@example.com',
  password: 'pilot1-dev-2026!',
};
const CLUB_ADMIN: SeededPrincipal = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};
const SYSADMIN: SeededPrincipal = {
  username: 'sysadmin@example.com',
  password: 'sysadmin-dev-2026!',
};

const CLUB1_TODAY_FLIGHTS = 3;
const CLUB1_PENDING_VALIDATION = 4;
const CLUB1_TODAY_AFTER_CREATE = CLUB1_TODAY_FLIGHTS + 1;
const SYSADMIN_MIN_CLUBS = 2;
const SYSADMIN_MIN_FLIGHTS = 14;
const SYSADMIN_MIN_USERS = 6;

const CLUB1_MOTOR_IMMAT = 'HB-MOT1';

async function loginAsRole(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
  principal: SeededPrincipal,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ baseURL });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  const page = await context.newPage();
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, principal.username, principal.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await page.goto('/start?lang=en');
  return { context, page };
}

async function captureBearer(page: Page): Promise<string> {
  const bearerPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
    { timeout: 10_000 },
  );
  await page.goto('/flights');
  const req = await bearerPromise;
  return req.headers()['authorization']!;
}

function todayLocalDate(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

interface AircraftListItem {
  id: string;
  immatriculation: string;
}

async function resolveClub1MotorAircraftId(
  api: APIRequestContext,
  bearer: string,
): Promise<string> {
  const res = await api.get('/api/v1/aircraft', { headers: { authorization: bearer } });
  expect(res.ok(), `GET /api/v1/aircraft (${res.status()})`).toBeTruthy();
  const list = (await res.json()) as AircraftListItem[];
  const motor = list.find((a) => a.immatriculation === CLUB1_MOTOR_IMMAT);
  expect(
    motor,
    `the showcase club-1 motor aircraft ${CLUB1_MOTOR_IMMAT} must be on the tenant aircraft list`,
  ).toBeTruthy();
  return motor!.id;
}

async function createClub1TodayMotorFlight(
  api: APIRequestContext,
  bearer: string,
  aircraftId: string,
): Promise<void> {
  const res = await api.post('/api/v1/flights', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightAircraftType: 'MOTOR',
      aircraftId,
      flightDate: todayLocalDate(),
      noStartTimeInformation: true,
      noLdgTimeInformation: true,
      isSoloFlight: false,
      comment: 'J-3 SSE live-update drive',
    },
  });
  expect(
    res.status(),
    `POST /api/v1/flights (motor, today) must 201 — body: ${await res.text().catch(() => '?')}`,
  ).toBe(201);
}

async function captureVariantShot(page: Page, testInfo: TestInfo, variant: string): Promise<void> {
  await page.screenshot({
    path: `${testInfo.outputDir}/alpenflight-start-${variant}.png`,
    fullPage: true,
  });
}

test.describe('J-3 dashboard (/start) — role variants [showcase seed, real chain]', () => {
  test('pilot principal renders the pilot variant with a populated last-flight card', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsRole(browser, baseURL!, testInfo, PILOT);
    try {
      await expect(page.getByTestId('start-variant-pilot')).toBeVisible();
      await expect(page.getByTestId('start-greeting')).toBeVisible();

      await captureVariantShot(page, testInfo, 'pilot');

      await expect(page.getByTestId('start-last-flight-card')).toBeVisible({ timeout: 15_000 });
      await expect(page.getByTestId('start-last-flight-empty')).toHaveCount(0);
      await expect(page.getByTestId('start-last-flight-error')).toHaveCount(0);
      await expect(page.getByTestId('start-last-flight-role')).not.toHaveText('—');

      await expect(page.getByTestId('start-variant-clubadmin')).toHaveCount(0);
      await expect(page.getByTestId('start-variant-sysadmin')).toHaveCount(0);
      await expect(page.getByTestId('start-pilot-view-toggle')).toHaveCount(0);

      await captureVariantShot(page, testInfo, 'pilot');
    } finally {
      await context.close();
    }
  });

  test('club-admin (club-1) renders today-flights=3 + pending-validation=4 (showcase club-1)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsRole(browser, baseURL!, testInfo, CLUB_ADMIN);
    try {
      await expect(page.getByTestId('start-variant-clubadmin')).toBeVisible();

      await captureVariantShot(page, testInfo, 'clubadmin');

      await expect(page.getByTestId('start-tile-today-flights-value')).toHaveText(
        String(CLUB1_TODAY_FLIGHTS),
      );
      await expect(page.getByTestId('start-tile-pending-validation-value')).toHaveText(
        String(CLUB1_PENDING_VALIDATION),
      );

      await captureVariantShot(page, testInfo, 'clubadmin');
    } finally {
      await context.close();
    }
  });

  test('sysadmin renders cross-tenant tiles (≥2 clubs / ≥14 flights / ≥6 users) + tenant-enter', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsRole(browser, baseURL!, testInfo, SYSADMIN);
    try {
      await expect(page.getByTestId('start-variant-sysadmin')).toBeVisible();

      await captureVariantShot(page, testInfo, 'sysadmin');

      const clubs = Number(
        (await page.getByTestId('start-tile-total-clubs-value').textContent())?.trim(),
      );
      const flights = Number(
        (await page.getByTestId('start-tile-total-flights-value').textContent())?.trim(),
      );
      const users = Number(
        (await page.getByTestId('start-tile-total-users-value').textContent())?.trim(),
      );
      expect(clubs, 'sysadmin total-clubs ≥ showcase 2-club floor').toBeGreaterThanOrEqual(
        SYSADMIN_MIN_CLUBS,
      );
      expect(flights, 'sysadmin total-flights ≥ showcase 14-flight floor').toBeGreaterThanOrEqual(
        SYSADMIN_MIN_FLIGHTS,
      );
      expect(users, 'sysadmin total-users ≥ showcase 6-principal floor').toBeGreaterThanOrEqual(
        SYSADMIN_MIN_USERS,
      );

      await expect(page.getByTestId('start-tenant-enter')).toBeVisible();

      await captureVariantShot(page, testInfo, 'sysadmin');
    } finally {
      await context.close();
    }
  });

  test('today-flights tile updates live (3 → 4) on flight.created without a reload', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsRole(browser, baseURL!, testInfo, CLUB_ADMIN);
    try {
      await expect(page.getByTestId('start-variant-clubadmin')).toBeVisible();
      const tile = page.getByTestId('start-tile-today-flights-value');
      await expect(tile).toHaveText(String(CLUB1_TODAY_FLIGHTS));

      const actorPage = await context.newPage();
      try {
        const bearer = await captureBearer(actorPage);
        const motorAircraftId = await resolveClub1MotorAircraftId(context.request, bearer);
        await createClub1TodayMotorFlight(context.request, bearer, motorAircraftId);
      } finally {
        await actorPage.close();
      }

      await expect(tile).toHaveText(String(CLUB1_TODAY_AFTER_CREATE), { timeout: 15_000 });
    } finally {
      await context.close();
    }
  });

  test('GET /api/v1/me/events with no token is rejected 401 (no anonymous stream)', async ({
    request,
  }) => {
    const res = await request.get('/api/v1/me/events', {
      headers: { accept: 'text/event-stream' },
    });
    expect(res.ok()).toBeFalsy();
    expect(res.status()).toBe(401);
  });
});
