/**
 * J-7 T-01 — LEGACY half of the flight-reports side-by-side parity gallery.
 *
 * The "before" side of the J-7 side-by-side parity gallery, the legacy
 * counterpart to AlpenFlight's `real-idp/flight-reports-parity.spec.ts` proof
 * videos (those carry `proof-journey: J-7`). J-7's PR gate runs the clean-seed
 * real chain with no legacy video; this spec captures the legacy flsweb
 * REPORTING parity video + the paired picker / canned-results / custom-builder
 * screenshots so the operator can eyeball the legacy reporting screens vs
 * AlpenFlight's, side by side.
 *
 * The legacy stack (Mono `flsserver` + Node 8 `flsweb` + MSSQL) is nightly-
 * budget, so this lands in the fan-out workflow
 * (`.github/workflows/alpenflight-proof-fanout.yml`), NOT the PR gate —
 * mirroring exactly how J-0c captures its legacy Location video, J-1 its legacy
 * aircraft video, J-2 its legacy flight video, J-5 its legacy reservation video,
 * and J-6 its legacy planning video (`planning-parity-J6.spec.ts`, the directly
 * mirrored model here).
 *
 * What it shows (read-only — the operator wants to compare SCREENS, not mutate):
 *   1. logs in (as the TestClub admin, UI login — same as the J-0c..J-6 specs),
 *   2. opens the reporting picker at `/flightreports` — the legacy category +
 *      canned-report tile grid (`flightreports.html`, `FlightReportsModule.js`),
 *   3. drives a canned report (`location-flights-this-year` — robust to
 *      wall-clock drift, the seed inserts flights with SYSDATETIME) and shows the
 *      filter-criteria panel + summary table + flights ng-table
 *      (`flightreportresults.html`),
 *   4. opens the custom builder (`/flightreports/custom/location/%7B%7D/edit`) and
 *      shows the legacy filter form (`flightreport-custom-configuration.html`),
 *   5. records the whole flow as the legacy parity video.
 *
 * Why read-only (deliberate, mirrors the J-0c..J-6 design intent): reporting is a
 * READ-SIDE screen — there is no mutation surface to begin with. The FLSTest seed
 * inserts flights on SYSDATETIME (so the year-wide canned window robustly contains
 * them) and the picker + custom-builder render their forms by route alone, so the
 * walkthrough is naturally idempotent with zero retry-collision surface.
 *
 * Legacy selectors (verified against flsweb, reused from the existing legacy
 * oracle stubs `e2e/tests/reporting/flight-reports.spec.ts` +
 * `custom-builder.spec.ts`):
 *   - Picker:        `/flightreports`, anchor
 *                    `a[href="#/flightreports/location/location-flights-this-year"]`.
 *   - Canned result: `/flightreports/location/location-flights-this-year`;
 *                    `.filter-criteria-panel`, summary `table.fls`,
 *                    flights `table[ng-table="tableParams"]`.
 *   - Custom builder:`/flightreports/custom/location/%7B%7D/edit`;
 *                    `.filter-criteria-panel` + `button[ng-click="applyCriteria()"]`.
 *   - Admin + password: `testclubadmin` / `s` (the TestClub `ClubAdministrator`,
 *     same credential the J-0c..J-6 specs use; `_test-fixture.sql`).
 *
 * STRUCTURAL STATUS (2026-06-09): the legacy stack does not run on the dev box
 * (Alpine/musl — no browser, no Mono/MSSQL). This spec is authored against the
 * REAL legacy selectors above and is structurally validated (tsc + `playwright
 * test --list` discovers it). Its FIRST LIVE green is the fan-out workflow run
 * that brings up the stack, runs it, and retains/publishes the video + paired
 * screenshots to the proof gallery under J-7 — the same first-green caveat the
 * J-0c..J-6 legacy specs + that workflow document. Until then the committed
 * legacy-reference `reporting/*.png` refs are absent and CI's `add_pair` degrades
 * to the AlpenFlight side (see `alpenflight/web/e2e/legacy-reference/README.md`).
 */

import { existsSync } from "node:fs";

import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  screenshot,
} from "../../fixtures";
import { ensureGliderFlight, getBearerToken } from "../../test-data";

// Record the read-only walkthrough as the legacy parity video regardless of
// pass/fail. The fan-out workflow stages this artifact + publishes it to the
// proof gallery under J-7 (declared via the `--legacy-video` sidecar).
test.use({ video: "on" });

// The seeded TestClub administrator (role ClubAdministrator). Password is the
// single letter `s` (_test-fixture.sql convention) — same as J-0c..J-6.
const ADMIN = { username: "testclubadmin", password: "s" } as const;

// The reporting page scans every club flight; under accumulated state this can
// take long. Give the whole flow generous headroom.
test.setTimeout(180_000);

test("J-7 parity: legacy reporting picker + canned result + custom builder (parity video)", async ({
  browser,
}, testInfo) => {
  // Own recording context (the J-0c..J-6 specs' shape) so the video is one
  // continuous take of the picker → canned-result → custom-builder walkthrough
  // at a fixed viewport.
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    recordVideo: {
      dir: testInfo.outputPath("video"),
      size: { width: 1280, height: 800 },
    },
  });
  const page = await ctx.newPage();

  try {
    await loginViaUi(page, ADMIN.username, ADMIN.password);
    await waitForLoggedInState(page);

    // Ensure ≥1 current-year LSZK glider flight so the canned report renders
    // something (location filter = testclub homebase LSZK; SYSDATETIME → current
    // year). Self-contained — don't depend on seed flights surviving.
    const token = await getBearerToken(page);
    await ensureGliderFlight(page.request, token, { comment: "J-7-parity" });

    // ----- 1. PICKER: the category + canned-report tile grid ------------------
    await gotoRoute(page, "/flightreports");
    const cannedLink = page.locator(
      'a[href="#/flightreports/location/location-flights-this-year"]',
    );
    await cannedLink.waitFor({ state: "visible", timeout: 30_000 });
    await screenshot(page, "reporting-parity-J7-01-legacy-picker");
    // STABLE parity screenshot the fanout stages into the gallery (side=legacy,
    // view=picker). FIXED basename `legacy-flightreports-picker.png` — it MUST
    // match the fanout's add_shot glob (alpenflight-proof-fanout.yml step 7,
    // "J-7 flight-reports parity SCREENSHOTS") + the J-7 expected-shots
    // legacy:picker view, exactly as the AlpenFlight half writes
    // alpenflight-flightreports-picker.png. Captured AS SOON AS the picker
    // renders (J-2 T-42: survive a partial red).
    await page.screenshot({
      path: testInfo.outputPath("legacy-flightreports-picker.png"),
      fullPage: true,
    });

    // ----- 2. CANNED RESULT: filter panel + summary + flights table -----------
    // Best-effort per-shot (J-5 T-38 / J-2 T-42): the page endpoint scans all
    // club flights and is slow on the Mono/MSSQL stack. Guard in its own
    // try/catch so a hiccup drops ONLY this shot, not the downstream custom shot.
    try {
      await gotoRoute(
        page,
        "/flightreports/location/location-flights-this-year",
      );
      const filterPanel = page.locator(".filter-criteria-panel");
      await filterPanel.waitFor({ state: "visible", timeout: 60_000 });
      const summaryTable = page.locator("table.fls").first();
      await summaryTable.waitFor({ state: "visible", timeout: 15_000 });
      await screenshot(page, "reporting-parity-J7-02-legacy-canned-result");
      await page.screenshot({
        path: testInfo.outputPath("legacy-flightreports-result.png"),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-7] legacy canned-result capture skipped (slow/absent report): ${
          (err as Error).message
        }`,
      );
    }

    // ----- 3. CUSTOM BUILDER: the legacy filter form --------------------------
    // AngularJS $location.path() URL-encodes `{}` to `%7B%7D`. Best-effort.
    try {
      await gotoRoute(page, "/flightreports/custom/location/%7B%7D/edit");
      const applyBtn = page
        .locator('button[ng-click="applyCriteria()"]')
        .first();
      await applyBtn.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "reporting-parity-J7-03-legacy-custom-builder");
      await page.screenshot({
        path: testInfo.outputPath("legacy-flightreports-custom.png"),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-7] legacy custom-builder capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    // SELF-GUARD (J-2 T-42 / J-5 T-38): the PICKER is the one load-bearing,
    // always-present legacy parity PNG (renders by route alone) — assert it
    // landed so a missed capture is a loud failure, not a hidden gallery gap. The
    // canned-result + custom-builder are BEST-EFFORT (their own try/catch above);
    // if absent the fanout's add_pair simply no-ops that entry.
    expect(
      existsSync(testInfo.outputPath("legacy-flightreports-picker.png")),
      "expected legacy parity screenshot legacy-flightreports-picker.png in the " +
        "test output dir — the fanout gallery's J-7 legacy half depends on it",
    ).toBeTruthy();
    for (const png of [
      "legacy-flightreports-result.png",
      "legacy-flightreports-custom.png",
    ]) {
      if (!existsSync(testInfo.outputPath(png))) {
        console.warn(
          `[J-7] best-effort legacy parity screenshot ${png} absent (slow/flaky ` +
            "legacy stack) — the gallery drops that one entry, the picker pair stands",
        );
      }
    }
  } finally {
    await page.close();
    await ctx.close();
  }
});
