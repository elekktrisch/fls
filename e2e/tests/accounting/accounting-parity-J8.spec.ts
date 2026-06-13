/**
 * J-8 T-18 — LEGACY half of the accounting-rule-filter side-by-side parity gallery.
 *
 * The "before" side of the J-8 side-by-side parity gallery, the legacy
 * counterpart to AlpenFlight's `real-idp/accounting-rules-parity.spec.ts` proof
 * videos + paired screenshots (those carry `proof-journey: J-8`). J-8 REPLACES
 * the legacy `masterdata/accountingRules/` screens; this spec captures the legacy
 * flsweb AccountingRuleFilter list + edit-form parity video + the paired
 * list / form screenshots so the operator can eyeball the legacy rule-filter
 * screens vs AlpenFlight's, side by side.
 *
 * The legacy stack (Mono `flsserver` + Node 8 `flsweb` + MSSQL) is nightly-
 * budget, so this lands in the fan-out workflow
 * (`.github/workflows/alpenflight-proof-fanout.yml`), NOT the PR gate —
 * mirroring exactly how J-0c captures its legacy Location video, J-1 its legacy
 * aircraft video, J-2 its legacy flight video, J-5 its legacy reservation video,
 * J-6 its legacy planning video, and J-7 its legacy reporting video
 * (`reporting-parity-J7.spec.ts`, the directly mirrored model here).
 *
 * What it shows (read-only walkthrough — the operator wants to compare SCREENS):
 *   1. logs in (as the TestClub admin, UI login — same as the J-0c..J-7 specs),
 *   2. seeds ONE article-target rule via the REST API (so the list is populated
 *      with ≥1 deterministic row and an edit-form is reachable — the form's
 *      $q.all loads 11 master-data lists, slow to drive by hand; the API seed is
 *      the same shape the SPA controller POSTs — see rules-edit.spec.ts),
 *   3. opens the list at `/masterdata/accountingRuleFilters` — the legacy
 *      ng-table (Active · Name · Description · Target · Type) and shoots
 *      `legacy-accountingrules-list.png`,
 *   4. opens that rule's edit form (`/masterdata/accountingRuleFilters/:id`) —
 *      the filter-type-driven legacy form (type selectize + the conditional
 *      article-target / aircraft-filter / recipient sections) and shoots
 *      `legacy-accountingrules-form.png`,
 *   5. records the whole flow as the legacy parity video,
 *   6. deletes the seeded rule (it matches all aircraft/start types and would
 *      otherwise apply to every glider flight in later specs).
 *
 * Legacy selectors (verified against flsweb `accountingRuleFilters-table.html`
 * + `accountingRuleFilters-edit.html`, reused from the existing legacy oracle
 * spec `e2e/tests/accounting/rules-edit.spec.ts`):
 *   - List:   `/masterdata/accountingRuleFilters`, rows `tbody [data-testid="row"]`.
 *   - Edit:   `/masterdata/accountingRuleFilters/:id`; readiness = the
 *             `fls-busy-indicator` backdrop (`[data-testid="busy-indicator"]`)
 *             cleared, then the stable `input#RuleFilterName` text field visible
 *             (a plain input, NOT the late-rendering type selectize).
 *   - Admin + password: `testclubadmin` / `s` (the TestClub `ClubAdministrator`,
 *     same credential the J-0c..J-7 specs use; `_test-fixture.sql`).
 *
 * STRUCTURAL STATUS (2026-06-13): the legacy stack does not run on the dev box
 * (Alpine/musl — no browser, no Mono/MSSQL). This spec is authored against the
 * REAL legacy selectors above and is structurally validated (tsc + `playwright
 * test --list` discovers it). Its FIRST LIVE green is the fan-out workflow run
 * that brings up the stack, runs it, and retains/publishes the video + paired
 * screenshots to the proof gallery under J-8 — the same first-green caveat the
 * J-0c..J-7 legacy specs + that workflow document. Until then the committed
 * legacy-reference `accounting/*.png` refs are absent and CI's `add_shot`
 * degrades to the AlpenFlight side (see
 * `alpenflight/web/e2e/legacy-reference/README.md`).
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
import { API_BASE, getBearerToken } from "../../test-data";

// Record the read-only walkthrough as the legacy parity video regardless of
// pass/fail. The fan-out workflow stages this artifact + publishes it to the
// proof gallery under J-8 (declared via the `--legacy-video` sidecar).
test.use({ video: "on" });

// The seeded TestClub administrator (role ClubAdministrator). Password is the
// single letter `s` (_test-fixture.sql convention) — same as J-0c..J-7.
const ADMIN = { username: "testclubadmin", password: "s" } as const;

// The rule-filter edit form fires 11 parallel master-data loads (persons,
// aircrafts, articles, …) that together can push past the default budget; the
// list page is also slow under accumulated DB load. Give the whole flow headroom.
test.setTimeout(180_000);

// Article 5001 = "Glider flight minutes" (_test-fixture.sql) — the seeded
// article we attach to the deterministic parity rule.
const ARTICLE_NUMBER = "5001";
// AccountingRuleFilterType.FlightTimeAccountingRuleFilter (drives the article-
// target + aircraft-filter conditional sections in the legacy edit form).
const RULE_TYPE_FLIGHTTIME = 30;

test("J-8 parity: legacy accounting-rule-filter list + edit form (parity video)", async ({
  browser,
}, testInfo) => {
  // Own recording context (the J-0c..J-7 specs' shape) so the video is one
  // continuous take of the list → edit-form walkthrough at a fixed viewport.
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    recordVideo: {
      dir: testInfo.outputPath("video"),
      size: { width: 1280, height: 800 },
    },
  });
  const page = await ctx.newPage();

  let seededId: string | undefined;

  try {
    await loginViaUi(page, ADMIN.username, ADMIN.password);
    await waitForLoggedInState(page);

    const token = await getBearerToken(page);
    const headers = {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    };

    // ----- 0. SEED one deterministic article-target rule via REST -------------
    // The SPA form's $q.all loads 11 master-data endpoints and is flaky to drive
    // by hand; the API POST is the same contract the controller hits. This
    // guarantees ≥1 populated list row + a reachable edit form regardless of
    // what else the seed left behind.
    const RULE_NAME = `J-8-parity ${Date.now().toString(36)}`;
    const articlesRes = await page.request.get(`${API_BASE}/api/v1/articles`, {
      headers,
    });
    const articles = articlesRes.ok()
      ? ((await articlesRes.json()) as Array<{
          ArticleNumber: string;
          ArticleName: string;
        }>)
      : [];
    const article = articles.find((a) => a.ArticleNumber === ARTICLE_NUMBER);

    const createPayload = {
      RuleFilterName: RULE_NAME,
      Description: "J-8 legacy parity rule (article target)",
      AccountingRuleFilterTypeId: RULE_TYPE_FLIGHTTIME,
      IsActive: true,
      IsRuleForGliderFlights: true,
      IsRuleForTowingFlights: false,
      IsRuleForMotorFlights: false,
      UseRuleForAllAircraftsExceptListed: true,
      MatchedAircraftImmatriculations: [],
      UseRuleForAllStartTypesExceptListed: true,
      MatchedStartTypes: [],
      UseRuleForAllFlightTypesExceptListed: true,
      MatchedFlightTypeCodes: [],
      UseRuleForAllStartLocationsExceptListed: true,
      MatchedStartLocations: [],
      UseRuleForAllLdgLocationsExceptListed: true,
      MatchedLdgLocations: [],
      UseRuleForAllClubMemberNumbersExceptListed: true,
      MatchedClubMemberNumbers: [],
      UseRuleForAllFlightCrewTypesExceptListed: true,
      MatchedFlightCrewTypes: [],
      UseRuleForAllAircraftsOnHomebaseExceptListed: true,
      MatchedAircraftsHomebase: [],
      UseRuleForAllMemberStatesExceptListed: true,
      MatchedMemberStates: [],
      UseRuleForAllPersonCategoriesExceptListed: true,
      MatchedPersonCategories: [],
      ArticleTarget: article
        ? {
            ArticleNumber: article.ArticleNumber,
            DeliveryLineText: article.ArticleName,
          }
        : undefined,
      AccountingUnitTypeId: 10, // Min — required for FlightTime rules.
      IsChargedToClubInternal: false,
    };
    const createRes = await page.request.post(
      `${API_BASE}/api/v1/accountingrulefilters`,
      { headers, data: createPayload },
    );
    if (createRes.ok()) {
      const created = (await createRes.json()) as {
        AccountingRuleFilterId: string;
      };
      seededId = created.AccountingRuleFilterId;
    } else {
      console.warn(
        `[J-8] parity rule seed failed (${createRes.status()}) — falling back to ` +
          "whatever rows the FLSTest seed left in the list",
      );
    }

    // ----- 1. LIST: the legacy rule-filter ng-table ---------------------------
    await gotoRoute(page, "/masterdata/accountingRuleFilters");
    await page
      .locator('tbody [data-testid="row"]')
      .first()
      .waitFor({ state: "visible", timeout: 60_000 });
    await screenshot(page, "accounting-parity-J8-01-legacy-list");
    // STABLE parity screenshot the fanout stages into the gallery (side=legacy,
    // view=list). FIXED basename `legacy-accountingrules-list.png` — it MUST
    // match the fanout's add_shot glob (alpenflight-proof-fanout.yml step 7,
    // "J-8 accounting parity SCREENSHOTS") + the J-8 expected-shots legacy:list
    // view, exactly as the AlpenFlight half writes
    // alpenflight-accountingrules-list.png. Captured AS SOON AS the list renders
    // (J-2 T-42: survive a partial red).
    await page.screenshot({
      path: testInfo.outputPath("legacy-accountingrules-list.png"),
      fullPage: true,
    });

    // ----- 2. EDIT FORM: the filter-type-driven legacy form -------------------
    // Best-effort per-shot (J-5 T-38 / J-2 T-42 / J-7): the edit form fires 11
    // parallel master-data loads, slow on the Mono/MSSQL stack. Guard in its own
    // try/catch so a hiccup drops ONLY this shot, not the list shot above.
    if (seededId) {
      try {
        await gotoRoute(page, `/masterdata/accountingRuleFilters/${seededId}`);
        // The edit form is wrapped in <fls-busy-indicator busy="busy"> and
        // $scope.busy stays true through loadMasterData() ($q.all of 11 parallel
        // reference loads — aircraft, start-types, flight-types, locations,
        // articles, persons, person-categories, crew-types, member-states,
        // unit-types) AND the rule fetch, only clearing in the controller's
        // .finally() (AccountingRuleFiltersEditController.js:103). gotoRoute's
        // shared busy-clear is a 30s ceiling — too short for this heavy load
        // under Mono/MSSQL worker contention — so wait again here with the
        // form's own 90s budget for busy to clear (display:none on the
        // [data-testid="busy-indicator"] backdrop), mirroring J-7's
        // "busy-spinner gone, then stable anchor" readiness pattern.
        await page.waitForFunction(
          () => {
            const spinners = Array.from(
              document.querySelectorAll('[data-testid="busy-indicator"]'),
            ) as HTMLElement[];
            return spinners.every((el) => {
              const r = el.getBoundingClientRect();
              return r.width === 0 && r.height === 0;
            });
          },
          undefined,
          { timeout: 90_000 },
        );
        // Anchor on the RuleFilterName text input — a plain <input id=...> with
        // no ng-if and no selectize transform, so it is a STABLE, reliably-
        // visible signal the populated form has rendered. (The old anchor,
        // `selectize#AccountingRuleFilterTypeId`, is the late-rendering selectize
        // widget the AngularJS directive rewrites; its 15s wait timed out and
        // the form PNG was never produced.) The seeded rule is FlightTime
        // (type 30), so targetTypeArticleVisible() is already true and the
        // conditional article-target section is in the shot.
        const nameInput = page.locator("input#RuleFilterName");
        await nameInput.waitFor({ state: "visible", timeout: 60_000 });
        await screenshot(page, "accounting-parity-J8-02-legacy-edit-form");
        await page.screenshot({
          path: testInfo.outputPath("legacy-accountingrules-form.png"),
          fullPage: true,
        });
      } catch (err) {
        console.warn(
          `[J-8] legacy edit-form capture skipped (slow/absent form): ${
            (err as Error).message
          }`,
        );
      }
    } else {
      console.warn(
        "[J-8] no seeded rule id — legacy edit-form shot skipped (the list pair stands)",
      );
    }

    // SELF-GUARD (J-2 T-42 / J-7): the LIST is the one load-bearing,
    // always-present legacy parity PNG (renders by route alone on a populated
    // seed) — assert it landed so a missed capture is a loud failure, not a
    // hidden gallery gap. The edit-form is BEST-EFFORT (its own try/catch above);
    // if absent the fanout's add_shot simply no-ops that entry.
    expect(
      existsSync(testInfo.outputPath("legacy-accountingrules-list.png")),
      "expected legacy parity screenshot legacy-accountingrules-list.png in the " +
        "test output dir — the fanout gallery's J-8 legacy half depends on it",
    ).toBeTruthy();
    if (!existsSync(testInfo.outputPath("legacy-accountingrules-form.png"))) {
      console.warn(
        "[J-8] best-effort legacy parity screenshot legacy-accountingrules-form.png " +
          "absent (slow/flaky legacy stack) — the gallery drops that one entry, the list pair stands",
      );
    }
  } finally {
    // Clean up the seeded rule — it would otherwise apply to glider flights in
    // later specs (matches all aircraft / start types).
    if (seededId) {
      try {
        const token = await getBearerToken(page);
        await page.request.post(
          `${API_BASE}/api/v1/accountingrulefilters/${seededId}`,
          {
            headers: {
              Authorization: `Bearer ${token}`,
              "X-HTTP-Method-Override": "DELETE",
            },
          },
        );
      } catch (err) {
        console.warn(
          `[J-8] afterAll cleanup: delete ${seededId} failed (${
            (err as Error).message
          })`,
        );
      }
    }
    await page.close();
    await ctx.close();
  }
});
