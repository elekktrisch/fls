import { test, expect, type Browser, type BrowserContext, type TestInfo } from '@playwright/test';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-7 — Flight-reports REAL-CHAIN parity SKELETON (live Keycloak auth + real
 * Spring backend + real Postgres). This is the gate-side sibling of the mock
 * inner-loop `tests/reporting/*` specs: it drives the `/flightreports` screen
 * against the J-2 migrated Flight/FlightCrew read-side with NO `page.route`
 * mocking, so the @TenantId report filter, the canned date-math, the summary
 * grouping rule, and the Excel export all run live.
 *
 * SKELETON STATUS (J-7 T-01): the `/flightreports` screen does not exist yet
 * (T-09/T-10/T-11 build it) and the backend read model + export endpoint are
 * pending (T-03..T-07). So the parity cases are `test.fixme`-skipped — they
 * encode the EXACT real-chain flow + the `proofVideo` emission the J-7 gallery
 * page assembles from, and T-15 flips them green with the full oracle assertions.
 *
 * What runs today is the PROOF-WIRING ANCHOR: `setup` provisions two real clubs
 * (so the gate harness + the proof-video helper are exercised on this spec from
 * task one) and emits the J-7 `proof-journey` annotation so the per-journey J-7
 * gallery page is generated as soon as this spec runs green. The heavy assertions
 * are deferred; the wiring is proven now.
 *
 * Parity contract this spec will assert (J-7-flight-reports.md § Parity
 * decisions, legacy-oracle 2026-06-09):
 *   - TENANT ISOLATION (non-negotiable correction): a club-A admin filtering by a
 *     club-B location sees NO club-B flights — every report query is @TenantId
 *     scoped (ADR 0008), correcting the legacy tenancy hole
 *     (FlightReportService.cs:114-125). Drive a real low-privilege principal
 *     ([[project_real_idp_real_roles_catches_authz_gaps]]).
 *   - CANNED DATE MATH preserved exactly incl. the last-7-days off-by-one.
 *   - SUMMARY grouping (person → crew-function rows incl. corrected
 *     TotalFlights≠0; location → FlightTypeName) + nested tow block.
 *   - EXCEL parity: the exported .xlsx is cell-for-cell equal to the legacy
 *     fixture (the T-08 parity harness; values/types strict, font/width cosmetic).
 *
 * Mirrors the model real-idp specs: `flight-migration-parity.spec.ts` (own
 * recorded context per club + proofVideo) and `locations-crud-tenant-isolation`.
 */

/**
 * Per-test recorded context — the `real-idp` project's `video: 'on'` only governs
 * Playwright's auto-created `page`; these tests drive their own
 * `browser.newContext()` per club, so pass `recordVideo` explicitly to land the
 * green run's `.webm` for the J-7 proof gallery (mirrors the J-2 flight spec).
 */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

test.describe('J-7 flight reports — real chain parity', () => {
  let fixture: TwoClubFixture;

  test.beforeAll(async ({ browser, baseURL }) => {
    fixture = await provisionTwoClubs(browser, baseURL!, 'rep');
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  // PROOF-WIRING ANCHOR — runs today. Provisions the two real clubs, logs the
  // club-A admin in, lands on the (future) /flightreports route, and emits the
  // J-7 proof-video annotation so the per-journey J-7 gallery page is generated.
  // The screen is unbuilt, so this asserts only that the real principal reaches
  // the app shell — the substantive parity assertions are the fixme cases below,
  // flipped green by T-15.
  test('real club-admin reaches the reporting route (J-7 proof anchor)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL!, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      // The route is not built yet (T-09); landing on the app shell with a REAL
      // principal proves the gate harness + auth seam for this spec. T-15 swaps
      // this for the picker → canned-results → export assertions.
      await page.goto('/flightreports');
      await expect(page).toHaveURL(/\/flightreports/);
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A real club administrator authenticates through live Keycloak and reaches the /flightreports screen (proof-wiring anchor; full report + export parity lands at T-15).',
        acTag: 'happy',
      });
    }
  });

  // ── PARITY CASES — fixme until the screen + backend land (T-03..T-11); T-15
  // flips them green with the full oracle assertions. ─────────────────────────

  test.fixme('canned person report: derived 30-day window + crew-function summary + flights table', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL!, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flightreports/person/my-flights-last-30-days');

      await expect(page.getByTestId('report-filter-criteria')).toBeVisible();
      await expect(page.getByTestId('report-summary-row').first()).toBeVisible();
      await expect(page.getByTestId('report-flights-row').first()).toBeVisible();
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A canned person report (last-30-days) renders the derived date window, the crew-function summary (corrected non-zero TotalFlights), and the migrated flights — live, tenant-scoped.',
        acTag: 'happy',
      });
    }
  });

  test.fixme('tenant isolation: club-A admin filtering by a club-B location sees no club-B flights', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL!, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      // Build a custom location report targeting club B's location id. The
      // @TenantId filter (ADR 0008) must yield an empty result — never a leak
      // (corrects the legacy tenancy hole, FlightReportService.cs:114-125).
      await page.goto('/flightreports/custom/location/%7B%7D/edit');
      // T-15: select club-B location, Apply, assert the empty-state.
      await expect(page.getByTestId('report-empty')).toBeVisible();
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'A club-A administrator filtering a report by a club-B location sees an empty result — the report query is @TenantId scoped, closing the legacy cross-club leak.',
        acTag: 'key-error',
      });
    }
  });

  test.fixme('Excel export is cell-for-cell equal to the legacy FlightReports fixture', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL!, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flightreports/person/my-flights-last-30-days');

      const downloadPromise = page.waitForEvent('download');
      await page.getByTestId('report-excel-export').click();
      const download = await downloadPromise;
      expect(download.suggestedFilename()).toMatch(/\.xlsx$/);
      // T-15: feed the downloaded .xlsx through the T-08 cell-diff parity
      // harness against the committed legacy fixture (values/types strict).
    } finally {
      await page.close();
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-7',
        caption:
          'The flight-reports Excel export streams an .xlsx that the parity harness proves cell-for-cell equal to the legacy fixture (values/types strict, font/width cosmetic).',
        acTag: 'happy',
      });
    }
  });
});
