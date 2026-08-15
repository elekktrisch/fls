import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import {
  loginAsMigratedAdmin,
  seedAircraftOwnerLink,
  seedAircraftParity,
  type AircraftParityFixture,
} from './_helpers/aircraft-parity-fixture';
import { proofVideo } from './_helpers/proof-video';

const LANG_DE_UUID = '019e2e15-2c00-77d0-8000-0000000007d0';

const SPEC_TOKEN_KEEPING_ADMIN_USERNAMES_DISJOINT = 'acft';

const GRADLE_OWNER_LINK_SEEDER_TIMEOUT_MS = 90_000;

const SINGLE_USE_BUNDLE_FORBIDS_RETRY = 0;

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function selectGliderType(page: Page): Promise<void> {
  await page.getByTestId('aircraft-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Glider' }).first().click();
}

async function createAircraftViaUi(
  page: Page,
  immatriculation: string,
  details: { manufacturer: string; model?: string; seats?: number; competitionSign?: string } = {
    manufacturer: 'Schleicher',
  },
): Promise<string> {
  await page.goto('/aircraft');
  await page.getByTestId('aircraft-new-button').locator('button').click();
  await expect(page).toHaveURL('/aircraft/new');

  await page.locator('#Immatriculation').fill(immatriculation);
  await selectGliderType(page);
  await page.locator('#ManufacturerName').fill(details.manufacturer);
  if (details.competitionSign !== undefined) {
    await page.locator('#CompetitionSign').fill(details.competitionSign);
  }
  if (details.model !== undefined) {
    await page.locator('#AircraftModel').fill(details.model);
  }
  if (details.seats !== undefined) {
    await page.locator('#NrOfSeats').fill(String(details.seats));
  }

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/aircraft' &&
      r.status() === 201,
  );
  await page.getByTestId('aircraft-save-button').locator('button').click();
  await created;
  await expect(page).toHaveURL('/aircraft');

  const row = page.locator('[data-testid^="aircraft-row-"]').filter({ hasText: immatriculation });
  await expect(row, `created aircraft "${immatriculation}" must appear in the list`).toBeVisible();
  const testId = await row.getAttribute('data-testid');
  expect(testId, 'aircraft row must carry an aircraft-row-<id> testid').toBeTruthy();
  const id = testId!.replace(/^aircraft-row-/, '');
  expect(id, `derived aircraft id must be ac-<uuid> form, got "${id}"`).toMatch(
    /^ac-[0-9a-f-]{36}$/,
  );
  return id;
}

async function bearerFromAircraftList(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      new URL(req.url()).pathname === '/api/v1/aircraft' &&
      typeof req.headers()['authorization'] === 'string',
  );
  await page.goto('/aircraft');
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

test.describe('Aircraft register — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  let aircraftId: string;
  let aircraftImmat: string;
  const createdAircraftIds: string[] = [];

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdAircraftIds.length = 0;
    fixture = await provisionTwoClubs(
      browser,
      baseURL,
      SPEC_TOKEN_KEEPING_ADMIN_USERNAMES_DISJOINT,
    );
  });

  test.afterAll(async ({ browser }) => {
    if (fixture && createdAircraftIds.length > 0) {
      const ctx = await browser.newContext({ baseURL });
      const page = await ctx.newPage();
      try {
        await loginAsClubAdmin(page, fixture.clubA);
        const bearer = await bearerFromAircraftList(page);
        for (const id of createdAircraftIds) {
          try {
            await ctx.request.delete(`/api/v1/aircraft/${id}`, {
              headers: { authorization: bearer },
            });
          } catch (err) {
            console.warn(`[J-1] afterAll cleanup: delete ${id} failed (${(err as Error).message})`);
          }
        }
      } finally {
        await ctx.close();
      }
    }
    await fixture?.dispose();
  });

  test('club admin lists, creates aircraft, and sees every created row in the list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto('/aircraft');
      await expect(page.locator('h1')).toHaveText('Aircraft');
      await expect(page.getByTestId('aircraft-table')).toBeVisible();

      const baselineRowCountInSharedSeedTenant = await page
        .locator('[data-testid^="aircraft-row-"]')
        .count();

      aircraftImmat = `HB-J1${Date.now().toString(36).slice(-3).toUpperCase()}`;
      aircraftId = await createAircraftViaUi(page, aircraftImmat, {
        manufacturer: 'Schleicher',
        model: 'ASK 21',
        seats: 2,
        competitionSign: 'FG',
      });
      expect(aircraftId).toBeTruthy();
      createdAircraftIds.push(aircraftId);

      const row = page.locator(`[data-testid="aircraft-row-${aircraftId}"]`);
      await expect(row).toContainText(aircraftImmat);
      await expect(page.getByTestId(`aircraft-model-${aircraftId}`)).toContainText('Schleicher');
      await expect(page.getByTestId(`aircraft-model-${aircraftId}`)).toContainText('ASK 21');
      await expect(page.getByTestId(`aircraft-seats-${aircraftId}`)).toContainText('2 seats');

      const extraRowsForThePopulatedListScreenshot = [
        { manufacturer: 'Schempp-Hirth', model: 'Discus b', seats: 1, competitionSign: 'GZ' },
        { manufacturer: 'DG Flugzeugbau', model: 'DG-1000', seats: 2, competitionSign: 'ZO' },
      ];
      for (const extra of extraRowsForThePopulatedListScreenshot) {
        const immat = `HB-J1${Date.now().toString(36).slice(-3).toUpperCase()}`;
        const extraId = await createAircraftViaUi(page, immat, extra);
        createdAircraftIds.push(extraId);
        await expect(
          page.locator(`[data-testid="aircraft-row-${extraId}"]`),
          `extra aircraft "${immat}" must appear in the list`,
        ).toBeVisible();
      }

      await expect(page).toHaveURL('/aircraft');
      await expect(page.getByTestId('aircraft-table')).toBeVisible();
      await expect(page.locator('[data-testid^="aircraft-row-"]')).toHaveCount(
        baselineRowCountInSharedSeedTenant + 3,
      );
      for (const id of createdAircraftIds) {
        await expect(
          page.locator(`[data-testid="aircraft-row-${id}"]`),
          `created aircraft ${id} must be present for the parity screenshot`,
        ).toBeVisible();
      }
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-aircraft-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          'J-1 · aircraft register · club admin logs in via real Keycloak, lists the fleet, and ' +
          'creates an aircraft that appears in the list (immatriculation + type)',
        acTag: 'happy',
      });
    }
  });

  test('club admin edits the aircraft; the change persists and re-renders', async ({
    browser,
  }, testInfo) => {
    expect(aircraftId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `/api/v1/aircraft/${aircraftId}` &&
          r.status() === 200,
      );
      await page.goto(`/aircraft/${aircraftId}/edit`);
      await expect(page.getByTestId('aircraft-edit-form')).toBeVisible();
      await expect(page.locator('#Immatriculation')).toHaveValue(aircraftImmat);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-aircraft-form.png`,
        fullPage: true,
      });

      await page.locator('#Comment').fill('edited by J-1 e2e');
      await page.getByTestId('aircraft-save-button').locator('button').click();
      await updated;
      await expect(page).toHaveURL('/aircraft');

      await page.goto(`/aircraft/${aircraftId}/edit`);
      await expect(page.locator('#Comment')).toHaveValue('edited by J-1 e2e');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          'J-1 · aircraft register · the managing-club admin edits the aircraft and the change ' +
          'persists across a reload (real backend round-trip)',
        acTag: 'happy',
      });
    }
  });

  test('cross-club caller is denied edit + delete on the aircraft (403)', async ({
    browser,
  }, testInfo) => {
    expect(aircraftId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);
      const bearer = await bearerFromAircraftList(page);

      const read = await ctx.request.get(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer },
      });
      expect(read.status(), 'aircraft is cross-tenant readable').toBe(200);

      const put = await ctx.request.put(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer, 'content-type': 'application/json' },
        data: {
          aircraftTypeId: '019e2e15-2c00-7af9-8000-000000002af9',
          immatriculation: `HB-X${Date.now().toString(36).slice(-3).toUpperCase()}`,
          manufacturerName: 'Schleicher',
          aircraftModel: 'ASK-21',
          nrOfSeats: 2,
          isTowingOrWinchRequired: false,
          isTowingStartAllowed: false,
          isWinchStartAllowed: false,
          isTowingAircraft: false,
        },
      });
      expect(put.status(), 'cross-club edit must be 403 (managing-club gate), not 404').toBe(403);

      const del = await ctx.request.delete(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer },
      });
      expect(del.status(), 'cross-club delete must be 403').toBe(403);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          "J-1 · edit isolation · a caller whose club ≠ the aircraft's managing club is denied " +
          'edit and delete (403, managing-club gate)',
        acTag: 'key-error',
      });
    }
  });

  test('S-164 · latestCounter present for the manager, redacted for a non-manager', async ({
    browser,
  }, testInfo) => {
    expect(aircraftId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const managerBearer = await bearerFromAircraftList(page);
      const counter = await ctx.request.post(`/api/v1/aircraft/${aircraftId}/counters`, {
        headers: { authorization: managerBearer, 'content-type': 'application/json' },
        data: { atDateTime: '2026-01-01T10:00:00Z', flightOperatingCounterInSeconds: 3600 },
      });
      expect(counter.status(), 'manager may record a counter').toBe(201);

      const managerRead = await ctx.request.get(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: managerBearer },
      });
      expect(managerRead.status()).toBe(200);
      const managerBody = (await managerRead.json()) as { latestCounter: unknown };
      expect(
        managerBody.latestCounter,
        'latestCounter is present for the managing-club caller',
      ).toBeTruthy();

      await page.goto(`/aircraft/${aircraftId}/edit`);
      await expect(page.getByTestId('aircraft-latest-counter')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          "J-1 · S-164 · the aircraft's latestCounter is surfaced to the managing-club admin on " +
          'the detail view (real backend, real counter)',
        acTag: 'edge',
      });
    }

    const ctxB = await newRecordedContext(browser, baseURL, testInfo);
    const pageB = await ctxB.newPage();
    try {
      await loginAsClubAdmin(pageB, fixture.clubB);
      const foreignBearer = await bearerFromAircraftList(pageB);
      const foreignRead = await ctxB.request.get(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: foreignBearer },
      });
      expect(foreignRead.status(), 'aircraft stays cross-tenant readable (200)').toBe(200);
      const foreignBody = (await foreignRead.json()) as { latestCounter: unknown };
      expect(
        foreignBody.latestCounter === null || foreignBody.latestCounter === undefined,
        'latestCounter is redacted for a non-managing-club caller (S-164)',
      ).toBe(true);
    } finally {
      await ctxB.close();
    }
  });

  test('S-163 · the owner-person of a non-managing club may edit the aircraft', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(GRADLE_OWNER_LINK_SEEDER_TIMEOUT_MS);
    expect(aircraftId, 'create must have run first').toBeTruthy();
    await seedAircraftOwnerLink({
      aircraftId,
      ownerKeycloakSub: fixture.clubB.kcUserId,
      ownerClubId: fixture.clubB.clubId,
      languageId: LANG_DE_UUID,
    });

    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);
      const bearer = await bearerFromAircraftList(page);
      const put = await ctx.request.put(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer, 'content-type': 'application/json' },
        data: {
          aircraftTypeId: '019e2e15-2c00-7af9-8000-000000002af9',
          immatriculation: aircraftImmat,
          manufacturerName: 'Schleicher',
          aircraftModel: 'ASK-21 (by owner)',
          nrOfSeats: 2,
          isTowingOrWinchRequired: false,
          isTowingStartAllowed: false,
          isWinchStartAllowed: false,
          isTowingAircraft: false,
        },
      });
      expect(
        put.status(),
        'the owner-person of a non-managing club is admitted to edit (S-163 OR-branch)',
      ).toBe(200);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          "J-1 · S-163 · a caller whose linked Person == the aircraft's owner-person may edit it " +
          'even from a non-managing club (net-new owner-person admit, fully real)',
        acTag: 'edge',
      });
    }
  });
});

test.describe('Aircraft register — migrated legacy aircraft renders (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: SINGLE_USE_BUNDLE_FORBIDS_RETRY });

  let fixture: AircraftParityFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await seedAircraftParity(browser, request, baseURL, testInfo.retry);
  });

  test('the migrated aircraft renders in the owning club list under its immatriculation', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.owner);
      await page.goto('/aircraft');
      await expect(page.getByTestId('aircraft-table')).toBeVisible();
      const row = page
        .locator('[data-testid^="aircraft-row-"]')
        .filter({ hasText: fixture.immatriculation });
      await expect(
        row,
        `migrated aircraft "${fixture.immatriculation}" must appear in the owning club's list`,
      ).toBeVisible();

      const testId = await row.getAttribute('data-testid');
      expect(testId, 'migrated aircraft row must carry an aircraft-row-<id> testid').toBeTruthy();
      const id = testId!.replace(/^aircraft-row-/, '');
      expect(id, `derived migrated aircraft id must be ac-<uuid> form, got "${id}"`).toMatch(
        /^ac-[0-9a-f-]{36}$/,
      );
      const model = page.getByTestId(`aircraft-model-${id}`);
      await expect(model).toContainText('Schleicher');
      await expect(model).toContainText('ASK 21');
      await expect(page.getByTestId(`aircraft-seats-${id}`)).toContainText('2 seats');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          'J-1 · migrated aircraft · a real legacy Aircraft, migrated through the live migration ' +
          "endpoint, renders in the owning club's /aircraft list under its immatriculation " +
          '(full legacy→migrate→Keycloak→UI chain)',
        acTag: 'happy',
      });
    }
  });
});
