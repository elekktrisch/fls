import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
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

const ICAO = 'LSZH';
const CH_COUNTRY_LABEL = 'Switzerland';

const LOCATIONS_SPEC_ADMIN_USERNAME_TAG = 'loc';

async function selectSwitzerland(page: Page): Promise<void> {
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.keyboard.type(CH_COUNTRY_LABEL);
  await page
    .locator('nz-option-item')
    .filter({ hasText: new RegExp(`^${CH_COUNTRY_LABEL}$`) })
    .click();
}

async function selectAnyLocationType(page: Page): Promise<void> {
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').first().click();
}

async function createLocationViaUi(
  page: Page,
  opts: { name: string; icao: string },
): Promise<void> {
  await page.goto('/locations');
  await page.getByRole('button', { name: 'New location' }).click();
  await expect(page).toHaveURL('/locations/new');

  await page.locator('#LocationName').fill(opts.name);
  await page.locator('#IcaoCode').fill(opts.icao);
  await selectSwitzerland(page);
  await selectAnyLocationType(page);

  const createCompletedBodyEvictedBySpaNav = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/locations' &&
      r.status() === 201,
  );
  await page.getByTestId('locations-save-button').click();
  await createCompletedBodyEvictedBySpaNav;
  await expect(page).toHaveURL('/locations');
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

test.describe('Locations — two-club tenant isolation (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  let clubALocationId: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL, LOCATIONS_SPEC_ADMIN_USERNAME_TAG);
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('club A admin creates a Location and sees it in club A list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const createCompletedBodyEvictedBySpaNav = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/locations' &&
          r.status() === 201,
      );
      await page.goto('/locations');
      await page.getByRole('button', { name: 'New location' }).click();
      await page.locator('#LocationName').fill('Zurich (Club A)');
      await page.locator('#IcaoCode').fill(ICAO);
      await selectSwitzerland(page);
      await selectAnyLocationType(page);
      await page.getByTestId('locations-save-button').click();
      await createCompletedBodyEvictedBySpaNav;

      await expect(page).toHaveURL('/locations');
      await expect(page.getByTestId('locations-table')).toBeVisible();
      const clubARow = page
        .locator('[data-testid^="location-row-"]')
        .filter({ hasText: 'Zurich (Club A)' });
      await expect(clubARow).toBeVisible();

      const rowTestId = await clubARow.getAttribute('data-testid');
      expect(rowTestId, 'club A row must carry a location-row-<id> testid').toBeTruthy();
      clubALocationId = rowTestId!.replace(/^location-row-/, '');
      expect(
        clubALocationId,
        `derived club A location id must be loc-<uuid> form, got "${clubALocationId}"`,
      ).toMatch(/^loc-[0-9a-f-]{36}$/);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0',
        caption:
          "J-0 · tenant isolation · club A admin creates a Location and sees it in club A's list",
        acTag: 'happy',
      });
    }
  });

  test('club B admin does NOT see club A Location; cross-tenant GET 404s', async ({
    browser,
  }, testInfo) => {
    expect(clubALocationId, 'club A must have created its Location first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);

      await page.goto('/locations');
      await expect(page.getByTestId('locations-table')).toBeVisible();
      await expect(page.locator(`[data-testid="location-row-${clubALocationId}"]`)).toHaveCount(0);
      await expect(
        page.locator('[data-testid^="location-row-"]').filter({ hasText: 'Zurich (Club A)' }),
      ).toHaveCount(0);

      const bearer = await bearerFromLocationsList(page);
      const res = await ctx.request.get(`/api/v1/locations/${clubALocationId}`, {
        headers: { authorization: bearer },
      });
      expect(
        res.status(),
        'cross-tenant GET-by-id must 404 (row invisible under tenant scope), not 403',
      ).toBe(404);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0',
        caption: "J-0 · cross-tenant 404 · club B is denied club A's Location (404 not 403)",
        acTag: 'key-error',
      });
    }
  });

  test('same ICAO (LSZH) is creatable in club B — per-club, not global uniqueness', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);

      await createLocationViaUi(page, { name: 'Zurich (Club B)', icao: ICAO });

      await expect(page.getByTestId('locations-save-error')).toBeHidden();
      await expect(
        page.locator('[data-testid^="location-row-"]').filter({ hasText: 'Zurich (Club B)' }),
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0',
        caption: 'J-0 · per-club ICAO · the same ICAO is creatable independently in club B',
        acTag: 'edge',
      });
    }
  });
});
