import { existsSync } from 'node:fs';

import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

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

/**
 * J-7 — Flight-reports REAL-CHAIN parity spec (live Keycloak auth + real Spring
 * backend + real Postgres). T-15 flips the T-01 skeleton's fixme cases green with
 * the FULL oracle assertions. This is the gate-side sibling of the mock inner
 * loop (`tests/reporting/*`): it drives `/flightreports` against flights seeded
 * THROUGH THE REAL create APIs (J-7 is read-side over J-2 data — no new mapper;
 * the read-side fixture {@link seedReportingFixture} seeds the oracle's minimum
 * legacy seed via real POSTs) with NO `page.route` mocking, so the @TenantId
 * report filter, the canned date-math, the summary grouping rule, the nested-tow
 * row shape, and the Excel export all run live.
 *
 * Parity contract asserted (J-7-flight-reports.md § Parity decisions, oracle
 * 2026-06-09):
 *   - CANNED DATE MATH — the derived filter-criteria panel shows the correct
 *     this-year window (robust to wall-clock drift; the seed is within the last
 *     few days so the year/30-day windows always contain it).
 *   - SUMMARY grouping — person report → crew-function rows with the CORRECTED
 *     non-zero TotalFlights on Pilot (Motor)/(Towing) (the legacy-bug
 *     correction); location report → group-by-FlightTypeName + Total.
 *   - NESTED TOW — an aerotow glider row carries its nested tow block.
 *   - TENANT ISOLATION (the key-error, non-negotiable correction) — driven by a
 *     REAL low-privilege PILOT (seed-club-1) filtering by a club-B location:
 *     every report query is @TenantId scoped (ADR 0008), so the result is empty,
 *     closing the legacy cross-club leak (FlightReportService.cs:114-125). Driving
 *     a real low-priv principal catches a role-authz gap the mock admin hides
 *     ([[project_real_idp_real_roles_catches_authz_gaps]]).
 *   - EXCEL parity — the export streams an .xlsx; cell-for-cell parity is the
 *     backend T-08 harness's job (this spec asserts the download + MIME, not the
 *     bytes — the harness re-implements no cell-diff in Playwright).
 *
 * Mocked seams: NONE. Happy + key-error run fully real.
 */

/** Per-test recorded context — the `real-idp` project's `video:'on'` only governs
 *  the auto `page`; these tests drive their own context per principal, so pass
 *  `recordVideo` explicitly to land the green run's `.webm` for the J-7 gallery. */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/** Capture the Bearer the OIDC interceptor attaches to a club admin's first
 *  /api/v1/* read — so the seed can POST flights with the real tenant token. */
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

/** Read a visible summary-row's cells `[group, starts, ldgs, flights, duration]`,
 *  matching the group label EXACTLY (first cell) so "Instructor" doesn't also
 *  match "Instructor (Soloflights)". */
async function summaryRowCells(page: Page, group: string): Promise<string[]> {
  const escaped = group.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const row = page
    .getByTestId('report-summary-row')
    .filter({ has: page.locator('td', { hasText: new RegExp(`^${escaped}$`) }) })
    .first();
  await expect(row).toBeVisible();
  return (await row.locator('td').allInnerTexts()).map((t) => t.trim());
}

/** Build the custom-report apply URL: JSON filter → encodeURIComponent → segment
 *  (mirrors the SPA's `encodeCustomFilter`). */
function customApplyUrl(category: 'person' | 'location', filter: Record<string, unknown>): string {
  return `/flightreports/custom/${category}/${encodeURIComponent(JSON.stringify(filter))}/apply`;
}

/** DD.MM.YYYY (the SPA's rendered date convention). */
function ddmmyyyy(d: Date): string {
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}.${mm}.${d.getFullYear()}`;
}

/**
 * SELF-GUARD (mirrors J-2 T-43): the gallery-declared AlpenFlight parity PNG MUST
 * have landed in the test output dir. The ci.yml/fanout `add_pair`/`add_shot`
 * only DECLARES shots it can `find`, so a skipped capture would be silently
 * absent from the gallery instead of red. Each of the three J-7 views (picker /
 * result / custom, expected-shots.json) asserts its own shot here so a missed
 * capture is a loud failure, not a hidden gallery gap.
 */
function assertGalleryShotLanded(testInfo: TestInfo, file: string): void {
  expect(
    existsSync(`${testInfo.outputDir}/${file}`),
    `expected AlpenFlight parity screenshot ${file} in the test output dir — ` +
      "the J-7 proof-gallery's AlpenFlight half depends on it",
  ).toBeTruthy();
}

test.describe('J-7 flight reports — real chain parity', () => {
  // One realm + one backend; provision + seed once, run the asserts in order.
  // Serial keeps the principals (admin / pilot) from racing KC user-exists checks
  // and the seed-then-read ordering (mirrors the J-2 flight spec).
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let pilot: SeedClubPilot;
  let baseURL: string;
  let seed: ReportingSeed;
  /** Club B's start location id — the foreign location the isolation case filters by. */
  let clubBLocationId: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Spec-scoped admin usernames ('rep') so this fixture's club admins are
    // disjoint from the other real-idp specs in one playwright invocation
    // (ux_user_username_lower_alive). Club A = seed-club-1, club B created live.
    fixture = await provisionTwoClubs(browser, baseURL, 'rep');
    // A REAL low-privilege PILOT bound to club A (seed-club-1) — drives the
    // tenant-isolation case so a role-authz gap the mock admin hides surfaces.
    pilot = await provisionSeedClubPilot();

    // Seed club A's flights (the oracle minimum seed) + one club-B flight, both
    // through the REAL create APIs with each tenant's own Bearer (no mocking).
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

  // PROOF-WIRING ANCHOR — runs first; proves the real principal reaches the app.
  test('[happy] real club-admin reaches the reporting picker (J-7 proof anchor)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      // Enter through the nav entry (legacy parity: FLIGHTREPORTS directly
      // after the start-list) — proves the screen is UI-reachable, not just
      // URL-reachable, and keeps the navigation warm (no cold-goto stall).
      await page.goto('/flights');
      await page.getByTestId('af-nav-section-/flightreports').click();
      await expect(page).toHaveURL(/\/flightreports/);
      await expect(page.getByTestId('flightreports-category-person')).toBeVisible();
      await expect(page.getByTestId('flightreports-category-location')).toBeVisible();
      // Gallery-declared AlpenFlight `picker` view (ci.yml/fanout add_pair/add_shot,
      // expected-shots.json J-7 → flips pending→expected once this PNG lands).
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

  // ── CANNED LOCATION REPORT — derived this-year window + group-by-FlightTypeName
  // + nested tow + populated flights table (live, tenant-scoped). The location
  // canned report is whole-club so it populates from the seed without needing the
  // admin to be a crew Person. ────────────────────────────────────────────────
  test('[happy] canned location report (this-year): derived window + FlightTypeName grouping + nested tow', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flightreports/location/location-flights-this-year');

      // (1) CANNED DATE WINDOW — the derived filter-criteria panel shows
      // Jan-1-this-year → today (computed at run time, wall-clock-robust).
      const now = new Date();
      const base = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      const expectedRange = `${ddmmyyyy(new Date(base.getFullYear(), 0, 1))} – ${ddmmyyyy(base)}`;
      await expect(page.getByTestId('report-filter-range')).toHaveText(expectedRange);

      // (2) LOCATION grouping by FlightTypeName: the seed has two flight types,
      // so the summary carries ≥2 type-named rows + a Total — never a crew
      // "Pilot (Glider)" row (that's the person branch).
      await expect(page.getByTestId('report-summary-table')).toBeVisible();
      const summaryRows = page.getByTestId('report-summary-row');
      await expect(summaryRows.filter({ hasText: 'Total' })).toBeVisible();
      // ≥2 non-Total group rows (the two seeded flight types).
      expect(await summaryRows.count()).toBeGreaterThanOrEqual(3);
      await expect(summaryRows.filter({ hasText: /Pilot \(Glider\)/ })).toHaveCount(0);

      // (3) FLIGHTS TABLE populated + NESTED TOW — the aerotow glider row carries
      // a nested tow sub-row. The location report runs at the shared club
      // homebase, so flights from prior runs accumulate (the seed-club-1 tenant is
      // never truncated); target THIS run's tow row by its run-unique
      // immatriculation rather than `.first()` so the assertion can't latch onto a
      // prior run's tow row.
      await expect(page.getByTestId('report-flights-table')).toBeVisible();
      await expect(page.getByTestId('report-flights-row').first()).toBeVisible();
      const towRow = page
        .getByTestId('report-flights-tow-row')
        .filter({ hasText: seed.masterdata.towImmat });
      await expect(towRow).toBeVisible();

      // Gallery-declared AlpenFlight `result` view: a canned report's filter
      // panel + summary + flights table (the parity pairing key vs legacy
      // result.png). Captured AFTER the deep assertions above — a partial red
      // still produces the populated shot the gallery needs.
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

  // ── PERSON REPORT (custom, on the seeded pilot) — crew-function summary with
  // the CORRECTED non-zero TotalFlights on Pilot (Motor)/(Towing). The canned
  // person report binds the principal's own personId (a KC admin, not crew), so
  // the custom builder is the realistic path to a person filter on a person who
  // actually flew — it exercises the same backend person-branch grouping. ──────
  test('[happy] person report: crew-function summary with corrected non-zero TotalFlights (Motor/Towing)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      // Custom person report on the seeded pilot, type flags all on so glider +
      // motor + tow flights all count. Wide date window (this whole year).
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
      // The pilot was PilotOrStudent on a glider, a motor, and a tow flight, so
      // the person branch shows all three pilot rows + Total.
      for (const group of ['Pilot (Glider)', 'Pilot (Motor)', 'Pilot (Towing)', 'Total']) {
        await expect(
          page.getByTestId('report-summary-row').filter({
            has: page.locator('td', {
              hasText: new RegExp(`^${group.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
            }),
          }),
        ).toHaveCount(1);
      }
      // THE LEGACY-BUG CORRECTION: Pilot (Motor) + Pilot (Towing) carry a NON-ZERO
      // Flights count (legacy omitted TotalFlights on these rows → always 0).
      const motorCells = await summaryRowCells(page, 'Pilot (Motor)');
      expect(
        Number(motorCells[3]),
        'Pilot (Motor) TotalFlights must be > 0 (corrected)',
      ).toBeGreaterThan(0);
      const towCells = await summaryRowCells(page, 'Pilot (Towing)');
      expect(
        Number(towCells[3]),
        'Pilot (Towing) TotalFlights must be > 0 (corrected)',
      ).toBeGreaterThan(0);

      // Gallery-declared AlpenFlight `custom` view: a CUSTOM-filter person report
      // (the custom-apply route) showing the crew-function summary — the parity
      // pairing key vs the legacy custom.png builder/result.
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

  // ── INSTRUCTOR SPLIT — a person report on the seeded instructor shows the
  // Instructor vs Instructor (Soloflights) split (on IsSoloFlight). ────────────
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
      // The instructor flew a non-solo + a solo flight → both rows present.
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
      expect(Number(instrCells[3]), 'Instructor (non-solo) count > 0').toBeGreaterThan(0);
      const soloCells = await summaryRowCells(page, 'Instructor (Soloflights)');
      expect(Number(soloCells[3]), 'Instructor (Soloflights) count > 0').toBeGreaterThan(0);

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

  // ── TENANT ISOLATION (key-error) — a REAL low-privilege PILOT (club A)
  // filtering by a CLUB-B location sees NO club-B flights. The report query is
  // @TenantId scoped (ADR 0008, structural), so the cross-club location yields NO
  // matching flights — closing the legacy tenancy hole. A LOCATION report with
  // zero matching flights is NOT the empty-state: legacy parity
  // (FlightReportService.cs:715-727 → locationBranch always appends a zeroed
  // "Total") returns one zeroed summary row, so the page shows the summary table
  // (not report-empty). The isolation proof is therefore: zero leaked flight rows
  // + a zeroed Total (asserted below). Driving a low-priv PILOT (not the
  // admin-everything mock principal) also proves the report read endpoint's role
  // gate admits a PILOT without leaking across tenants. ────────────────────────
  test('[key-error] tenant isolation: a club-A PILOT filtering by a club-B location sees no club-B flights', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsSeedClubPilot(page, pilot);
      // Custom location report targeting CLUB B's location id. @TenantId scope
      // must yield NO matching flights for the club-A PILOT — never a leak.
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

      // ISOLATION ASSERTION — a LOCATION report is NOT an empty-state when it has
      // zero matching flights: the legacy `FlightReportService.cs:715-727`
      // (mirrored by `FlightReportQueryService.locationBranch` → `totalRow` always
      // appended) returns a SINGLE zeroed "Total" summary row even with no flights,
      // so `report.store.ts` `isEmpty` (items===0 && summaries===0) is FALSE and the
      // page renders the summary table with one zeroed Total — never `report-empty`.
      // The security property we prove is the absence of a leak, not the empty-state:
      //   1. NO club-B flight row appears (the flights table has 0 data rows), and
      //   2. the lone summary row is the zeroed Total (no club-B starts/ldgs/flights).
      // This still FAILS loud if a club-B flight leaked into the club-A PILOT's
      // @TenantId-scoped report (a leaked row → report-flights-row count > 0, and a
      // non-zero Total flights cell).
      const summaryTable = page.getByTestId('report-summary-table');
      await expect(summaryTable).toBeVisible();
      // No leaked flights — the flights table renders with zero data rows.
      await expect(page.getByTestId('report-flights-row')).toHaveCount(0);
      // The only summary row is the zeroed Total (location-report parity).
      const summaryRows = page.getByTestId('report-summary-row');
      await expect(summaryRows).toHaveCount(1);
      const total = summaryRows.first();
      await expect(total).toContainText('Total');
      // Total flights/starts/landings are all 0 — no club-B data summed in.
      // (cells: Group · Starts · Landings · Flights · Duration → cols 1..3 are 0).
      await expect(total.locator('td').nth(1)).toHaveText('0'); // starts
      await expect(total.locator('td').nth(2)).toHaveText('0'); // landings
      await expect(total.locator('td').nth(3)).toHaveText('0'); // flights
      // And the empty-state is NOT shown (this branch is mutually exclusive with it).
      await expect(page.getByTestId('report-empty')).toHaveCount(0);

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

  // ── EXCEL EXPORT — the export streams an .xlsx attachment with the spreadsheet
  // MIME. Cell-for-cell parity is the backend T-08 harness's job (this spec does
  // NOT re-implement cell-diff in Playwright); here we assert the live download
  // happens + the filename + a content-type sanity check off the captured
  // response (the harness proves the bytes). ──────────────────────────────────
  test('[happy] Excel export streams an .xlsx attachment (cell parity is the T-08 harness)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flightreports/location/location-flights-this-year');
      await expect(page.getByTestId('report-flights-table')).toBeVisible();

      // Assert the export response is the spreadsheet MIME (the corrected MIME,
      // oracle § MIME type) off the network, AND that the browser download fires.
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
      // Sanity: the downloaded bytes are a real ZIP/OOXML container (PK header).
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
