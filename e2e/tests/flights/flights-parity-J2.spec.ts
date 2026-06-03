/**
 * J-2 T-10 — LEGACY half of the Flight logbook side-by-side parity video.
 *
 * This is the "before" side of the J-2 side-by-side parity video, the legacy
 * counterpart to AlpenFlight's `flight-migration-parity.spec.ts` proof videos
 * (those carry `proof-journey: J-2`). J-2's PR gate runs synth-at-PR with no
 * legacy video; this spec captures the legacy flsweb FLIGHT parity video so the
 * operator can eyeball the legacy field set (glider + paired tow + motor air
 * movement) vs AlpenFlight's, side by side.
 *
 * The legacy stack (Mono `flsserver` + Node 8 `flsweb` + MSSQL) is nightly-
 * budget, so this lands in the nightly fan-out workflow
 * (`.github/workflows/alpenflight-proof-fanout.yml`), NOT the PR gate —
 * mirroring exactly how J-0c captures its legacy Location video and J-1 its
 * legacy aircraft video.
 *
 * What it shows (read-only — the operator wants to compare FIELDS, not mutate):
 *   1. logs in (as the TestClub admin, UI login — same as the J-0c / J-1 specs),
 *   2. opens the seeded flight list at `/flights` (the FlightsController defaults
 *      its FlightDate filter to TODAY, and T-08 seeds the test flights on
 *      `@Today` — the glider + paired tow rows show in the default range) — the
 *      legacy flight-table column set (Status · Date · Glider plane ·
 *      Pilot/2nd crew · Start location · Takeoff · Landing · Glider duration ·
 *      Towing plane · Towing pilot · Tow landing · Tow duration · Comment —
 *      `flights.html:38-128`),
 *   3. opens ONE seeded flight's edit form (`/flights/:id`) and scrolls the
 *      glider + tow field set into view (the `<fls-flight-edit-glider-form>` +
 *      `<fls-flight-edit-tow-form>` directives on `flight-edit-form.html:26,30`),
 *   4. visits `/airmovements` for the MOTOR air-movement list (the same shared
 *      table parameterized to the motor variant — `air-movements.html`), where
 *      T-08 seeds a `Motor air-movement` flight,
 *   5. records the whole flow as the legacy parity video.
 *
 * Why read-only (deliberate, mirrors the design intent of the J-0c / J-1 specs):
 * a recording variant that CREATED a flight would, on a Playwright retry, risk
 * the same unique/duplicate trap the masterdata create specs document + pre-clean
 * via raw SQL. The parity ASK is "show the legacy fields"; the T-08 seeded flights
 * already give us a populated list + a populated form to display, with zero
 * mutation and zero retry-collision surface. The mutating legacy flight paths stay
 * covered by the `tests/flights/` CRUD specs (create / edit / state-transitions).
 *
 * Legacy-oracle grounding (verified against flsweb + the T-08 seed
 * `flsserver/database/FLSTest/3 insert/6 Insert Test Flights.sql`):
 *   - List route:   `#/flights` (`FlightsModule.js:44`), template `flights.html`
 *     → `<table ng-table>` with one `<tr data-testid="row">` per flight
 *     (`flights.html:31,37`); the glider immatriculation cell is
 *     `td.immatriculation[ng-bind="flight.Immatriculation"]` (`flights.html:57-61`).
 *     The default filter is `FlightDate = today..today`
 *     (`FlightsController.js:49-51`); the seed stamps the flights on `@Today`
 *     (seed line 55 `SET @Today = DATEDIFF(dd, 0, SYSDATETIME())`), so the
 *     default list is populated — no date-filter wrangefiddling needed.
 *   - Edit route:   `#/flights/:id` (`FlightsModule.js:54`), form
 *     `name="flightDetailsForm"` (`flight-edit-form.html:7`), the `#FlightDate`
 *     date picker (`flight-edit-form.html:17`) is the visible-when-loaded anchor;
 *     the glider + tow field sets render via the two directives below it.
 *   - Air-movements: `#/airmovements` (`AirMovementsModule.js:34`), template
 *     `air-movements.html` — same `<tr data-testid="row">` shape, motor variant.
 *   - Seeded flights: T-08 "6 Insert Test Flights.sql" seeds a glider+paired-tow
 *     set + a `Motor air-movement` flight on the TestClub for TODAY.
 *   - Admin + password: `testclubadmin` / `s` (the TestClub `ClubAdministrator`,
 *     same credential the J-0c / J-1 specs use; `_test-fixture.sql`).
 *
 * STRUCTURAL STATUS (2026-06-03): the legacy stack does not run on the dev box
 * (Alpine/musl — no browser, no Mono/MSSQL). This spec is authored against the
 * REAL legacy selectors above and is structurally validated (tsc + `playwright
 * test --list` discovers it). Its FIRST LIVE green is the nightly/dispatch
 * fan-out workflow run that brings up the stack, runs it, and retains/publishes
 * the video to the proof gallery — the same first-green caveat the J-0c / J-1
 * legacy specs + that workflow document.
 */

import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  screenshot,
} from "../../fixtures";

// Record the read-only walkthrough as the legacy parity video regardless of
// pass/fail. The fan-out workflow stages this artifact + publishes it to the
// proof gallery under J-2 (declared via the `--legacy-video` sidecar). Authored
// here so the spec is self-describing; the gate workflow records at the project
// level too. (Identical shape to the J-1 aircraft parity spec.)
test.use({ video: "on" });

// The seeded TestClub administrator (role ClubAdministrator). Password is the
// single letter `s` (_test-fixture.sql convention) — same as the J-0c / J-1 specs.
const ADMIN = { username: "testclubadmin", password: "s" } as const;

// Single UI login + two list renders + one form open on the Mono/MSSQL legacy
// stack; give it the same headroom the other masterdata/flight flows use.
test.setTimeout(120_000);

test("J-2 parity: legacy flight list (glider+tow) + form + motor air-movements (parity video)", async ({
  browser,
}, testInfo) => {
  // Own recording context (the J-0c / J-1 specs' shape) so the video is one
  // continuous take of the list → form → air-movements walkthrough at a fixed
  // viewport.
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

    // ----- 1. LIST: the seeded flight list (glider + paired tow) -------------
    await gotoRoute(page, "/flights");
    // The ng-table renders one <tr data-testid="row"> per flight; wait for the
    // first data row's glider-immatriculation cell so the recording captures a
    // populated list, not the empty/loading shell. (The default FlightDate
    // filter is today..today and the seed stamps the flights on @Today.)
    const firstImmat = page
      .locator(
        'tr[data-testid="row"] td.immatriculation[ng-bind="flight.Immatriculation"]',
      )
      .first();
    await firstImmat.waitFor({ state: "visible", timeout: 30_000 });
    await expect(firstImmat).not.toBeEmpty();
    await screenshot(page, "flights-parity-J2-01-legacy-list");

    // J-2 T-10 — STABLE parity screenshot the fanout stages into the gallery
    // (declared in screenshots.json, side=legacy view=list). Written to this
    // test's output dir (under outputDir /tmp/fls-e2e-results/<spec-…>/) with a
    // FIXED basename so the staging step finds it by name the same way it finds
    // the .webm — distinct from the diagnostic `screenshot()` PNGs above (those
    // land under e2e/screenshots/<category>/ and are NOT gallery-declared).
    // Mirrors J-1's `legacy-aircraft-list.png` contract exactly.
    await page.screenshot({
      path: testInfo.outputPath("legacy-flight-list.png"),
      fullPage: true,
    });

    // ----- 2. FORM: open one flight so the legacy glider+tow field set is on
    // camera. Read-only — clicking a row NAVIGATES to `/flights/:id` (the
    // ng-click="editFlight(flight)" on the row, `flights.html:32`); no field is
    // changed, no save fired. This shows WHICH flight to open and never mutates.
    await firstImmat.click();
    // The edit template loads `name="flightDetailsForm"` with the `#FlightDate`
    // date picker as the visible-when-loaded anchor (flight-edit-form.html:17).
    const flightDate = page.locator("#FlightDate");
    await flightDate.waitFor({ state: "visible", timeout: 30_000 });
    await screenshot(page, "flights-parity-J2-02-legacy-form-top");

    // Scroll the glider + tow directives into view so the WHOLE legacy flight
    // form (date → glider field set → tow field set) is visible across the
    // recording — this is the parity surface the operator wants to eyeball vs
    // AlpenFlight's 3-step glider/tow wizard. The tow form's towplane
    // registration label is the lower anchor (flight-edit-tow-form.html:4).
    const towForm = page.locator("fls-flight-edit-tow-form");
    await towForm.scrollIntoViewIfNeeded();
    await towForm.waitFor({ state: "visible", timeout: 15_000 });
    await screenshot(page, "flights-parity-J2-03-legacy-form-fields");

    // J-2 T-10 — STABLE parity screenshot (side=legacy view=form). fullPage so
    // the WHOLE legacy flight field set (date → glider → tow) is one image the
    // operator eyeballs against AlpenFlight's flight form. Same fixed-name +
    // output-dir contract as the list screenshot above (mirrors J-1's
    // `legacy-aircraft-form.png`).
    await page.screenshot({
      path: testInfo.outputPath("legacy-flight-form.png"),
      fullPage: true,
    });

    // ----- 3. MOTOR: the air-movements list (the motor variant) --------------
    // The same shared flight table parameterized to the motor variant
    // (`/airmovements`, AirMovementsModule.js:34). T-08 seeds a
    // `Motor air-movement` flight; the default range is today and the seed
    // stamps it on @Today, so the motor list is populated. Read-only.
    await gotoRoute(page, "/airmovements");
    const firstMotor = page
      .locator(
        'tr[data-testid="row"] td.immatriculation[ng-bind="flight.Immatriculation"]',
      )
      .first();
    await firstMotor.waitFor({ state: "visible", timeout: 30_000 });
    await expect(firstMotor).not.toBeEmpty();
    await screenshot(page, "flights-parity-J2-04-legacy-airmovements");
  } finally {
    await ctx.close();
  }
});
