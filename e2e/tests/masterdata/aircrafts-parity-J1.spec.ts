/**
 * J-1 T-14 — LEGACY half of the Aircraft register side-by-side parity video.
 *
 * This is the "before" side of the J-1 side-by-side parity video, the legacy
 * counterpart to AlpenFlight's `aircraft-migration-parity.spec.ts` proof videos
 * (those carry `proof-journey: J-1`). J-1's PR gate ran synth-at-PR with no
 * legacy video; this spec captures the legacy flsweb aircraft parity video so
 * the operator can eyeball the legacy field set vs AlpenFlight's, side by side.
 *
 * The legacy stack (Mono `flsserver` + Node 8 `flsweb` + MSSQL) is nightly-
 * budget, so this lands in the nightly fan-out workflow
 * (`.github/workflows/alpenflight-proof-fanout.yml`), NOT the PR gate —
 * mirroring exactly how J-0c captures its legacy Location video.
 *
 * What it shows (read-only — the operator wants to compare FIELDS, not mutate):
 *   1. logs in (as the TestClub admin, UI login — same as the J-0c spec),
 *   2. opens the seeded aircraft list at `/masterdata/aircrafts` (FLSTest seeds
 *      ~15 aircraft) — the legacy aircraft-table column set
 *      (Immatriculation · AircraftModel · CompetitionSign · ManufacturerName ·
 *      NrOfSeats — `aircrafts-table.html:4-8`),
 *   3. opens ONE seeded aircraft's edit form (`/masterdata/aircrafts/:id`) and
 *      scrolls the field set into view (Immatriculation, CompetitionSign,
 *      AircraftType, ManufacturerName, AircraftModel, NrOfSeats, owner type,
 *      owner club/person, homebase, spot link, … — `aircraft-form-fields.html`),
 *   4. records the whole flow as the legacy parity video.
 *
 * Why read-only (deliberate, mirrors the design intent of the J-0c spec's
 * idempotency guard): a recording variant that CREATED an aircraft would, on a
 * Playwright retry against a pinned name, collide on the
 * `(Immatriculation, DeletedOn)` unique constraint (the exact trap
 * `aircrafts-crud.spec.ts:34-43` documents + pre-cleans via raw SQL). The
 * parity ASK is "show the legacy fields"; the seeded fleet already gives us a
 * populated list + a populated form to display, with zero mutation and zero
 * retry-collision surface. The mutating legacy CRUD path stays covered by
 * spec #26 (`aircrafts-crud.spec.ts`).
 *
 * Legacy-oracle grounding (verified against flsweb + FLSTest seed):
 *   - List route:  `#/masterdata/aircrafts` (`AircraftsModule.js:30`), template
 *     `aircrafts.html` → `<fls-data-table>` wrapping `<fls-aircrafts>` →
 *     `aircrafts-table.html` (ng-table, columns above).
 *   - Edit route:  `#/masterdata/aircrafts/:id` (`AircraftsModule.js:40`), form
 *     `name="aircraftForm"`, text input `#Immatriculation`
 *     (`aircraft-form-fields.html:15`) is the visible-when-loaded anchor — the
 *     same anchor `aircrafts-crud.spec.ts:67` waits on.
 *   - Seeded fleet:  FLSTest "4 or 5 Insert Test Data.sql" seeds ~15 aircraft;
 *     the list endpoint `aircrafts/listitems/gliders` returns them for the
 *     logged-in admin (used here only to pick a real AircraftId to open).
 *   - Admin + password:  `testclubadmin` / `s` (the TestClub `ClubAdministrator`,
 *     same credential the J-0c spec uses; `_test-fixture.sql`).
 *
 * STRUCTURAL STATUS (2026-06-03): the legacy stack does not run on the dev box
 * (Alpine/musl — no browser, no Mono/MSSQL). This spec is authored against the
 * REAL legacy selectors above and is structurally validated (tsc + `playwright
 * test --list` discovers it). Its FIRST LIVE green is the nightly/dispatch
 * fan-out workflow run that brings up the stack, runs it, and retains/publishes
 * the video to the proof gallery — the same first-green caveat the J-0c legacy
 * Location spec + that workflow document.
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
import type { Page } from "@playwright/test";

// Record the read-only walkthrough as the legacy parity video regardless of
// pass/fail. The fan-out workflow stages this artifact + publishes it to the
// proof gallery under J-1 (declared via the `--legacy-video` sidecar). Authored
// here so the spec is self-describing; the gate workflow records at the project
// level too.
test.use({ video: "on" });

const API_BASE = process.env.FLS_API ?? "http://localhost:25567";

// The seeded TestClub administrator (role ClubAdministrator). Password is the
// single letter `s` (_test-fixture.sql convention) — same as the J-0c spec.
const ADMIN = { username: "testclubadmin", password: "s" } as const;

/** Read the bearer token the SPA persisted, for the read-only API pick below. */
async function bearer(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem("ngStorage-loginResult");
    try {
      return raw ? (JSON.parse(raw).access_token as string) : null;
    } catch {
      return null;
    }
  });
  expect(token, "expected access_token in ngStorage-loginResult").toBeTruthy();
  return token as string;
}

// Single UI login + list render + one form open on the Mono/MSSQL legacy stack;
// give it the same headroom the other masterdata flows use.
test.setTimeout(120_000);

test("J-1 parity: legacy aircraft list + form field set (parity video)", async ({
  browser,
}, testInfo) => {
  // Own recording context (the J-0c spec's shape) so the video is one
  // continuous take of the list → form walkthrough at a fixed viewport.
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
    const token = await bearer(page);

    // ----- 1. LIST: the seeded aircraft fleet (the legacy column set) ---------
    await gotoRoute(page, "/masterdata/aircrafts");
    // The ng-table renders one <tr> per aircraft; wait for the first data row's
    // Immatriculation cell so the recording captures a populated list, not the
    // empty/loading shell. (Header row has no [ng-bind] immatriculation cell.)
    const firstImmat = page
      .locator('td[ng-bind="aircraft.Immatriculation"]')
      .first();
    await firstImmat.waitFor({ state: "visible", timeout: 30_000 });

    // J-1 T-42 — STABLE parity screenshot the fanout stages into the gallery
    // (declared in screenshots.json, side=legacy view=list). Written to this
    // test's output dir (under outputDir /tmp/fls-e2e-results/<spec-…>/) with a
    // FIXED basename so the staging step finds it by name the same way it finds
    // the .webm — distinct from the diagnostic `screenshot()` PNGs above (those
    // land under e2e/screenshots/<category>/ and are NOT gallery-declared).
    //
    // ROBUSTNESS (run 26926684710): capture the list PNG AS SOON AS the first
    // data row is visible — BEFORE the `not.toBeEmpty()` content assertion and
    // BEFORE the API pick / form steps below. In that run the J-1 gallery half
    // was missing BOTH legacy PNGs (list + form), so the spec aborted before the
    // list PNG ever ran; landing the screenshot at the earliest settled-list
    // moment makes the list-parity deliverable independent of any later check.
    await page.screenshot({
      path: testInfo.outputPath("legacy-aircraft-list.png"),
      fullPage: true,
    });
    await expect(firstImmat).not.toBeEmpty();
    await screenshot(page, "aircrafts-parity-J1-01-legacy-list");

    // Pick a REAL seeded AircraftId to open (read-only; the cheaper listitems
    // endpoint that aircrafts-crud.spec.ts uses, no paged-search 500 under
    // load). This drives WHICH form to open — it never mutates.
    const auth = {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    };
    const listRes = await page.request.get(
      `${API_BASE}/api/v1/aircrafts/listitems/gliders`,
      { headers: auth },
    );
    expect(
      listRes.ok(),
      `GET aircrafts/listitems/gliders: ${listRes.status()}: ${(await listRes.text().catch(() => "")).slice(0, 200)}`,
    ).toBeTruthy();
    const fleet = (await listRes.json()) as Array<{
      AircraftId: string;
      Immatriculation: string;
    }>;
    expect(
      fleet.length,
      "FLSTest should seed at least one aircraft for the parity walkthrough",
    ).toBeGreaterThan(0);
    const sample = fleet[0];

    // ----- 2. FORM: open one aircraft so the legacy field set is on camera ----
    await gotoRoute(page, `/masterdata/aircrafts/${sample.AircraftId}`);
    const immatInput = page.locator("#Immatriculation");
    await immatInput.waitFor({ state: "visible", timeout: 30_000 });
    await screenshot(page, "aircrafts-parity-J1-02-legacy-form-top");

    // J-1 T-42 — STABLE parity screenshot (side=legacy view=form), written RIGHT
    // AFTER the edit form's anchor field (#Immatriculation) is visible and
    // BEFORE the lower-field-set scroll/assert below. This screenshot is the
    // deliverable (the gallery's J-1 form-parity half); it must NOT be gated on
    // the #Comment visibility check (run 26926684710: the J-1 gallery half was
    // missing both legacy PNGs). The legacy aircraft edit form renders the whole
    // field set in one DOM pass once #Immatriculation is visible, so a fullPage
    // capture here gets the complete field set independent of any later check.
    await page.screenshot({
      path: testInfo.outputPath("legacy-aircraft-form.png"),
      fullPage: true,
    });
    await expect(immatInput).toHaveValue(sample.Immatriculation);

    // Scroll the lower field set (homebase, spot link, counters, comment) into
    // view so the WHOLE legacy aircraft form is visible across the recording —
    // this is the parity surface the operator wants to eyeball vs AlpenFlight.
    // #Comment is the last text field on the form (aircraft-form-fields.html:258).
    const comment = page.locator("#Comment");
    await comment.scrollIntoViewIfNeeded();
    await comment.waitFor({ state: "visible", timeout: 15_000 });
    await screenshot(page, "aircrafts-parity-J1-03-legacy-form-fields");
  } finally {
    await ctx.close();
  }

  // J-1 T-42 — SELF-GUARD: both gallery-declared parity PNGs MUST have landed.
  // The fanout staging only DECLARES screenshots it can `find`, so a
  // skipped-but-expected capture (run 26926684710: BOTH legacy PNGs missing
  // from the J-1 gallery half) was silently absent instead of red. Asserting
  // both files here turns a missed capture into a loud failure of THIS spec
  // step (non-blocking parity aid — it does not gate the AlpenFlight chain, but
  // it IS surfaced in the fanout's final-status step), so the gap can never
  // again hide behind continue-on-error.
  for (const png of ["legacy-aircraft-list.png", "legacy-aircraft-form.png"]) {
    expect(
      existsSync(testInfo.outputPath(png)),
      `expected parity screenshot ${png} to have been written to the test output dir — ` +
        `the fanout gallery's J-1 ${png.includes("list") ? "list" : "form"} parity half depends on it`,
    ).toBeTruthy();
  }
});
