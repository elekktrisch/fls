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

import { existsSync } from "node:fs";
import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  waitForBusyIndicatorsToClear,
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
    // The ng-table renders one <tr data-testid="row"> per flight (trace-verified
    // against run 26923775939: the row carries data-testid="row" and the glider
    // cell is `td.immatriculation[ng-bind="flight.Immatriculation"]` with value
    // HB-3407). The default FlightDate filter is today..today and the seed stamps
    // the flights on @Today, so the list is populated. Wait for the data ROW
    // first (ng-table can re-render the cell node after the row appears — that
    // re-render was the run's transient retry cell-visible timeout), THEN the
    // glider immatriculation cell, so the recording captures a settled list.
    await page
      .locator('tr[data-testid="row"]')
      .first()
      .waitFor({ state: "visible", timeout: 30_000 });
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

    // J-2 T-48 — DATA-LOADED GATE (operator-caught demonstrability bug). The
    // edit form renders its SHELL (all labels + #FlightDate host) BEFORE it
    // binds the selected flight's data: `<fls-busy-indicator busy="busy">`
    // wraps the whole form (flight-edit-form.html:3-7) and `busy` stays true
    // until BOTH loadMasterdata() AND the flight load resolve — it is only
    // cleared in the `$q.all(...).finally(() => $scope.busy = busyLoadingFlight)`
    // (FlightsController.js:311-314). T-42 moved the form PNG to "right after
    // the shell is visible" to drop the brittle tow drill-down dependency, but
    // that caught the form EMPTY with the AngularJS spinner still spinning
    // (every field value blank) — a useless legacy↔AlpenFlight parity shot.
    //
    // Gate the capture purely on DATA-LOADED (NOT on the tow sub-form — T-42's
    // win stays):
    //   (1) the busy spinner is GONE — `[data-testid="busy-indicator"]` (the
    //       ng-show="busy" backdrop around `.cssload-loader`,
    //       busy-indicator-directive.html:2-6) has collapsed to a zero box.
    //   (2) a known bound field carries a NON-EMPTY value. We anchor on the
    //       Datum field rather than a selectize: `#FlightDate` is the
    //       fls-date-picker HOST and its real text input is the child
    //       `input[pikaday]` bound to `stringDateValue`, which the directive
    //       sets to `moment(ngModel).format('DD.MM.YYYY')` once the flight's
    //       FlightDate binds (DatePickerInputDirective.js:56-58). An empty
    //       (still-loading) form leaves it blank; a populated form shows the
    //       seeded flight's date. Value-presence beats the selectize `.item`
    //       text, which is documented hostile to Playwright (TEST_WRITING.md
    //       §6). Per-assertion 5s cap per suite convention.
    await waitForBusyIndicatorsToClear(page);
    await expect(page.locator("#FlightDate input")).toHaveValue(/.+/, {
      timeout: 5_000,
    });
    await screenshot(page, "flights-parity-J2-02-legacy-form-top");

    // J-2 T-42/T-48 — STABLE parity screenshot (side=legacy view=form), written
    // AFTER the data-loaded gate above (busy spinner gone + Datum populated) and
    // BEFORE the tow drill-down assertions below. T-42 dropped the brittle
    // tow-field dependency; T-48 added the data-loaded gate so the deliverable
    // contains REAL field values (not an empty/loading form). This screenshot is
    // the deliverable (the gallery's J-2 form-parity half); it must NOT be gated
    // on a finicky tow-field visibility check. The seeded glider flight is the
    // paired-tow flight, and the legacy
    // edit form renders the WHOLE field set (date → glider → tow) in one DOM
    // pass once `#FlightDate` is visible (trace run 26926684710 error-context
    // confirms the full Schleppflugzeug HB-KCB / Schleppilot / tow-times block
    // is present in the snapshot). A fullPage capture here gets the complete
    // glider+tow field set with zero dependence on the selectize host's box.
    //
    // ROOT-CAUSE FIX (run 26926684710, flights-parity-J2.spec.ts:189): the form
    // PNG previously landed only AFTER an `expect(...TowAircraftId...).toBeVisible()`
    // that resolved `hidden` — the `<selectize>` host is a layout-less widget
    // host (Selectize renders a visible `.selectize-input` SIBLING and leaves the
    // original `<selectize>`/`<select>` host zero-box), so its visibility check
    // aborts the spec before the screenshot ever runs. Capturing first makes the
    // form PNG robust; the tow check below is now a non-fatal SECONDARY signal.
    await page.screenshot({
      path: testInfo.outputPath("legacy-flight-form.png"),
      fullPage: true,
    });

    // Scroll the glider + tow field set into view so the WHOLE legacy flight
    // form (date → glider field set → tow field set) is visible across the
    // recording — this is the parity surface the operator wants to eyeball vs
    // AlpenFlight's 3-step glider/tow wizard.
    //
    // IMPORTANT (first-live-run fix, run 26923775939): do NOT wait on the
    // `<fls-flight-edit-tow-form>` DIRECTIVE HOST element. That custom element is
    // a layout-less directive host (no width/height/display box of its own) —
    // Playwright's visibility check sees a zero-size box and resolves it
    // `hidden` even when its content is fully rendered (the run's "34 × resolved
    // to hidden <fls-flight-edit-tow-form>"). The actual tow field set lives in
    // the host's child `<div class="col-md-6" ng-if="needsTowplane(StartType)">`
    // (flight-edit-tow-form.html:1-2), a Bootstrap grid column that HAS a box and
    // renders only for a tow/aerotow start type. The seeded glider flight opened
    // here is the paired-tow flight (trace confirms HB-3407 → tow HB-KCB), so the
    // ng-if is true and the column renders. Anchor on that inner column for the
    // scroll-into-view diagnostic.
    const towFieldSet = page.locator(
      'fls-flight-edit-tow-form div[ng-if="needsTowplane(flightDetails.StartType)"]',
    );
    await towFieldSet.scrollIntoViewIfNeeded();
    await towFieldSet.waitFor({ state: "visible", timeout: 15_000 });
    // SECONDARY (non-fatal) tow-field signal. The towplane control is rendered by
    // Selectize: the original `[name="TowAircraftId"]` <selectize> host is
    // zero-box (`toBeVisible()` resolves `hidden` — run 26926684710:189), so we
    // assert on the VISIBLE Selectize widget it renders instead (`.selectize-control`
    // / `.selectize-input` sibling inside the same field set). This proves the tow
    // field set populated for the diagnostic screenshot below WITHOUT re-introducing
    // the directive/widget-host visibility trap. The form PNG above is already
    // written, so even a tow-side surprise can no longer suppress the deliverable.
    await expect(
      page
        .locator(
          'fls-flight-edit-tow-form div[ng-if="needsTowplane(flightDetails.StartType)"] .selectize-input',
        )
        .first(),
    ).toBeVisible();
    await screenshot(page, "flights-parity-J2-03-legacy-form-fields");

    // ----- 3. MOTOR: the air-movements list (the motor variant) --------------
    // The same shared flight table parameterized to the motor variant
    // (`/airmovements`, AirMovementsModule.js:34). T-08 seeds a
    // `Motor air-movement` flight; the default range is today and the seed
    // stamps it on @Today, so the motor list is populated. Read-only.
    await gotoRoute(page, "/airmovements");
    await page
      .locator('tr[data-testid="row"]')
      .first()
      .waitFor({ state: "visible", timeout: 30_000 });
    const firstMotor = page
      .locator(
        'tr[data-testid="row"] td.immatriculation[ng-bind="flight.Immatriculation"]',
      )
      .first();
    await firstMotor.waitFor({ state: "visible", timeout: 30_000 });
    await expect(firstMotor).not.toBeEmpty();
    await screenshot(page, "flights-parity-J2-04-legacy-airmovements");

    // J-2 T-43 — STABLE parity screenshot the fanout stages into the gallery
    // (declared in screenshots.json, side=legacy view=motor). This is the
    // legacy half of J-2's MOTOR-UNIFICATION parity story: legacy flsweb has a
    // SEPARATE `#/airmovements` motor screen, whereas AlpenFlight unifies motor
    // flights into the same /flights wizard (no separate route). Captured RIGHT
    // AFTER the motor row is visible (the populated motor list is settled),
    // BEFORE any further drill-down — same T-42 robustness lesson as the form
    // PNG: the deliverable must not be gated on a finicky later assertion. A
    // fullPage capture gets the whole motor list + its column set with zero
    // dependence on a single widget's box.
    await page.screenshot({
      path: testInfo.outputPath("legacy-airmovements-list.png"),
      fullPage: true,
    });
  } finally {
    await ctx.close();
  }

  // J-2 T-42 — SELF-GUARD: both gallery-declared parity PNGs MUST have landed.
  // The fanout staging only DECLARES screenshots it can `find`, so a
  // skipped-but-expected capture (the run-26926684710 bug, where the form PNG
  // was gated behind a brittle selectize assertion) was silently absent from
  // the gallery instead of red. Asserting both files here turns a missed
  // capture into a loud failure of THIS spec step (non-blocking parity aid —
  // it does not gate the AlpenFlight chain, but it IS surfaced in the fanout's
  // final-status step), so the gap can never again hide behind continue-on-error.
  // J-2 T-43 adds the third legacy surface (the /airmovements motor list) so the
  // gallery tells the MOTOR-UNIFICATION story (legacy separate /airmovements ↔
  // AlpenFlight unified /flights + motor wizard). All three legacy PNGs are
  // gallery-declared, so all three are guarded here.
  for (const png of [
    "legacy-flight-list.png",
    "legacy-flight-form.png",
    "legacy-airmovements-list.png",
  ]) {
    const view = png.includes("airmovements")
      ? "motor"
      : png.includes("list")
        ? "list"
        : "form";
    expect(
      existsSync(testInfo.outputPath(png)),
      `expected parity screenshot ${png} to have been written to the test output dir — ` +
        `the fanout gallery's J-2 ${view} parity half depends on it`,
    ).toBeTruthy();
  }
});
