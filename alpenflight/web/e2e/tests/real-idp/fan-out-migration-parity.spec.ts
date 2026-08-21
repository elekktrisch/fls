import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import {
  loginAsMigratedAdmin,
  seedFanOutParity,
  ensureSharedMigrationBundle,
  type FanOutParityFixture,
} from './_helpers/fan-out-parity-fixture';
import { proofVideo } from './_helpers/proof-video';

const RENAMED_SUFFIX = ' (renamed by club A)';

const REAL_BUNDLE = (process.env['J0C_BUNDLE_SOURCE'] ?? 'synth').toLowerCase() === 'real';

const SINGLE_USE_REAL_BUNDLE_CANNOT_BE_RE_INGESTED_ON_RETRY: { retries?: number } = REAL_BUNDLE
  ? { retries: 0 }
  : {};

const LIVE_MIGRATION_SEED_TIMEOUT_MS = 120_000;

const TWO_REAL_KEYCLOAK_LOGINS_PLUS_AN_EDIT_NEED_MORE_THAN_THE_PROJECT_BUDGET_MS = 180_000;

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function locationIdByName(page: Page, name: string): Promise<string> {
  await page.goto('/locations');
  await expect(page.getByTestId('locations-table')).toBeVisible();
  const row = page.locator('[data-testid^="location-row-"]').filter({ hasText: name });
  await expect(row, `migrated Location "${name}" must appear in this club's list`).toBeVisible();
  const testId = await row.getAttribute('data-testid');
  expect(testId, 'migrated Location row must carry a location-row-<id> testid').toBeTruthy();
  const id = testId!.replace(/^location-row-/, '');
  expect(id, `derived migrated location id must be loc-<uuid> form, got "${id}"`).toMatch(
    /^loc-[0-9a-f-]{36}$/,
  );
  return id;
}

async function bearerFromLocationsList(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      new URL(req.url()).pathname === '/api/v1/locations' &&
      typeof req.headers()['authorization'] === 'string',
  );
  await page.goto('/locations');
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

test.describe('Fan-out migration parity — migrated Location, two clubs (real-idp)', () => {
  test.describe.configure({
    mode: 'serial',
    ...SINGLE_USE_REAL_BUNDLE_CANNOT_BE_RE_INGESTED_ON_RETRY,
  });

  let fixture: FanOutParityFixture;
  let baseURL: string;
  let clubBLocationId: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    testInfo.setTimeout(LIVE_MIGRATION_SEED_TIMEOUT_MS);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = REAL_BUNDLE
      ? await ensureSharedMigrationBundle(browser, baseURL)
      : await seedFanOutParity(browser, request, baseURL, testInfo.retry);
  });

  test('club-A admin sees the migrated Location under its random name', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.clubA);
      const id = await locationIdByName(page, fixture.locationName);
      expect(id).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · fan-out migration · club-A admin logs in via real Keycloak and sees its ' +
          'migrated Location under the random name',
        acTag: 'happy',
      });
    }
  });

  test('club-B admin sees its OWN copy of the migrated Location, same name', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.clubB);
      clubBLocationId = await locationIdByName(page, fixture.locationName);
      expect(clubBLocationId).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · fan-out migration · club-B admin sees its OWN copy of the migrated Location ' +
          'under the same name (distinct fanned-out row)',
        acTag: 'happy',
      });
    }
  });

  test('cross-tenant GET of club-B Location as club-A 404s (isolation on migrated data)', async ({
    browser,
  }, testInfo) => {
    expect(clubBLocationId, 'club B id must be known from the earlier assert').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.clubA);
      const bearer = await bearerFromLocationsList(page);
      const res = await ctx.request.get(`/api/v1/locations/${clubBLocationId}`, {
        headers: { authorization: bearer },
      });
      expect(
        res.status(),
        'cross-tenant GET of the other club’s migrated Location must 404 ' +
          '(invisible under tenant scope), not 403',
      ).toBe(404);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · cross-tenant 404 · club-A is denied club-B’s migrated Location ' +
          '(tenant isolation holds on migrated data)',
        acTag: 'key-error',
      });
    }
  });

  test('renaming club-A copy leaves club-B copy unchanged (distinct rows)', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(TWO_REAL_KEYCLOAK_LOGINS_PLUS_AN_EDIT_NEED_MORE_THAN_THE_PROJECT_BUDGET_MS);
    expect(clubBLocationId, 'club B must have located its copy first').toBeTruthy();
    const renamed = fixture.locationName + RENAMED_SUFFIX;
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.clubA);
      const clubAId = await locationIdByName(page, fixture.locationName);
      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `/api/v1/locations/${clubAId}` &&
          r.status() === 200,
      );
      await page.goto(`/locations/${clubAId}/edit`);
      await expect(page.getByTestId('locations-edit-form')).toBeVisible();
      await page.locator('#LocationName').fill(renamed);
      await page.getByTestId('locations-save-button').click();
      await updated;
      await expect(page).toHaveURL('/locations');
      await expect(
        page.locator(`[data-testid="location-row-${clubAId}"]`).filter({ hasText: renamed }),
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · edit isolation · renaming club-A’s migrated copy does not touch ' +
          'club-B’s copy (the two are DISTINCT fanned-out rows)',
        acTag: 'happy',
      });
    }

    const ctxB = await newRecordedContext(browser, baseURL, testInfo);
    const pageB = await ctxB.newPage();
    try {
      await loginAsMigratedAdmin(pageB, fixture.clubB);
      await pageB.goto('/locations');
      await expect(pageB.getByTestId('locations-table')).toBeVisible();
      const stillThere = pageB
        .locator(`[data-testid="location-row-${clubBLocationId}"]`)
        .filter({ hasText: fixture.locationName });
      await expect(
        stillThere,
        'club B copy must still carry the ORIGINAL name (rename did not leak across the fan-out)',
      ).toBeVisible();
      await expect(
        pageB.locator('[data-testid^="location-row-"]').filter({ hasText: RENAMED_SUFFIX }),
      ).toHaveCount(0);
    } finally {
      await ctxB.close();
    }
  });
});
