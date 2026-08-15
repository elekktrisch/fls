import { existsSync } from 'node:fs';

import {
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import type { FlightDetail, FlightUpdateRequest } from '../../../src/app/api/generated/model';

import { selectAfOption } from '../_helpers/af-select';
import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import {
  AEROTOW_START_TYPE_ID,
  loginAsMigratedAdmin,
  seedFlightMasterdata,
  seedFlightParity,
  type FlightMasterdata,
  type FlightParityFixture,
} from './_helpers/flight-parity-fixture';
import { proofVideo } from './_helpers/proof-video';


async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function bearerFromFlightsList(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      new URL(req.url()).pathname === '/api/v1/flights' &&
      typeof req.headers()['authorization'] === 'string',
  );
  await page.goto('/flights');
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

function flightIdFromLocation(location: string | undefined): string {
  expect(
    location,
    'POST /api/v1/flights must return a 201 Location header (FlightsController.create)',
  ).toBeTruthy();
  const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
  expect(id, `Location "${location}" must end in a flight id`).toMatch(/^fl-[0-9a-f-]{36}$/);
  return id;
}

async function createGliderFlightAerotow(
  page: Page,
  md: FlightMasterdata,
  comment: string,
  shots?: { testInfo: TestInfo },
): Promise<string> {
  await page.goto('/flights/new');
  await expect(page.getByTestId('flight-form')).toBeVisible();
  await expect(page.getByTestId('flight-step-launch')).toBeVisible();

  await selectAfOption(page, 'flight-edit-startType', AEROTOW_START_TYPE_ID);
  await selectAfOption(page, 'flight-edit-startLocation', md.locationId, 'J2 Airfield');

  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-glider')).toBeVisible();
  await selectAfOption(page, 'flight-edit-glider-aircraft', md.gliderAircraftId, md.gliderImmat);
  await selectAfOption(page, 'flight-edit-glider-flightType', md.gliderFlightTypeId, 'J2 Local');
  await selectAfOption(page, 'flight-edit-glider-pilot', md.pilotPersonId, 'Pilot');
  await page.getByTestId('flight-edit-glider-startTime').locator('input').fill('09:00');
  await page.getByTestId('flight-edit-glider-ldgTime').locator('input').fill('10:30');
  await page.getByTestId('flight-edit-glider-comment').locator('input').fill(comment);

  if (shots) {
    await page.screenshot({
      path: `${shots.testInfo.outputDir}/alpenflight-flights-wizard-glider.png`,
      fullPage: true,
    });
  }

  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-tow')).toBeVisible();
  await selectAfOption(page, 'flight-edit-tow-aircraft', md.towAircraftId, md.towImmat);
  await selectAfOption(page, 'flight-edit-tow-pilot', md.towPilotPersonId, 'TowPilot');

  if (shots) {
    await expect(page.getByTestId('flight-step-tow')).toBeVisible();
    await page.screenshot({
      path: `${shots.testInfo.outputDir}/alpenflight-flights-wizard-tow.png`,
      fullPage: true,
    });
  }

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/flights' &&
      r.status() >= 200 &&
      r.status() < 300,
    { timeout: 5_000 },
  );
  await page.getByTestId('flight-submit-header').click();
  const createdResp = await created;

  const flightId = flightIdFromLocation(createdResp.headers()['location']);
  await expect(page).toHaveURL(/\/flights$/);

  const row = page
    .locator('[data-testid^="flights-row-"]')
    .filter({ has: page.locator(`text="${md.gliderImmat}"`) })
    .first();
  await expect(row, 'created glider flight must appear in the list').toBeVisible();
  return flightId;
}

async function createMotorFlight(
  page: Page,
  md: FlightMasterdata,
  comment: string,
  shots?: { testInfo: TestInfo },
): Promise<{ id: string; flightAircraftType: string }> {
  await page.goto('/flights/new');
  await expect(page.getByTestId('flight-form')).toBeVisible();
  await expect(page.getByTestId('flight-step-launch')).toBeVisible();

  await selectAfOption(page, 'flight-edit-startLocation', md.locationId, 'J2 Airfield');

  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-glider')).toBeVisible();
  await selectAfOption(page, 'flight-edit-glider-aircraft', md.motorAircraftId, md.motorImmat);
  await selectAfOption(page, 'flight-edit-glider-flightType', md.gliderFlightTypeId, 'J2 Local');
  await selectAfOption(page, 'flight-edit-glider-pilot', md.pilotPersonId, 'Pilot');
  await page.getByTestId('flight-edit-glider-startTime').locator('input').fill('11:00');
  await page.getByTestId('flight-edit-glider-ldgTime').locator('input').fill('12:00');
  await page.getByTestId('flight-edit-glider-comment').locator('input').fill(comment);

  await expect(page.getByTestId('flight-step-tow')).toHaveCount(0);

  if (shots) {
    await page.screenshot({
      path: `${shots.testInfo.outputDir}/alpenflight-motor-form.png`,
      fullPage: true,
    });
  }

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/flights' &&
      r.status() >= 200 &&
      r.status() < 300,
    { timeout: 5_000 },
  );
  await page.getByTestId('flight-submit-header').click();
  const createdResp = await created;

  const id = flightIdFromLocation(createdResp.headers()['location']);

  const bearer = createdResp.request().headers()['authorization'];

  await expect(page).toHaveURL(/\/flights$/);

  const detail = await page.request.get(`/api/v1/flights/${id}`, {
    headers: { authorization: bearer! },
  });
  expect(detail.status(), 'the just-created motor flight is readable').toBe(200);
  const { flightAircraftType } = (await detail.json()) as { flightAircraftType: string };
  return { id, flightAircraftType };
}

function detailToUpdateRequest(d: FlightDetail, comment: string): FlightUpdateRequest {
  const req: FlightUpdateRequest = {
    aircraftId: d.aircraftId,
    crew: d.crew,
    comment,
  };
  if (d.flightDate != null) req.flightDate = d.flightDate;
  if (d.startDateTime != null) req.startDateTime = d.startDateTime;
  if (d.ldgDateTime != null) req.ldgDateTime = d.ldgDateTime;
  if (d.startLocationId != null) req.startLocationId = d.startLocationId;
  if (d.ldgLocationId != null) req.ldgLocationId = d.ldgLocationId;
  if (d.flightTypeId != null) req.flightTypeId = d.flightTypeId;
  if (d.startTypeId != null) req.startTypeId = d.startTypeId;
  if (d.towFlightId != null) req.towFlightId = d.towFlightId;
  if (d.nrOfLdgs != null) req.nrOfLdgs = d.nrOfLdgs;
  if (d.outboundRoute != null) req.outboundRoute = d.outboundRoute;
  if (d.inboundRoute != null) req.inboundRoute = d.inboundRoute;
  if (d.flightCostBalanceTypeId != null) req.flightCostBalanceTypeId = d.flightCostBalanceTypeId;
  if (d.couponNumber != null) req.couponNumber = d.couponNumber;
  if (d.engineStartOperatingCounterInSeconds != null) {
    req.engineStartOperatingCounterInSeconds = d.engineStartOperatingCounterInSeconds;
  }
  if (d.engineEndOperatingCounterInSeconds != null) {
    req.engineEndOperatingCounterInSeconds = d.engineEndOperatingCounterInSeconds;
  }
  req.isSoloFlight = d.isSoloFlight;
  req.noStartTimeInformation = d.noStartTimeInformation;
  req.noLdgTimeInformation = d.noLdgTimeInformation;
  return req;
}

function fieldErrors(page: Page, testId: string): Locator {
  return page.locator('af-form-field', { has: page.getByTestId(testId) }).getByRole('alert');
}

function saveButton(page: Page): Locator {
  return page.getByTestId('flight-submit-header').locator('button');
}

async function createMinimalGliderFlight(
  page: Page,
  md: FlightMasterdata,
  flightDateOverride?: string,
): Promise<string> {
  await page.goto('/flights/new');
  await expect(page.getByTestId('flight-form')).toBeVisible();
  await expect(page.getByTestId('flight-step-launch')).toBeVisible();

  if (flightDateOverride) {
    await page.getByTestId('flight-edit-flightDate').locator('input').fill(flightDateOverride);
  }

  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-glider')).toBeVisible();
  await selectAfOption(page, 'flight-edit-glider-aircraft', md.gliderAircraftId, md.gliderImmat);
  await selectAfOption(page, 'flight-edit-glider-pilot', md.pilotPersonId, 'Pilot');

  await expect(saveButton(page)).toBeEnabled();

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/flights' &&
      r.status() >= 200 &&
      r.status() < 300,
    { timeout: 5_000 },
  );
  await page.getByTestId('flight-submit-header').click();
  const createdResp = await created;
  return flightIdFromLocation(createdResp.headers()['location']);
}

const WINCH_START_TYPE_ID = '019e2e15-2c00-7fa0-8000-000000000fa0';

async function createFullyPopulatedGliderFlight(page: Page, md: FlightMasterdata): Promise<string> {
  await page.goto('/flights/new');
  await expect(page.getByTestId('flight-form')).toBeVisible();
  await expect(page.getByTestId('flight-step-launch')).toBeVisible();

  await selectAfOption(page, 'flight-edit-startType', WINCH_START_TYPE_ID);
  await selectAfOption(page, 'flight-edit-startLocation', md.locationId, 'J2 Airfield');

  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-glider')).toBeVisible();
  await selectAfOption(page, 'flight-edit-glider-aircraft', md.gliderAircraftId, md.gliderImmat);
  await selectAfOption(page, 'flight-edit-glider-flightType', md.gliderFlightTypeId, 'J2 Local');
  await selectAfOption(page, 'flight-edit-glider-pilot', md.pilotPersonId, 'Pilot');
  await page.getByTestId('flight-edit-glider-startTime').locator('input').fill('09:00');
  await page.getByTestId('flight-edit-glider-ldgTime').locator('input').fill('10:30');
  await page.getByTestId('flight-edit-glider-nrOfLdgs').locator('input').fill('1');

  await expect(page.getByTestId('flight-step-tow')).toHaveCount(0);

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/flights' &&
      r.status() >= 200 &&
      r.status() < 300,
    { timeout: 5_000 },
  );
  await page.getByTestId('flight-submit-header').click();
  const createdResp = await created;
  return flightIdFromLocation(createdResp.headers()['location']);
}

function isoToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function isoDaysAhead(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const OLDEST_SEEDED_FLIGHT_OFFSET_DAYS = 10;

async function widenFlightListRangeToRecent(page: Page): Promise<void> {
  const refetch = page.waitForResponse(
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
  const overlay = page.locator('.cdk-overlay-container .ant-picker-panel-container');
  const inputs = page.getByTestId('flights-date-range').locator('input');

  await inputs.first().click();
  await expect(overlay).toBeVisible();
  await overlay.locator('.ant-picker-panel').first().locator('.ant-picker-header-prev-btn').click();

  const cells = overlay.locator(
    '.ant-picker-cell-in-view:not(.ant-picker-cell-disabled) .ant-picker-cell-inner',
  );
  const count = await cells.count();
  await cells.first().click();
  await cells.nth(count - 1).click();

  const committed = new URL((await refetch).url());
  const from = committed.searchParams.get('from')!;
  const to = committed.searchParams.get('to')!;
  expect(from <= to, `committed range must be ordered (from=${from} to=${to})`).toBe(true);
  const oldestSeeded = isoDaysAhead(-OLDEST_SEEDED_FLIGHT_OFFSET_DAYS);
  expect(
    from <= oldestSeeded && oldestSeeded <= to,
    `committed range [${from}, ${to}] must span the oldest seeded flight date ${oldestSeeded}`,
  ).toBe(true);
}

test.describe('Flight list+edit — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  let masterdata: FlightMasterdata;
  let flightId: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    testInfo.setTimeout(180_000);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL, 'flt');

    const ctx = await browser.newContext({ baseURL });
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const bearer = await bearerFromFlightsList(page);
      masterdata = await seedFlightMasterdata(request, bearer);
    } finally {
      await ctx.close();
    }
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('club admin lists /flights and creates a glider flight (AEROTOW) with a linked tow', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto('/flights');
      await expect(page.locator('h1')).toHaveText('Flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      flightId = await createGliderFlightAerotow(page, masterdata, 'created by J-2 e2e', {
        testInfo,
      });
      expect(flightId).toMatch(/^fl-[0-9a-f-]{36}$/);

      await expect(page.getByTestId(`flights-immat-${flightId}`)).toHaveText(
        masterdata.gliderImmat,
      );

      const bearer = await bearerFromFlightsList(page);
      const detail = await ctx.request.get(`/api/v1/flights/${flightId}`, {
        headers: { authorization: bearer },
      });
      expect(detail.status(), 'the created glider flight is readable').toBe(200);
      const gliderBody = (await detail.json()) as { towFlightId?: string };
      expect(
        gliderBody.towFlightId,
        'the AEROTOW glider must carry a towFlightId (paired save, S-063)',
      ).toBeTruthy();
      const towDetail = await ctx.request.get(`/api/v1/flights/${gliderBody.towFlightId}`, {
        headers: { authorization: bearer },
      });
      expect(towDetail.status(), 'the linked tow flight is readable').toBe(200);
      const towBody = (await towDetail.json()) as {
        flightAircraftType: string;
        aircraftId: string;
      };
      expect(towBody.flightAircraftType, 'the linked flight is a distinct TOW row').toBe('TOW');
      expect(towBody.aircraftId, 'the tow row flies the tow aircraft').toBe(
        masterdata.towAircraftId,
      );

      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-list.png`,
        fullPage: true,
      });

      for (const png of [
        'alpenflight-flights-list.png',
        'alpenflight-flights-wizard-glider.png',
        'alpenflight-flights-wizard-tow.png',
      ]) {
        expect(
          existsSync(`${testInfo.outputDir}/${png}`),
          `expected AlpenFlight parity screenshot ${png} in the test output dir — ` +
            "the fanout gallery's J-2 AlpenFlight half depends on it",
        ).toBeTruthy();
      }
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · flight logbook · club admin logs in via real Keycloak, lists /flights, and creates ' +
          'a glider flight via the 3-step wizard with start-type AEROTOW — the paired tow is created ' +
          'and linked in the same save (glider.towFlightId → a distinct TOW row)',
        acTag: 'happy',
      });
    }
  });

  test('club admin creates a motor flight (MOTOR aircraft, no tow) that lists in /flights', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto('/flights');
      await expect(page.locator('h1')).toHaveText('Flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      const createdBody = await createMotorFlight(page, masterdata, 'motor by J-2 e2e', {
        testInfo,
      });

      expect(createdBody.id, 'the motor create POST returns the created flight id').toMatch(
        /^fl-[0-9a-f-]{36}$/,
      );
      expect(
        createdBody.flightAircraftType,
        'a flight created with a motor aircraft selected must be stamped MOTOR — not GLIDER',
      ).toBe('MOTOR');

      const row = page
        .locator('[data-testid^="flights-row-"]')
        .filter({ has: page.locator(`text="${masterdata.motorImmat}"`) })
        .first();
      await expect(
        row,
        'the created motor flight appears in the unified /flights list',
      ).toBeVisible();
      const motorId = (await row.getAttribute('data-testid'))!.replace(/^flights-row-/, '');
      await expect(page.getByTestId(`flights-aircraft-type-${motorId}`)).toContainText('Motor');

      expect(
        existsSync(`${testInfo.outputDir}/alpenflight-motor-form.png`),
        'expected AlpenFlight parity screenshot alpenflight-motor-form.png in the test output dir — ' +
          "the fanout gallery's J-2 legacy-/airmovements ↔ AlpenFlight-motor pairing depends on it",
      ).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · motor flight · a motor flight (motor aircraft, no tow) is created via the unified ' +
          '/flights wizard and renders in the SAME /flights list with aircraft type "Motor" — ' +
          "legacy's separate /airmovements screen is NOT carried forward",
        acTag: 'happy',
      });
    }
  });

  test('club admin edits the glider flight; the change persists and re-renders', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto(`/flights/${flightId}/edit`);
      await expect(page.getByTestId('flight-form')).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-form.png`,
        fullPage: true,
      });

      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `/api/v1/flights/${flightId}` &&
          r.status() === 200,
        { timeout: 5_000 },
      );
      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-step-glider')).toBeVisible();
      await page
        .getByTestId('flight-edit-glider-comment')
        .locator('input')
        .fill('edited by J-2 e2e');
      await page.getByTestId('flight-submit-header').click();
      await updated;
      await expect(page).toHaveURL(/\/flights$/);

      await page.goto(`/flights/${flightId}/edit`);
      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-edit-glider-comment').locator('input')).toHaveValue(
        'edited by J-2 e2e',
      );
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · flight logbook · the club admin edits a flight and the change persists across a ' +
          'reload (real backend round-trip)',
        acTag: 'happy',
      });
    }
  });

  test('club admin deletes a flight; it leaves the list', async ({ browser }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const victimId = await createGliderFlightAerotow(page, masterdata, 'to-delete by J-2 e2e');

      await page.goto('/flights');
      await page.getByTestId(`flights-kebab-${victimId}`).click();
      await page.getByTestId(`flights-delete-${victimId}`).click();
      await expect(page.getByTestId('af-dialog-title')).toContainText('Delete this flight?');
      await page.getByTestId('af-dialog-confirm').click();

      await expect(page.getByTestId(`flights-row-${victimId}`)).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · flight logbook · the club admin deletes a flight (CLUB_ADMINISTRATOR gate) and it ' +
          'leaves the list',
        acTag: 'happy',
      });
    }
  });

  test('cross-tenant flight GET 404s (single-flight @TenantId scope, oracle #24)', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);
      const bearer = await bearerFromFlightsList(page);

      const read = await ctx.request.get(`/api/v1/flights/${flightId}`, {
        headers: { authorization: bearer },
      });
      expect(read.status(), 'a cross-tenant flight GET must 404 (not 403/200)').toBe(404);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          "J-2 · tenant scope · a caller from a different club cannot read another tenant's flight " +
          '(GET 404, structural @TenantId scope)',
        acTag: 'key-error',
      });
    }
  });

  test('412 optimistic concurrency: a stale PUT opens the inline per-field conflict diff', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      allowConsoleErrors(testInfo, /\b412\b/);

      const bearer = await bearerFromFlightsList(page);

      const before = await ctx.request.get(`/api/v1/flights/${flightId}`, {
        headers: { authorization: bearer },
      });
      expect(before.status()).toBe(200);
      const detail = (await before.json()) as FlightDetail;

      await page.goto(`/flights/${flightId}/edit`);
      await expect(page.getByTestId('flight-form')).toBeVisible();

      const conflictingComment = 'changed-by-other-writer';
      const oobBody = detailToUpdateRequest(detail, conflictingComment);
      const oob = await ctx.request.put(`/api/v1/flights/${flightId}`, {
        headers: {
          authorization: bearer,
          'content-type': 'application/json',
          'If-Match': String(detail.version),
        },
        data: oobBody,
      });
      expect(oob.status(), 'the out-of-band write succeeds, bumping the server version').toBe(200);

      let putCount = 0;
      await page.route(`**/api/v1/flights/${flightId}`, async (route) => {
        if (route.request().method() === 'PUT') {
          putCount += 1;
        }
        await route.continue();
      });

      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-step-glider')).toBeVisible();
      await page.getByTestId('flight-edit-glider-comment').locator('input').fill('my-local-edit');
      await page.getByTestId('flight-submit-header').click();

      await expect(page.getByTestId('flight-conflict-dialog')).toBeVisible();
      await expect(page.getByTestId('flight-conflict-field-comment')).toBeVisible();
      await expect(page.getByTestId('flight-conflict-keep-mine-comment')).toBeVisible();
      const keepTheirs = page.getByTestId('flight-conflict-keep-theirs-comment');
      await expect(keepTheirs).toContainText(conflictingComment);
      await expect(
        page.locator('[data-testid^="flight-conflict-keep-mine-"]').first(),
      ).toBeFocused();

      expect(putCount, 'a 412 must NOT auto-retry — exactly one PUT before the dialog').toBe(1);

      await expect(page.getByTestId('flight-409-toast')).toHaveCount(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-conflict.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · optimistic concurrency · a stale PUT (old If-Match) returns 412 and opens the ' +
          'inline per-field keep-mine/keep-theirs conflict diff (no auto-retry; net-new affordance)',
        acTag: 'key-error',
      });
    }
  });
});

test.describe('Flight list+edit — migrated legacy flight renders (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  let fixture: FlightParityFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    testInfo.setTimeout(180_000);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await seedFlightParity(browser, request, baseURL, testInfo.retry);
  });

  test('the migrated glider flight (crew + tow link) renders in the owning club /flights list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.owner);
      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      await widenFlightListRangeToRecent(page);

      const row = page
        .locator('[data-testid^="flights-row-"]')
        .filter({ has: page.locator(`text="${fixture.gliderImmatriculation}"`) })
        .first();
      await expect(
        row,
        `migrated glider "${fixture.gliderImmatriculation}" must appear in the owning club's list`,
      ).toBeVisible();
      const rowId = (await row.getAttribute('data-testid'))!.replace(/^flights-row-/, '');
      expect(rowId, 'migrated flight row must carry a flights-row-<id> testid').toMatch(
        /^fl-[0-9a-f-]{36}$/,
      );

      const bearer = await bearerFromFlightsList(page);
      const detail = await ctx.request.get(`/api/v1/flights/${rowId}`, {
        headers: { authorization: bearer },
      });
      expect(detail.status(), 'the migrated glider flight is readable in its own club').toBe(200);
      const body = (await detail.json()) as {
        flightAircraftType: string;
        towFlightId?: string;
        crew?: unknown[];
      };
      expect(body.flightAircraftType).toBe('GLIDER');
      expect(
        body.towFlightId,
        'the migrated glider carries the two-pass tow self-FK link',
      ).toBeTruthy();
      expect(
        (body.crew ?? []).length,
        'the migrated glider carries its migrated FlightCrew (pilot + co-pilot)',
      ).toBeGreaterThanOrEqual(2);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-migrated-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-29',
        caption:
          'J-29 · scheduled-proof stabilization · after widening the /flights date range (single ' +
          'from≠to refetch), the recent-past migrated legacy Flight + FlightCrew renders in the owning ' +
          "club's /flights list under its immatriculation (crew + tow link; full " +
          'legacy→migrate→Keycloak→UI chain)',
        acTag: 'happy',
      });
    }
  });

  test('a migrated DeliveryBooked flight is read-only: edit + delete return 4xx (oracle #12)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.owner);
      const bearer = await bearerFromFlightsList(page);


      const list = await ctx.request.get('/api/v1/flights', { headers: { authorization: bearer } });
      expect(list.status()).toBe(200);
      const items = ((await list.json()) as { items: { id: string; processState: string }[] })
        .items;
      const booked = items.find((f) => f.processState === 'DELIVERY_BOOKED');
      expect(
        booked,
        'the T-08 parity bundle seeds a DeliveryBooked(60) read-only flight',
      ).toBeTruthy();
      const bookedId = booked!.id;

      const read = await ctx.request.get(`/api/v1/flights/${bookedId}`, {
        headers: { authorization: bearer },
      });
      expect(read.status(), 'a DeliveryBooked flight is still readable').toBe(200);
      const detail = (await read.json()) as Record<string, unknown> & { version: number };

      const put = await ctx.request.put(`/api/v1/flights/${bookedId}`, {
        headers: {
          authorization: bearer,
          'content-type': 'application/json',
          'If-Match': String(detail.version),
        },
        data: { ...detail, comment: 'attempt-edit-booked' },
      });
      expect(
        put.status(),
        'editing a DeliveryBooked flight must be rejected (4xx state gate)',
      ).toBeGreaterThanOrEqual(400);
      expect(put.status()).toBeLessThan(500);

      const del = await ctx.request.delete(`/api/v1/flights/${bookedId}`, {
        headers: { authorization: bearer, 'If-Match': String(detail.version) },
      });
      expect(
        del.status(),
        'deleting a DeliveryBooked flight must be rejected (4xx state gate)',
      ).toBeGreaterThanOrEqual(400);
      expect(del.status()).toBeLessThan(500);

      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();
      await widenFlightListRangeToRecent(page);
      await page.getByTestId(`flights-kebab-${bookedId}`).click();
      await expect(page.getByTestId(`flights-delete-disabled-${bookedId}`)).toBeVisible();
      await expect(page.getByTestId(`flights-delete-${bookedId}`)).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · read-only state · a DeliveryBooked flight rejects edit + delete (4xx state gate; ' +
          'Locked stays editable per oracle #12). The Valid→Locked lock is LOCK_JOB-driven (proven ' +
          'at FlightTimeGateIT), so the e2e asserts the read-only consequence, not the transition.',
        acTag: 'key-error',
      });
    }
  });
});

test.describe('Flight hardening — create-persists + edit validation (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  let masterdata: FlightMasterdata;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    testInfo.setTimeout(180_000);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL, 'fhd');
    const ctx = await browser.newContext({ baseURL });
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const bearer = await bearerFromFlightsList(page);
      masterdata = await seedFlightMasterdata(request, bearer);
    } finally {
      await ctx.close();
    }
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('[happy] a new flight saved with only date + aircraft + pilot PERSISTS (#229 regression)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const createdId = await createMinimalGliderFlight(page, masterdata);
      expect(createdId).toMatch(/^fl-[0-9a-f-]{36}$/);
      await expect(page).toHaveURL(/\/flights$/);

      const bearer = await bearerFromFlightsList(page);
      const detail = await ctx.request.get(`/api/v1/flights/${createdId}`, {
        headers: { authorization: bearer },
      });
      expect(detail.status(), 'the minimally-created flight persisted + is readable').toBe(200);
      const body = (await detail.json()) as { aircraftId: string; flightAircraftType: string };
      expect(body.aircraftId, 'the persisted flight carries the selected glider').toBe(
        masterdata.gliderAircraftId,
      );
      expect(body.flightAircraftType).toBe('GLIDER');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2b',
        caption:
          'J-2b · #229 · a new flight saved with only date + glider aircraft + pilot persists to the ' +
          'real backend (legacy parity: an incomplete flight saves as Invalid — Save was never blocked; ' +
          'the bug was the invalid-on-load display, fixed below)',
        acTag: 'happy',
      });
    }
  });

  test('[edge] an off-today saved flight is surfaced by the post-save "View it →" jump (Option B)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const future = isoDaysAhead(7);
      const futureId = await createMinimalGliderFlight(page, masterdata, future);
      expect(futureId).toMatch(/^fl-[0-9a-f-]{36}$/);
      await expect(page).toHaveURL(/\/flights$/);

      await expect(page.getByTestId('flights-offrange-banner')).toBeVisible();
      const view = page.getByTestId('flight-offrange-view');
      await expect(view).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-hardening-list.png`,
        fullPage: true,
      });

      await view.click();
      await expect(
        page.getByTestId(`flights-row-${futureId}`),
        'the post-save jump widens the range so the off-today flight appears',
      ).toBeVisible();
      await expect(page.getByTestId('flights-offrange-banner')).toHaveCount(0);

      expect(
        existsSync(`${testInfo.outputDir}/alpenflight-flights-hardening-list.png`),
        'expected the J-2b AlpenFlight list shot in the output dir — the gallery list pairing needs it',
      ).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2b',
        caption:
          'J-2b · off-today visibility · the /flights list defaults to today..today (legacy parity); a ' +
          'flight saved a week ahead is surfaced by the post-save "View it →" jump that widens the range ' +
          'to reveal it — it is not silently hidden (#229(a), Option B)',
        acTag: 'edge',
      });
    }
  });

  test('[key-error] reopening a fully-populated saved flight shows every required field VALID', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const fullId = await createFullyPopulatedGliderFlight(page, masterdata);
      expect(fullId).toMatch(/^fl-[0-9a-f-]{36}$/);

      await page.goto(`/flights/${fullId}/edit`);
      await expect(page.getByTestId('flight-form')).toBeVisible();
      await expect(saveButton(page)).toBeEnabled();

      await expect(fieldErrors(page, 'flight-edit-flightDate')).toHaveCount(0);
      await expect(fieldErrors(page, 'flight-edit-startType')).toHaveCount(0);
      await expect(fieldErrors(page, 'flight-edit-startLocation')).toHaveCount(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-hardening-form.png`,
        fullPage: true,
      });

      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-step-glider')).toBeVisible();
      await expect(fieldErrors(page, 'flight-edit-glider-aircraft')).toHaveCount(0);
      await expect(fieldErrors(page, 'flight-edit-glider-flightType')).toHaveCount(0);
      await expect(fieldErrors(page, 'flight-edit-glider-pilot')).toHaveCount(0);
      await expect(fieldErrors(page, 'flight-edit-glider-nrOfLdgs')).toHaveCount(0);

      expect(
        existsSync(`${testInfo.outputDir}/alpenflight-flights-hardening-form.png`),
        'expected the J-2b AlpenFlight form shot in the output dir — the gallery form pairing needs it',
      ).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2b',
        caption:
          'J-2b · valid-on-load · reopening a fully-populated saved flight renders every populated ' +
          'required field VALID — no stale "Entry required." (the #229(b) root fix: the edit form had ' +
          'ZERO client validators; they are now wired + revalidate against the loaded values)',
        acTag: 'key-error',
      });
    }
  });

  test('[key-error] as-you-type: clearing a GATE field gates Save; clearing a non-gate field does NOT', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const fullId = await createFullyPopulatedGliderFlight(page, masterdata);
      await page.goto(`/flights/${fullId}/edit`);
      await expect(page.getByTestId('flight-form')).toBeVisible();
      await expect(saveButton(page)).toBeEnabled();

      const flightDate = page.getByTestId('flight-edit-flightDate').locator('input');
      await flightDate.fill('');
      await expect(fieldErrors(page, 'flight-edit-flightDate')).toBeVisible();
      await expect(saveButton(page)).toBeDisabled();

      await flightDate.fill(isoToday());
      await expect(fieldErrors(page, 'flight-edit-flightDate')).toHaveCount(0);
      await expect(saveButton(page)).toBeEnabled();

      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-step-glider')).toBeVisible();
      await page.getByTestId('flight-edit-glider-nrOfLdgs').locator('input').fill('');
      await expect(fieldErrors(page, 'flight-edit-glider-nrOfLdgs')).toBeVisible();
      await expect(
        saveButton(page),
        'clearing a NON-gate required field shows an inline error but does NOT block Save (legacy ' +
          'incomplete-save parity)',
      ).toBeEnabled();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2b',
        caption:
          'J-2b · as-you-type gating · clearing a GATE field (date/aircraft/pilot) shows an inline error ' +
          'AND disables Save; clearing a NON-gate required field (landings) shows the inline error but ' +
          'leaves Save ENABLED — legacy incomplete-save parity (the rest of the minimal-valid set marks ' +
          'the flight Invalid but never blocks Save)',
        acTag: 'key-error',
      });
    }
  });
});
