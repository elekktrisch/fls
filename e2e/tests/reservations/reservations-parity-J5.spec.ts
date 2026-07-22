/**
 * J-5 T-16 — LEGACY half of the Aircraft-reservation side-by-side parity video.
 *
 * The "before" side of the J-5 side-by-side parity gallery, the legacy
 * counterpart to AlpenFlight's `reservations-migration-parity.spec.ts` proof
 * videos (those carry `proof-journey: J-5`). J-5's PR gate runs the clean-seed
 * real chain with no legacy video; this spec captures the legacy flsweb
 * RESERVATION parity video + the paired list/form screenshots so the operator
 * can eyeball the legacy field set (the reservations table columns + the edit
 * form + the reservation-scheduler grid) vs AlpenFlight's, side by side.
 *
 * The legacy stack (Mono `flsserver` + Node 8 `flsweb` + MSSQL) is nightly-
 * budget, so this lands in the fan-out workflow
 * (`.github/workflows/alpenflight-proof-fanout.yml`), NOT the PR gate —
 * mirroring exactly how J-0c captures its legacy Location video, J-1 its legacy
 * aircraft video, and J-2 its legacy flight video.
 *
 * What it shows (read-only — the operator wants to compare FIELDS, not mutate):
 *   1. logs in (as the TestClub admin, UI login — same as the J-0c / J-1 / J-2
 *      specs),
 *   2. opens the seeded reservation list at `/reservations` — the legacy
 *      reservation-table column set (Date · Start/End · Location · Immatriculation
 *      · Pilot · 2nd crew · Reservation type · Remarks — `reservations-table.html`),
 *   3. opens ONE seeded reservation's edit form (`/reservations/:id/edit`) and
 *      shows the legacy reservation field set (`reservations-edit.html`),
 *   4. visits `/reservation-scheduler` for the aircraft×time grid (best-effort:
 *      the per-aircraft series fetch is slow on the test stack — the legacy suite
 *      skips the deep scheduler assertion for that reason; here we register the
 *      aircraft + capture the grid screenshot as soon as it renders, bounded, and
 *      tolerate a slow/absent grid rather than red the whole video — J-2 T-42).
 *   5. records the whole flow as the legacy parity video.
 *
 * Why read-only (deliberate, mirrors the J-0c / J-1 / J-2 design intent): a
 * recording variant that CREATED a reservation would, on a Playwright retry,
 * risk the unique/duplicate trap the mutating CRUD spec (`crud.spec.ts`) handles
 * with raw-SQL pre-clean. The parity ASK is "show the legacy fields"; the
 * deterministic FLSTest seed already gives a populated list + a populated form to
 * display, with zero mutation and zero retry-collision surface. The mutating
 * legacy reservation paths stay covered by `tests/reservations/crud.spec.ts`.
 *
 * Legacy-oracle grounding (verified against flsweb):
 *   - List route:   `#/reservations` (`ReservationsModule.js:27`), template
 *     `reservations.html` → `<fls-reservations-table>` →
 *     `reservations-table.html` with one `<tr data-testid="row">` per
 *     reservation; the immatriculation cell is
 *     `td[ng-bind="reservation.Immatriculation"]`.
 *   - Edit route:   `#/reservations/:id/:mode` (`ReservationsModule.js:37`),
 *     template `reservations-edit.html`.
 *   - Scheduler:    `#/reservation-scheduler`
 *     (`ReservationSchedulerModule.js:18`), the SVG aircraft×time grid; the set
 *     of aircraft is the per-user `AircraftIdsToDisplayInScheduler` setting
 *     (POST /api/v1/settings), so a fresh user must register an aircraft first
 *     (`scheduler.spec.ts` pattern) or the `.scroll-container` is ng-if'd OUT.
 *   - Seeded data:  "4 or 5 Insert Test Data.sql" seeds an all-day reservation
 *     on the first aircraft for GETDATE()+1; J-5 T-07's `_test-fixture.sql`
 *     additionally seeds a TIMED cross-tenant reservation, a reservation type
 *     (Schulung), and the HB-3999 cross-club aircraft — so the list is populated.
 *   - Admin + password: `testclubadmin` / `s` (the TestClub `ClubAdministrator`,
 *     same credential the J-0c / J-1 / J-2 specs use; `_test-fixture.sql`).
 *
 * STRUCTURAL STATUS (2026-06-06): the legacy stack does not run on the dev box
 * (Alpine/musl — no browser, no Mono/MSSQL). This spec is authored against the
 * REAL legacy selectors above and is structurally validated (tsc + `playwright
 * test --list` discovers it). Its FIRST LIVE green is the fan-out workflow run
 * that brings up the stack, runs it, and retains/publishes the video + paired
 * screenshots to the proof gallery under J-5 — the same first-green caveat the
 * J-0c / J-1 / J-2 legacy specs + that workflow document.
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
// proof gallery under J-5 (declared via the `--legacy-video` sidecar).
test.use({ video: "on" });

const API_BASE = process.env.FLS_API ?? "http://localhost:25567";
const SETTINGS_KEY = "AircraftIdsToDisplayInScheduler";

// The seeded TestClub administrator (role ClubAdministrator). Password is the
// single letter `s` (_test-fixture.sql convention) — same as J-0c / J-1 / J-2.
const ADMIN = { username: "testclubadmin", password: "s" } as const;

// Single UI login + list render + one form open + a best-effort scheduler view
// on the Mono/MSSQL legacy stack; the scheduler's per-aircraft series fetch is
// slow, so give the whole flow generous headroom.
test.setTimeout(180_000);

async function bearer(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem("ngStorage-loginResult");
    try {
      return raw ? (JSON.parse(raw).access_token as string) : null;
    } catch {
      return null;
    }
  });
  expect(token, "expected access_token in sessionStorage").toBeTruthy();
  return token!;
}

async function currentUserId(page: Page): Promise<string> {
  const userId = await page.evaluate(() => {
    const raw = sessionStorage.getItem("ngStorage-user");
    try {
      return raw ? (JSON.parse(raw).UserId as string) : null;
    } catch {
      return null;
    }
  });
  expect(userId, "expected UserId in sessionStorage").toBeTruthy();
  return userId!;
}

test("J-5 parity: legacy reservation list + edit form (+ scheduler) (parity video)", async ({
  browser,
}, testInfo) => {
  // Own recording context (the J-0c / J-1 / J-2 specs' shape) so the video is one
  // continuous take of the list → form → scheduler walkthrough at a fixed
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

    // ----- 1. LIST: the seeded reservation list ------------------------------
    await gotoRoute(page, "/reservations");
    // The ng-table renders one <tr data-testid="row"> per reservation; the
    // immatriculation cell is `td[ng-bind="reservation.Immatriculation"]`. Wait
    // for the data ROW first, THEN the immat cell, so the recording captures a
    // settled, POPULATED list (the seed gives ≥1 reservation).
    // Poll the ROW COUNT until populated: the ng-table's first paint can show an
    // empty tbody before its per-request `getData` resolves, so a single-shot
    // waitFor races that empty frame. Retrying the count tolerates the getData
    // re-fire (same pattern as the flights-parity list + flight-reports rows).
    const rows = page.locator('tr[data-testid="row"]');
    await expect
      .poll(async () => rows.count(), {
        message: "expected at least one seeded reservation row",
        timeout: 30_000,
      })
      .toBeGreaterThanOrEqual(1);
    const firstImmat = page
      .locator(
        'tr[data-testid="row"] td[ng-bind="reservation.Immatriculation"]',
      )
      .first();
    await firstImmat.waitFor({ state: "visible", timeout: 30_000 });
    await expect(firstImmat).not.toBeEmpty();
    await screenshot(page, "reservations-parity-J5-01-legacy-list");

    // J-5 T-16 — STABLE parity screenshot the fanout stages into the gallery
    // (declared in screenshots.json, side=legacy view=list). FIXED basename so
    // the staging step finds it by name the same way it finds the .webm.
    // Captured AS SOON AS the list renders, BEFORE any deeper assertion (J-2
    // T-42: survive a partial red).
    await page.screenshot({
      path: testInfo.outputPath("legacy-reservation-list.png"),
      fullPage: true,
    });

    // ----- 2. FORM: open one reservation so the legacy field set is on camera.
    // Read-only — clicking a row NAVIGATES to `/reservations/:id` (the
    // ng-click="showReservationDetails(reservation)" on the row); no field is
    // changed, no save fired.
    //
    // BEST-EFFORT per-shot (J-5 T-38, mirrors the scheduler block below + the
    // J-2 T-42 rule): the legacy edit form's reservation + picker fetches are
    // slow/flaky on the Mono/MSSQL test stack. Guard the form-open in its OWN
    // try/catch so a single hiccup drops ONLY the form shot — it must NOT kill
    // the spec (which would also drop the downstream scheduler capture and red
    // the whole non-blocking parity video). Capture the form PNG AS SOON AS the
    // form renders, before any deeper interaction. A missing form PNG just drops
    // that one gallery entry (the fanout's add_shot no-ops what it can't find).
    try {
      await firstImmat.click();
      // The edit template loads `reservations-edit.html`; anchor on the form
      // element (the busy indicator clears once the reservation + pickers load).
      const editForm = page.locator("form").first();
      await editForm.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "reservations-parity-J5-02-legacy-form");
      await page.screenshot({
        path: testInfo.outputPath("legacy-reservation-form.png"),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-5] legacy reservation-edit form capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    // ----- 3. SCHEDULER (best-effort): the aircraft×time grid ----------------
    // The scheduler reads the per-user `AircraftIdsToDisplayInScheduler` setting;
    // a fresh user has none, so register every seeded aircraft first (the
    // scheduler.spec.ts pattern), then visit the grid. The per-aircraft series
    // fetch is slow on the test stack (the legacy suite skips its deep
    // assertion), so capture the grid AS SOON AS it renders, bounded, and
    // tolerate a slow/absent grid rather than red the whole parity video.
    try {
      const token = await bearer(page);
      const userId = await currentUserId(page);
      const authHeader = { Authorization: `Bearer ${token}` };
      const overview = await page.request.get(
        `${API_BASE}/api/v1/aircrafts/overview`,
        {
          headers: authHeader,
        },
      );
      if (overview.ok()) {
        const aircrafts = (await overview.json()) as Array<{
          AircraftId: string;
        }>;
        const aircraftIds = aircrafts.map((a) => a.AircraftId);
        await page.request.post(`${API_BASE}/api/v1/settings`, {
          headers: authHeader,
          data: {
            UserId: userId,
            SettingKey: SETTINGS_KEY,
            SettingValue: JSON.stringify(aircraftIds),
          },
        });
      }
      await gotoRoute(page, "/reservation-scheduler");
      // The grid container renders inside `.scroll-container .container`. Bound
      // the wait so a slow series-fetch doesn't burn the whole budget; capture
      // whatever rendered.
      await page
        .locator(".scroll-container .container")
        .first()
        .waitFor({ state: "visible", timeout: 45_000 });
      await screenshot(page, "reservations-parity-J5-03-legacy-scheduler");
      await page.screenshot({
        path: testInfo.outputPath("legacy-reservation-scheduler.png"),
        fullPage: true,
      });
    } catch (err) {
      // Best-effort only — the scheduler grid is slow/flaky on the test stack.
      // The list + form are the load-bearing parity views; a missing scheduler
      // shot just drops that one gallery entry (the staging step declares only
      // PNGs it can find), it does NOT fail the parity video.
      console.warn(
        `[J-5] legacy reservation-scheduler capture skipped (slow/absent grid): ${
          (err as Error).message
        }`,
      );
    }

    // SELF-GUARD (J-2 T-42): the LIST is the one load-bearing, always-present
    // legacy parity PNG — assert it landed so a missed list capture is a loud
    // failure, not a hidden gallery gap. The form + scheduler are BEST-EFFORT
    // (their own try/catch above, slow/flaky legacy stack); if either is absent
    // the fanout's add_shot simply no-ops that entry rather than red the
    // gallery, so guarding them here would re-introduce the J-5 T-38 brittleness
    // (a flaky form-open killing the whole capture). Surface a non-fatal warning
    // for an absent best-effort shot so it's still visible in the logs.
    expect(
      existsSync(testInfo.outputPath("legacy-reservation-list.png")),
      "expected legacy parity screenshot legacy-reservation-list.png in the test " +
        "output dir — the fanout gallery's J-5 legacy half depends on it",
    ).toBeTruthy();
    for (const png of [
      "legacy-reservation-form.png",
      "legacy-reservation-scheduler.png",
    ]) {
      if (!existsSync(testInfo.outputPath(png))) {
        console.warn(
          `[J-5] best-effort legacy parity screenshot ${png} absent (slow/flaky ` +
            "legacy stack) — the gallery drops that one entry, the list pair stands",
        );
      }
    }
  } finally {
    await page.close();
    await ctx.close();
  }
});
