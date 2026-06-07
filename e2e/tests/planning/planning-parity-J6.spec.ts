/**
 * J-6 T-13/T-16 — LEGACY half of the planning-days side-by-side parity gallery.
 *
 * The "before" side of the J-6 side-by-side parity gallery, the legacy
 * counterpart to AlpenFlight's `planning-migration-parity.spec.ts` proof videos
 * (those carry `journey: J-6`). J-6's PR gate runs the clean-seed real chain
 * with no legacy video; this spec captures the legacy flsweb PLANNING parity
 * video + the paired list / form / setup-wizard screenshots so the operator can
 * eyeball the legacy field set (the future-days list columns + the planning-day
 * edit form + the setup-wizard's range×weekday×location form) vs AlpenFlight's,
 * side by side.
 *
 * The legacy stack (Mono `flsserver` + Node 8 `flsweb` + MSSQL) is nightly-
 * budget, so this lands in the fan-out workflow
 * (`.github/workflows/alpenflight-proof-fanout.yml`), NOT the PR gate —
 * mirroring exactly how J-0c captures its legacy Location video, J-1 its legacy
 * aircraft video, J-2 its legacy flight video, and J-5 its legacy reservation
 * video (`reservations-parity-J5.spec.ts`, the directly-mirrored model here).
 *
 * What it shows (read-only — the operator wants to compare FIELDS, not mutate):
 *   1. logs in (as the TestClub admin, UI login — same as the J-0c / J-1 / J-2 /
 *      J-5 specs),
 *   2. opens the seeded future-days list at `/planning` — the legacy planning
 *      table column set (Date · Weekday · Location · Remarks · Towing pilot ·
 *      Flight operator · Instructor · #Reservations — `planning.html`),
 *   3. opens ONE seeded planning day's edit form (`/planning/:id/edit`) and shows
 *      the legacy planning-day field set + the inline per-day reservations table
 *      (`planning-edit.html`),
 *   4. visits the setup wizard `/planningsetup` for the range×weekday×location
 *      bulk-create form (`planning-setup.html`),
 *   5. records the whole flow as the legacy parity video.
 *
 * Why read-only (deliberate, mirrors the J-0c / J-1 / J-2 / J-5 design intent): a
 * recording variant that CREATED a planning day would, on a Playwright retry,
 * risk the unique/duplicate trap (V4 ux_pln_club_date_loc's legacy analogue) the
 * mutating CRUD specs handle with raw-SQL pre-clean. The parity ASK is "show the
 * legacy fields"; the deterministic FLSTest seed already gives a populated
 * future-days list (a GETDATE()+1 planning day with crew assignments + remarks
 * 'Test', "4 or 5 Insert Test Data.sql") and a populated edit form to display,
 * with zero mutation and zero retry-collision surface. The setup wizard renders
 * its empty form by route alone (no seed needed).
 *
 * Legacy-oracle grounding (verified against flsweb):
 *   - List route:   `#/planning` (`PlanningModule.js:31`), template
 *     `planning.html` → ng-table with one `<tr data-testid="row">` per planning
 *     day; the list defaults to `Day.From = today`
 *     (`PlanningDaysController.js:11-21`) so the GETDATE()+1 seed row renders.
 *     Row click → `showPlanningDayDetails` → `/planning/:id/view`
 *     (`PlanningDaysController.js:56-58`).
 *   - Edit route:   `#/planning/:id/:mode` (`PlanningModule.js:41`), template
 *     `planning-edit.html` → `form[name="planningForm"]` (date picker, location
 *     selectize, remarks, the 3 crew selectizes, the inline reservations table).
 *     `mode === 'edit'` makes the form editable (`PlanningDayEditController.js:52`).
 *   - Setup route:  `#/planningsetup` (`PlanningModule.js:51`), template
 *     `planning-setup.html` → start/end `fls-date-picker`s, 7 weekday checkboxes,
 *     a location selectize, and the "Generate Planning Days" submit.
 *   - Seeded data:  "4 or 5 Insert Test Data.sql:1033-1041" seeds a PlanningDay on
 *     GETDATE()+1 at the first location with remarks 'Test' + assignments — so the
 *     future-days list is populated for the TestClub.
 *   - Admin + password: `testclubadmin` / `s` (the TestClub `ClubAdministrator`,
 *     same credential the J-0c / J-1 / J-2 / J-5 specs use; `_test-fixture.sql`).
 *
 * STRUCTURAL STATUS (2026-06-07): the legacy stack does not run on the dev box
 * (Alpine/musl — no browser, no Mono/MSSQL). This spec is authored against the
 * REAL legacy selectors above and is structurally validated (tsc + `playwright
 * test --list` discovers it). Its FIRST LIVE green is the fan-out workflow run
 * that brings up the stack, runs it, and retains/publishes the video + paired
 * screenshots to the proof gallery under J-6 — the same first-green caveat the
 * J-0c / J-1 / J-2 / J-5 legacy specs + that workflow document.
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

// Record the read-only walkthrough as the legacy parity video regardless of
// pass/fail. The fan-out workflow stages this artifact + publishes it to the
// proof gallery under J-6 (declared via the `--legacy-video` sidecar).
test.use({ video: "on" });

// The seeded TestClub administrator (role ClubAdministrator). Password is the
// single letter `s` (_test-fixture.sql convention) — same as J-0c / J-1 / J-2 /
// J-5.
const ADMIN = { username: "testclubadmin", password: "s" } as const;

// Single UI login + list render + one form open + the setup-wizard form on the
// Mono/MSSQL legacy stack; the planning-day edit form's picker fetches are
// slow, so give the whole flow generous headroom.
test.setTimeout(180_000);

test("J-6 parity: legacy planning list + edit form + setup wizard (parity video)", async ({
  browser,
}, testInfo) => {
  // Own recording context (the J-0c / J-1 / J-2 / J-5 specs' shape) so the video
  // is one continuous take of the list → form → setup-wizard walkthrough at a
  // fixed viewport.
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

    // ----- 1. LIST: the seeded future-days list ------------------------------
    await gotoRoute(page, "/planning");
    // The ng-table renders one <tr data-testid="row"> per planning day, default-
    // filtered to Day.From = today, so the GETDATE()+1 seed row is present. Wait
    // for the data ROW first, THEN assert its date cell is populated, so the
    // recording captures a settled, POPULATED list (the seed gives ≥1 day).
    const firstRow = page.locator('tr[data-testid="row"]').first();
    await firstRow.waitFor({ state: "visible", timeout: 30_000 });
    // The first cell is the formatted Day (td[ng-bind="planningDay.Day | ..."]).
    const firstDate = firstRow.locator("td").first();
    await firstDate.waitFor({ state: "visible", timeout: 30_000 });
    await expect(firstDate).not.toBeEmpty();
    await screenshot(page, "planning-parity-J6-01-legacy-list");

    // J-6 T-16 — STABLE parity screenshot the fanout stages into the gallery
    // (declared in screenshots.json, side=legacy view=list). FIXED basename so
    // the staging step finds it by name the same way it finds the .webm.
    // Captured AS SOON AS the list renders, BEFORE any deeper assertion (J-2
    // T-42: survive a partial red).
    await page.screenshot({
      path: testInfo.outputPath("legacy-planning-list.png"),
      fullPage: true,
    });

    // ----- 2. FORM: open one planning day so the legacy field set is on camera.
    // Read-only — clicking a row NAVIGATES to `/planning/:id/view`
    // (showPlanningDayDetails). To match the task's `/planning/:id/edit` parity
    // pair AND show the editable field set, read the row's id off the view-URL
    // the click produced, then navigate to that day's `/edit` mode. No field is
    // changed, no save fired.
    //
    // BEST-EFFORT per-shot (J-5 T-38 / J-2 T-42 rule): the legacy edit form's
    // planning-day + picker fetches are slow/flaky on the Mono/MSSQL test stack.
    // Guard the form-open in its OWN try/catch so a single hiccup drops ONLY the
    // form shot — it must NOT kill the spec (which would also drop the downstream
    // setup-wizard capture and red the whole non-blocking parity video). Capture
    // the form PNG AS SOON AS the form renders, before any deeper interaction. A
    // missing form PNG just drops that one gallery entry (the fanout's add_shot
    // no-ops what it can't find).
    try {
      await firstRow.click();
      // showPlanningDayDetails navigates to `#/planning/:id/view`; read the id
      // from the URL, then switch to `/edit` for the editable form (the same
      // template, planningForm becomes editable when mode === 'edit').
      await page.waitForFunction(
        () => /#\/planning\/[0-9a-fA-F-]{36}\/view/.test(window.location.hash),
        undefined,
        { timeout: 30_000 },
      );
      const id = await page.evaluate(() => {
        const m = window.location.hash.match(
          /#\/planning\/([0-9a-fA-F-]{36})\/view/,
        );
        return m ? m[1] : null;
      });
      expect(id, "expected a planning-day id in the /view URL").toBeTruthy();
      await gotoRoute(page, `/planning/${id}/edit`);
      // The edit template loads `planning-edit.html`; anchor on the named form
      // (the busy indicator clears once the day + pickers + inline reservations
      // load).
      const editForm = page.locator('form[name="planningForm"]');
      await editForm.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "planning-parity-J6-02-legacy-form");
      await page.screenshot({
        path: testInfo.outputPath("legacy-planning-form.png"),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-6] legacy planning-edit form capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    // ----- 3. SETUP WIZARD: the range×weekday×location bulk-create form -------
    // `/planningsetup` renders `planning-setup.html` by route alone (no seed
    // needed): start/end date pickers, 7 weekday checkboxes, a location
    // selectize, and the "Generate Planning Days" submit. Read-only — we render
    // the empty form for field parity, we do NOT generate (a mutating retry would
    // accumulate days). Best-effort like the form above.
    try {
      await gotoRoute(page, "/planningsetup");
      // Anchor on the wizard form's start-date label/picker (the setup form has
      // no name attr; the start-date field is `#startdate`).
      const setupStart = page.locator("#startdate");
      await setupStart.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "planning-parity-J6-03-legacy-setup");
      await page.screenshot({
        path: testInfo.outputPath("legacy-planning-setup.png"),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-6] legacy planning setup-wizard capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    // SELF-GUARD (J-2 T-42 / J-5 T-38): the LIST is the one load-bearing,
    // always-present legacy parity PNG — assert it landed so a missed list
    // capture is a loud failure, not a hidden gallery gap. The form + setup are
    // BEST-EFFORT (their own try/catch above, slow/flaky legacy stack); if either
    // is absent the fanout's add_shot simply no-ops that entry rather than red the
    // gallery, so guarding them here would re-introduce the J-5 T-38 brittleness
    // (a flaky form-open killing the whole capture). Surface a non-fatal warning
    // for an absent best-effort shot so it's still visible in the logs.
    expect(
      existsSync(testInfo.outputPath("legacy-planning-list.png")),
      "expected legacy parity screenshot legacy-planning-list.png in the test " +
        "output dir — the fanout gallery's J-6 legacy half depends on it",
    ).toBeTruthy();
    for (const png of [
      "legacy-planning-form.png",
      "legacy-planning-setup.png",
    ]) {
      if (!existsSync(testInfo.outputPath(png))) {
        console.warn(
          `[J-6] best-effort legacy parity screenshot ${png} absent (slow/flaky ` +
            "legacy stack) — the gallery drops that one entry, the list pair stands",
        );
      }
    }
  } finally {
    await page.close();
    await ctx.close();
  }
});
