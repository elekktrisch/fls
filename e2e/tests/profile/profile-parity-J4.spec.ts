/**
 * J-4 T-12 — LEGACY half of the /profile side-by-side parity video + screenshots.
 *
 * This is the "before" side of J-4's legacy↔AlpenFlight /profile parity gallery —
 * the legacy flsweb counterpart to AlpenFlight's 4-tab /profile redesign (whose
 * shots are written by `alpenflight/web/e2e/tests/profile/self-edit.spec.ts`,
 * journey J-4, side alpenflight). J-4 carries NO data migration (Person / User /
 * PersonClub already migrate via the identity mappers), so the fan-out's
 * export→migrate half is NOT needed for this journey — only the legacy-video
 * harness step, exactly mirroring how J-0c / J-1 / J-2 capture their legacy
 * videos. AlpenFlight green is still the gate; this is a non-blocking parity aid.
 *
 * The legacy stack (Mono `flsserver` + Node 8 `flsweb` + MSSQL) is nightly-
 * budget, so this lands in the nightly fan-out workflow
 * (`.github/workflows/alpenflight-proof-fanout.yml`), NOT the per-PR ci.yml gate
 * — mirroring exactly how J-0c / J-1 / J-2 capture their legacy parity videos.
 *
 * What it shows (READ-ONLY — the operator wants to compare FIELDS, not mutate):
 *   the legacy `#/profile` screen is a SINGLE non-tabbed page = TWO side-by-side
 *   forms (`profile.html`):
 *     LEFT  (`name="profileForm"`): the user-settings + password form — the
 *           disabled `#username`, the dropped-by-design password fields
 *           (OldPassword / NewPassword / NewPassword2 — NOT carried into
 *           AlpenFlight, Keycloak owns credentials, ADR 0007), the `#LanguageId`
 *           selectize, and the "Save user settings" button.
 *     RIGHT (`name="personForm"`, renders only when `myUser.PersonId` is truthy):
 *           the shared `<fls-person-form>` accordion (`person-form-fields.html`)
 *           with 5 groups — Masterdata, Communication, License, Club-Settings,
 *           Person-Categories. The License group (`#HasGliderPilotLicence`,
 *           `#LicenceNumber`, `#MedicalClass2ExpireDate`) is the field set J-4's
 *           Pilot tab maps to; the Club-Settings group holds the notification
 *           flags J-4's Notifications tab maps to.
 *
 * The capture takes ONE screenshot per AlpenFlight-tab equivalent so the gallery
 * pairs legacy↔AlpenFlight per view (the four views match the AlpenFlight tab
 * views declared by the showcase capture: "Account tab", "Personal tab",
 * "Pilot tab", "Notifications tab"):
 *   1. "Account tab"       — the LEFT user-settings form scrolled to the top
 *      (username / language / password drop), vs AlpenFlight's Account tab.
 *   2. "Personal tab"      — the RIGHT person form's Masterdata + Communication
 *      groups (name / address / contact), vs AlpenFlight's Personal tab.
 *   3. "Pilot tab"         — the RIGHT person form's License group scrolled into
 *      view (licence flags / licence number / medical-class-2 expiry), vs
 *      AlpenFlight's Pilot tab.
 *   4. "Notifications tab" — the RIGHT person form's Club-Settings group scrolled
 *      into view (the receive-* notification flags), vs AlpenFlight's
 *      Notifications tab.
 * Plus the whole walkthrough is recorded as the legacy parity video.
 *
 * Why read-only (deliberate, mirrors the J-0c / J-1 / J-2 specs' design intent):
 * a recording variant that SUBMITTED a form would, on a Playwright retry, risk a
 * partial mutation of the linked Person + (worse) could fire the legacy
 * change-password / save-user path. The parity ASK is "show the legacy fields";
 * displaying the seeded person already gives a populated person form to capture,
 * with zero mutation and zero retry-collision surface. We do NOT submit either
 * form, do NOT change the password (the dropped-by-design affordance), do NOT
 * fire "Save user settings".
 *
 * Test-data wrinkle (same as the legacy `e2e/tests/profile/edit.spec.ts` mutation
 * spec): the seeded `testclubadmin` user has `Users.PersonId = NULL`, so the
 * RIGHT `<fls-person-form>` (`ng-if="myUser.PersonId"`, profile.html:75) never
 * renders out of the box. To capture the person field set we must, BEFORE login,
 * point `testclubadmin` at an existing TestClub Person (a single read-only SQL
 * UPDATE of `Users.PersonId`) so the form renders. We deliberately keep the link
 * in place for the screenshot only — no Person field is edited or saved. We drive
 * the page via the injected-sessionStorage `loggedInPage` fixture (NOT a UI login)
 * so the patched `ngStorage-user.PersonId` is what `AuthService.getUser()` reads
 * when `ProfileController` evaluates the `ng-if`.
 *
 * Legacy-oracle grounding (verified against flsweb `profile/` + the seed):
 *   - Route:    `#/profile` (`ProfileModule.js:14`), template `profile.html`,
 *               controller `ProfileController.js` (loads `GET /persons/my` +
 *               `AuthService.getUser()` self).
 *   - LEFT form `name="profileForm"`: `#username` (disabled, profile.html:13-17),
 *               `#OldPassword`/`#NewPassword`/`#NewPassword2` (the dropped password
 *               form), `#LanguageId` selectize, "Save user settings" button.
 *   - RIGHT form `name="personForm"` `ng-if="myUser.PersonId"`: `<fls-person-form>`
 *               accordion — License group anchors `#HasGliderPilotLicence`,
 *               `#LicenceNumber`, `#MedicalClass2ExpireDate`
 *               (person-form-fields.html:188,279,289); Club-Settings notification
 *               anchors `#ReceiveFlightReports`,
 *               `#ReceiveAircraftReservationNotifications`,
 *               `#ReceivePlanningDayRoleReminder` (person-form-fields.html:387,393,400).
 *   - Admin: `testclubadmin` / `s` (the TestClub `ClubAdministrator`, same
 *            credential the J-0c / J-1 / J-2 specs use; `_test-fixture.sql`).
 *
 * STRUCTURAL STATUS (2026-06-05): the legacy stack does not run on the dev box
 * (Alpine/musl — no browser, no Mono/MSSQL). This spec is authored against the
 * REAL legacy selectors above and is structurally validated (tsc + `playwright
 * test --list` discovers it). Its FIRST LIVE green is the nightly/dispatch
 * fan-out workflow run that brings up the stack, runs it, and retains/publishes
 * the video to the proof gallery — the same first-green caveat the J-0c / J-1 /
 * J-2 legacy specs + that workflow document.
 */

import { existsSync } from "node:fs";
import {
  test,
  expect,
  gotoRoute,
  waitForBusyIndicatorsToClear,
  screenshot,
} from "../../fixtures";
import sql from "mssql";
import type { Page } from "@playwright/test";

/**
 * Expand a collapsed `<fls-person-form>` accordion group, idempotently.
 *
 * The legacy person form (`person-form-fields.html`) is an angular-ui-bootstrap
 * 0.13.4 `<accordion>` (package.json: "angular-ui-bootstrap": "0.13.4"). Each
 * `<accordion-group>` heading transcludes into `a.accordion-toggle`
 * (panel-heading) and toggles its `is-open` scope flag on click. Only
 * Masterdata (`status1`) and Communication (`status2`) carry
 * `ng-init="statusN = true"`, so they render OPEN; License (`status3`),
 * Club-Settings (`status4`) and Person-Categories (`status5`) have NO ng-init,
 * so `is-open` is undefined → those groups render COLLAPSED. A collapsed group's
 * fields are in the DOM but not visible/scrollable, so a `scrollIntoViewIfNeeded`
 * on e.g. `#LicenceNumber` times out (the ~10s timeout at the Pilot/Notifications
 * captures, fan-out run 27039051676).
 *
 * The angular-translate 2.8.0 attribute directive leaves the `translate="<KEY>"`
 * attribute on the heading span in the DOM (it only sets text content), so we
 * target the group by its locale-independent translate key. We click the heading
 * anchor only if the anchored field is not already visible — keeping it idempotent
 * for the already-open groups and safe to call before any capture.
 */
async function expandGroupIfCollapsed(
  page: Page,
  headingTranslateKey: string,
  anchorSelector: string,
): Promise<void> {
  const anchor = page.locator(anchorSelector);
  // Already expanded (status1/status2, or a re-run on the same page) → nothing
  // to do. A short poll: a collapsed group's field is hidden, not absent.
  if (await anchor.isVisible().catch(() => false)) {
    return;
  }
  // The transcluded heading: `a.accordion-toggle` is the ancestor of the
  // `span[translate="<KEY>"]` heading label (accordion-group template).
  const heading = page.locator("a.accordion-toggle", {
    has: page.locator(`span[translate="${headingTranslateKey}"]`),
  });
  await heading.waitFor({ state: "visible", timeout: 15_000 });
  await heading.click();
  // The collapse animation reveals the panel body; wait for the anchored field
  // to become visible before the caller scrolls/screenshots it.
  await anchor.waitFor({ state: "visible", timeout: 15_000 });
}

// Record the read-only walkthrough as the legacy parity video regardless of
// pass/fail. The fan-out workflow stages this artifact + publishes it to the
// proof gallery under J-4 (declared via the `--legacy-video` sidecar). Identical
// shape to the J-1 / J-2 parity specs.
test.use({ video: "on" });

// Single fixture link + four scroll-and-screenshot passes on the Mono/MSSQL
// legacy stack; give it the same headroom the other legacy parity flows use.
test.setTimeout(120_000);

const MSSQL_CONFIG: sql.config = {
  user: "sa",
  password: process.env.FLS_MSSQL_SA_PASSWORD ?? "Demo#FLS#2026",
  server: "localhost",
  port: 1433,
  database: "FLSTest",
  options: { trustServerCertificate: true, encrypt: false },
  pool: { max: 2, min: 0, idleTimeoutMillis: 5000 },
};

async function withPool<T>(
  fn: (pool: sql.ConnectionPool) => Promise<T>,
): Promise<T> {
  const pool = await new sql.ConnectionPool(MSSQL_CONFIG).connect();
  try {
    return await fn(pool);
  } finally {
    await pool.close();
  }
}

test("J-4 parity: legacy /profile field set (user-settings + person License/Notifications groups) (parity video)", async ({
  loggedInPage,
}, testInfo) => {
  const page: Page = loggedInPage;

  // ----- 0. LINK testclubadmin to a TestClub Person (read-only display setup) -
  // The seeded testclubadmin has Users.PersonId = NULL, so the RIGHT
  // <fls-person-form> (ng-if="myUser.PersonId") would never render. Point it at
  // an existing TestClub Person with a non-null Lastname so the person field set
  // is populated for the capture. This is the SAME setup the legacy
  // edit.spec.ts mutation spec uses — but here we ONLY display, never save.
  const personId = await withPool(async (pool) => {
    const r = await pool.request().query(`
      DECLARE @pid uniqueidentifier =
        (SELECT TOP 1 p.PersonId FROM Persons p
           INNER JOIN PersonClub pc ON pc.PersonId = p.PersonId
           INNER JOIN Clubs c ON c.ClubId = pc.ClubId
          WHERE c.ClubKey = 'TestClub'
            AND p.Lastname IS NOT NULL
          ORDER BY p.Lastname);
      UPDATE Users SET PersonId = @pid WHERE Username = 'testclubadmin';
      SELECT @pid AS PersonId;
    `);
    return r.recordset[0].PersonId as string;
  });
  expect(
    personId,
    "expected a TestClub Person to attach for the person-form display",
  ).toBeTruthy();

  // Mirror the link into ngStorage-user so AuthService.getUser() sees the
  // PersonId before ProfileController evaluates the ng-if. The loggedInPage
  // fixture injected ngStorage-user via an init script on first nav; patch it.
  await page.goto("/#/main");
  await page.evaluate((pid) => {
    const raw = sessionStorage.getItem("ngStorage-user");
    if (!raw) return;
    const u = JSON.parse(raw);
    u.PersonId = pid;
    sessionStorage.setItem("ngStorage-user", JSON.stringify(u));
  }, personId);

  try {
    // ----- 1. NAVIGATE to /profile + wait for BOTH forms to render -----------
    await gotoRoute(page, "/profile");
    // The busy spinner clears once GET /persons/my + the masterdata loads resolve
    // (ProfileController.js:60-62). gotoRoute already waits for busy-clear; assert
    // the stable anchors of both forms so the recording captures a settled page.
    await waitForBusyIndicatorsToClear(page);

    // LEFT form anchor: the disabled username field is populated from the token
    // user (profile.html:13-17) the moment the controller binds myUser.
    //
    // SCOPE the locator to `form[name="profileForm"]` — a bare `#username` is
    // AMBIGUOUS: the always-mounted login-form directive
    // (core/directives/loginForm/login-form-directive.html:11) ALSO renders an
    // `id="username"` (the `ng-model="user.username"` login input), so a
    // page-wide `#username` resolves to TWO elements and trips Playwright strict
    // mode (the original failure at this anchor). The profile form's disabled
    // username (`<input disabled id="username" ng-model="myUser.UserName">`,
    // profile.html:15) is the one we want — qualify by its `name="profileForm"`
    // ancestor so the match is unique to the profile page. (No other anchor in
    // this spec collides: `#password` is login-only — the profile password drop
    // uses `#OldPassword`/`#NewPassword` — and every person-form anchor below is
    // unique to person-form-fields.html.)
    const username = page.locator('form[name="profileForm"] #username');
    await username.waitFor({ state: "visible", timeout: 30_000 });
    await expect(username).not.toHaveValue("");

    // RIGHT form anchor: the person form renders (ng-if true now PersonId is set)
    // and binds the loaded person — Firstname is populated from GET /persons/my.
    const personForm = page.locator('form[name="personForm"]');
    await personForm.waitFor({ state: "visible", timeout: 30_000 });
    await page
      .locator("#Firstname")
      .waitFor({ state: "visible", timeout: 30_000 });
    await expect(page.locator("#Firstname")).not.toHaveValue("");

    // ----- 2. "Account tab" view — the LEFT user-settings + password form ----
    // Scroll the LEFT form to the top so the username (disabled), the password
    // drop (OldPassword/NewPassword), the language selectize + "Save user
    // settings" are on camera — the field set AlpenFlight's Account tab redesigns
    // (password gone, language kept). Diagnostic PNG + the stable gallery PNG.
    await username.scrollIntoViewIfNeeded();
    await screenshot(page, "profile-parity-J4-01-legacy-account");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-account.png"),
      fullPage: true,
    });

    // ----- 3. "Personal tab" view — person Masterdata + Communication groups --
    // The Masterdata (name/address) + Communication (email/phone) accordion
    // groups render open by default (ng-init status1/status2 = true). Expand
    // idempotently (a no-op while they're open) so the capture is robust if the
    // ng-init default ever changes, then anchor on a Communication field so the
    // shot proves the contact field set is bound. fullPage so name+address+contact
    // are all on camera — the field set AlpenFlight's Personal tab edits.
    await expandGroupIfCollapsed(page, "MASTERDATA", "#Firstname");
    await expandGroupIfCollapsed(page, "COMMUNICATION", "#MobilePhoneNumber");
    const mobile = page.locator("#MobilePhoneNumber");
    await mobile.scrollIntoViewIfNeeded();
    await mobile.waitFor({ state: "visible", timeout: 15_000 });
    await screenshot(page, "profile-parity-J4-02-legacy-personal");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-personal.png"),
      fullPage: true,
    });

    // ----- 4. "Pilot tab" view — the person License group --------------------
    // The License group (status3) holds the licence flags + licence number +
    // medical expiry dates — the exact field set J-4's Pilot tab maps to. UNLIKE
    // Masterdata/Communication, the License group has NO ng-init, so is-open is
    // undefined → it renders COLLAPSED (its fields are in the DOM but not
    // scrollable; the bare scrollIntoViewIfNeeded timed out, run 27039051676).
    // Expand it first, then scroll its anchors into view. The expiry-date fields
    // are ng-show-gated on the matching licence flag, so we anchor on the
    // always-present LicenceNumber + the glider-pilot flag + medical-class-2
    // expiry (all unconditionally rendered once the group is open).
    await expandGroupIfCollapsed(page, "LICENSE", "#LicenceNumber");
    const licenceNumber = page.locator("#LicenceNumber");
    await licenceNumber.scrollIntoViewIfNeeded();
    await licenceNumber.waitFor({ state: "visible", timeout: 15_000 });
    await expect(page.locator("#HasGliderPilotLicence")).toBeVisible();
    await expect(page.locator("#MedicalClass2ExpireDate")).toBeVisible();
    await screenshot(page, "profile-parity-J4-03-legacy-pilot");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-pilot.png"),
      fullPage: true,
    });

    // ----- 5. "Notifications tab" view — the Club-Settings notification flags -
    // The Club-Settings group (status4) holds the receive-* notification flags —
    // the field set J-4's Notifications tab maps to (the 3 toggles). Like the
    // License group, Club-Settings has NO ng-init → it renders COLLAPSED, so we
    // expand it before scrolling the notification checkboxes into view. (Legacy
    // mixes these with admin-only membership fields — memberNumber/memberState —
    // which AlpenFlight keeps admin-only and OFF the self-edit Notifications tab;
    // the side-by-side makes that scoping visible.)
    await expandGroupIfCollapsed(
      page,
      "CLUB_SETTINGS",
      "#ReceiveFlightReports",
    );
    const receiveFlightReports = page.locator("#ReceiveFlightReports");
    await receiveFlightReports.scrollIntoViewIfNeeded();
    await receiveFlightReports.waitFor({ state: "visible", timeout: 15_000 });
    await expect(
      page.locator("#ReceiveAircraftReservationNotifications"),
    ).toBeVisible();
    await expect(page.locator("#ReceivePlanningDayRoleReminder")).toBeVisible();
    await screenshot(page, "profile-parity-J4-04-legacy-notifications");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-notifications.png"),
      fullPage: true,
    });
  } finally {
    // No teardown of the Users.PersonId link is needed: the fan-out seeds the
    // FLSTest DB fresh per run, and we mutate nothing else. Leaving the link in
    // place is harmless (and avoids a second SQL round-trip that could race the
    // recording close).
  }

  // SELF-GUARD: all four gallery-declared parity PNGs MUST have landed. The
  // fan-out staging only DECLARES screenshots it can `find`, so a skipped-but-
  // expected capture would be silently absent from the gallery instead of red.
  // Asserting all four files here turns a missed capture into a loud failure of
  // THIS spec (non-blocking parity aid — it does not gate the AlpenFlight chain,
  // but it IS surfaced in the fan-out's final-status step). Mirrors J-2's guard.
  for (const png of [
    "legacy-profile-account.png",
    "legacy-profile-personal.png",
    "legacy-profile-pilot.png",
    "legacy-profile-notifications.png",
  ]) {
    expect(
      existsSync(testInfo.outputPath(png)),
      `expected parity screenshot ${png} to have been written to the test output dir — ` +
        `the fan-out gallery's J-4 legacy /profile parity half depends on it`,
    ).toBeTruthy();
  }
});
