import { existsSync } from 'node:fs';

import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type ClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import {
  provisionSeedClubPilot,
  loginAsSeedClubPilot,
  type SeedClubPilot,
} from './_helpers/planning-parity-fixture';
import {
  seedReportingFixture,
  seedClubBFlight,
  type ReportingSeed,
} from './_helpers/reporting-parity-fixture';
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

async function captureAdminBearer(
  browser: Browser,
  baseURL: string,
  admin: ClubAdmin,
): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerPromise = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await loginAsClubAdmin(page, admin);
    await page.goto('/flights');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

const SUMMARY_CELL = { group: 0, starts: 1, landings: 2, flights: 3, duration: 4 } as const;

const SEEDED_FLIGHT_TYPE_GROUPS_PLUS_TOTAL_ROW = 3;

const REPORTS_SPEC_ADMIN_USERNAME_TAG = 'rep';

async function summaryRowCells(page: Page, group: string): Promise<string[]> {
  const escaped = group.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const row = page
    .getByTestId('report-summary-row')
    .filter({ has: page.locator('td', { hasText: new RegExp(`^${escaped}$`) }) })
    .first();
  await expect(row).toBeVisible();
  return (await row.locator('td').allInnerTexts()).map((t) => t.trim());
}

function customApplyUrl(category: 'person' | 'location', filter: Record<string, unknown>): string {
  return `/flightreports/custom/${category}/${encodeURIComponent(JSON.stringify(filter))}/apply`;
}

function ddmmyyyy(d: Date): string {
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}.${mm}.${d.getFullYear()}`;
}

function assertGalleryShotLanded(testInfo: TestInfo, file: string): void {
  expect(
    existsSync(`${testInfo.outputDir}/${file}`),
    `expected AlpenFlight parity screenshot ${file} in the test output dir — ` +
      "the J-7 proof-gallery's AlpenFlight half depends on it",
  ).toBeTruthy();
}

test.describe('J-7 flight reports — real chain parity', () => {
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let pilot: SeedClubPilot;
  let baseURL: string;
  let seed: ReportingSeed;
  let clubBLocationId: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL, REPORTS_SPEC_ADMIN_USERNAME_TAG);
    pilot = await provisionSeedClubPilot();

    const adminBearer = await captureAdminBearer(browser, baseURL, fixture.clubA);
    seed = await seedReportingFixture(request as APIRequestContext, adminBearer);
    const clubBBearer = await captureAdminBearer(browser, baseURL, fixture.clubB);
    ({ locationId: clubBLocationId } = await seedClubBFlight(
      request as APIRequestContext,
      clubBBearer,
    ));
  });

  test.afterAll(async () => {
    await pilot?.dispose();
    await fixture?.dispose();
  });

  test('[happy] real club-admin reaches the reporting picker (J-7 proof anchor)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flights');
      await page.getByTestId('af-nav-section-/flightreports').click();
      await expect(page).toHaveURL(/\/flightreports/);
      await expect(page.getByTestId('flightreports-category-person')).toBeVisible();
      await expect(page.getByTestId('flightreports-category-location')).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flightreports-picker.png`,
        fullPage: true,
      });
      assertGalleryShotLanded(testInfo, 'alpenflight-flightreports-picker.png');
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A real club administrator authenticates through live Keycloak and reaches the /flightreports picker (person + location report categories).',
        acTag: 'happy',
      });
    }
  });

  test('[happy] canned location report (this-year): derived window + FlightTypeName grouping + nested tow', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flightreports/location/location-flights-this-year');

      const now = new Date();
      const base = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      const expectedRange = `${ddmmyyyy(new Date(base.getFullYear(), 0, 1))} – ${ddmmyyyy(base)}`;
      await expect(page.getByTestId('report-filter-range')).toHaveText(expectedRange);

      await expect(page.getByTestId('report-summary-table')).toBeVisible();
      const summaryRows = page.getByTestId('report-summary-row');
      await expect(summaryRows.filter({ hasText: 'Total' })).toBeVisible();
      expect(await summaryRows.count()).toBeGreaterThanOrEqual(
        SEEDED_FLIGHT_TYPE_GROUPS_PLUS_TOTAL_ROW,
      );
      await expect(summaryRows.filter({ hasText: /Pilot \(Glider\)/ })).toHaveCount(0);

      await expect(page.getByTestId('report-flights-table')).toBeVisible();
      await expect(page.getByTestId('report-flights-row').first()).toBeVisible();
      const towRow = page
        .getByTestId('report-flights-tow-row')
        .filter({ hasText: seed.masterdata.towImmat });
      await expect(towRow).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flightreports-result.png`,
        fullPage: true,
      });
      assertGalleryShotLanded(testInfo, 'alpenflight-flightreports-result.png');
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A canned location report (this-year) renders the derived date window, groups the summary by flight-type, and shows the aerotow glider with its nested tow block — live, tenant-scoped.',
        acTag: 'happy',
      });
    }
  });

  test('[happy] person report: crew-function summary with corrected non-zero TotalFlights (Motor/Towing)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const yearStart = `${new Date().getFullYear()}-01-01`;
      const todayIso = new Date().toISOString().slice(0, 10);
      await page.goto(
        customApplyUrl('person', {
          flightDateFrom: yearStart,
          flightDateTo: todayIso,
          flightCrewPersonId: seed.pilotPersonId,
          gliderFlights: true,
          motorFlights: true,
          towFlights: true,
        }),
      );

      await expect(page.getByTestId('report-summary-table')).toBeVisible();
      for (const group of ['Pilot (Glider)', 'Pilot (Motor)', 'Pilot (Towing)', 'Total']) {
        await expect(
          page.getByTestId('report-summary-row').filter({
            has: page.locator('td', {
              hasText: new RegExp(`^${group.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
            }),
          }),
        ).toHaveCount(1);
      }
      const motorCells = await summaryRowCells(page, 'Pilot (Motor)');
      expect(
        Number(motorCells[SUMMARY_CELL.flights]),
        'Pilot (Motor) TotalFlights must be > 0 (corrected)',
      ).toBeGreaterThan(0);
      const towCells = await summaryRowCells(page, 'Pilot (Towing)');
      expect(
        Number(towCells[SUMMARY_CELL.flights]),
        'Pilot (Towing) TotalFlights must be > 0 (corrected)',
      ).toBeGreaterThan(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flightreports-custom.png`,
        fullPage: true,
      });
      assertGalleryShotLanded(testInfo, 'alpenflight-flightreports-custom.png');
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A person report groups by crew function (Pilot Glider/Motor/Towing + Total) with the corrected non-zero TotalFlights on the Pilot (Motor) and Pilot (Towing) rows — the legacy under-count bug, fixed.',
        acTag: 'happy',
      });
    }
  });

  test('[happy] person report: Instructor vs Instructor (Soloflights) split', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const yearStart = `${new Date().getFullYear()}-01-01`;
      const todayIso = new Date().toISOString().slice(0, 10);
      await page.goto(
        customApplyUrl('person', {
          flightDateFrom: yearStart,
          flightDateTo: todayIso,
          flightCrewPersonId: seed.instructorPersonId,
          gliderFlights: true,
          motorFlights: true,
          towFlights: true,
        }),
      );

      await expect(page.getByTestId('report-summary-table')).toBeVisible();
      for (const group of ['Instructor', 'Instructor (Soloflights)']) {
        await expect(
          page.getByTestId('report-summary-row').filter({
            has: page.locator('td', {
              hasText: new RegExp(`^${group.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
            }),
          }),
        ).toHaveCount(1);
      }
      const instrCells = await summaryRowCells(page, 'Instructor');
      expect(
        Number(instrCells[SUMMARY_CELL.flights]),
        'Instructor (non-solo) count > 0',
      ).toBeGreaterThan(0);
      const soloCells = await summaryRowCells(page, 'Instructor (Soloflights)');
      expect(
        Number(soloCells[SUMMARY_CELL.flights]),
        'Instructor (Soloflights) count > 0',
      ).toBeGreaterThan(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reporting-instructor-split.png`,
        fullPage: true,
      });
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A person report on an instructor splits the summary into Instructor and Instructor (Soloflights) rows on the solo/non-solo flag.',
        acTag: 'happy',
      });
    }
  });

  test('[key-error] tenant isolation: a club-A PILOT filtering by a club-B location sees no club-B flights', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsSeedClubPilot(page, pilot);
      const yearStart = `${new Date().getFullYear()}-01-01`;
      const todayIso = new Date().toISOString().slice(0, 10);
      await page.goto(
        customApplyUrl('location', {
          flightDateFrom: yearStart,
          flightDateTo: todayIso,
          locationId: clubBLocationId,
          gliderFlights: true,
          motorFlights: true,
          towFlights: true,
        }),
      );

      await test.step('no club-B flight row leaks and the lone summary row is a zeroed Total — a location report always appends a Total, so the empty state never renders', async () => {
        const summaryTable = page.getByTestId('report-summary-table');
        await expect(summaryTable).toBeVisible();
        await expect(page.getByTestId('report-flights-row')).toHaveCount(0);
        const summaryRows = page.getByTestId('report-summary-row');
        await expect(summaryRows).toHaveCount(1);
        const zeroedTotalRow = summaryRows.first();
        await expect(zeroedTotalRow).toContainText('Total');
        await expect(zeroedTotalRow.locator('td').nth(SUMMARY_CELL.starts)).toHaveText('0');
        await expect(zeroedTotalRow.locator('td').nth(SUMMARY_CELL.landings)).toHaveText('0');
        await expect(zeroedTotalRow.locator('td').nth(SUMMARY_CELL.flights)).toHaveText('0');
        await expect(page.getByTestId('report-empty')).toHaveCount(0);
      });

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reporting-tenant-isolation.png`,
        fullPage: true,
      });
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A real low-privilege PILOT in club A filtering a report by a club-B location sees an empty result — every report query is @TenantId scoped (ADR 0008), closing the legacy cross-club leak.',
        acTag: 'key-error',
      });
    }
  });

  test('[happy] Excel export streams an .xlsx attachment (cell parity is the T-08 harness)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flightreports/location/location-flights-this-year');
      await expect(page.getByTestId('report-flights-table')).toBeVisible();

      const exportResponsePromise = page.waitForResponse(
        (r) =>
          /\/api\/v1\/flightreports\/export\/excel\//.test(r.url()) &&
          r.request().method() === 'POST',
      );
      const downloadPromise = page.waitForEvent('download');
      await page.getByTestId('report-excel-export').click();

      const exportResponse = await exportResponsePromise;
      expect(exportResponse.status()).toBe(200);
      expect(
        exportResponse.headers()['content-type'] ?? '',
        'the export uses the corrected OOXML spreadsheet MIME (oracle § MIME type)',
      ).toContain('spreadsheetml.sheet');

      const download = await downloadPromise;
      expect(download.suggestedFilename()).toMatch(/\.xlsx$/);
      const stream = await download.createReadStream();
      const chunks: Buffer[] = [];
      for await (const chunk of stream) chunks.push(chunk as Buffer);
      const head = Buffer.concat(chunks).subarray(0, 2).toString('latin1');
      expect(head, 'an .xlsx is a ZIP container starting with the PK magic').toBe('PK');

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reporting-export.png`,
        fullPage: true,
      });
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'The flight-reports Excel export streams a real .xlsx (OOXML spreadsheet MIME, PK container); cell-for-cell parity against the legacy fixture is proven by the T-08 backend parity harness.',
        acTag: 'happy',
      });
    }
  });
});
