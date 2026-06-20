import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type TestInfo,
} from '@playwright/test';

import { enterViaNav } from '../_helpers/nav';

import { seedFlightMasterdata, type FlightMasterdata } from './_helpers/flight-parity-fixture';
import { proofVideo } from './_helpers/proof-video';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  resolveMigratedTestClubAdmin,
  loginAsMigratedTestClubAdmin,
  useRealBundle,
  type MigratedClubAdmin,
} from './_helpers/reservation-parity-fixture';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';

/**
 * J-9 — Delivery-creation-test harness real chain (live Keycloak auth + real
 * Spring backend + real Postgres). The journey's `parity_test` (the real-chain
 * done-bar) — proves the SACRED-COW rules engine end to end through its harness
 * screen. NO `page.route` mocking on any path: the engine pipeline, the
 * `@TenantId` filter, the CLUB_ADMINISTRATOR `@PreAuthorize` gate, the
 * `expected_delivery` jsonb capture + diff, and the dry-run/run endpoints all
 * run live over a DETERMINISTIC scenario this spec seeds through the real APIs.
 *
 * ── PRINCIPAL (CLUB_ADMINISTRATOR, every harness endpoint is admin-gated) ─────
 * Drives `clubadmin4` (V29 seed), a REAL CLUB_ADMINISTRATOR bound to seed-club-1
 * — NOT the mock-admin everything-principal that hides a role-authz gap
 * ([[project_real_idp_real_roles_catches_authz_gaps]]). The cross-tenant 404
 * probe drives a REAL second club + admin (`provisionTwoClubs`).
 *
 * ── DETERMINISTIC SCENARIO (mirrors DeliveryCreationTestRunIT, over REST) ─────
 * The engine output must be reproducible, so the spec CREATES the inputs as
 * seed-club-1 through the real APIs (no reliance on whatever the fanout migrated):
 *   - the masterdata (`seedFlightMasterdata`: aircraft / pilot / flight-type /
 *     location);
 *   - a GLIDER Flight (90-min duration, 1 landing, the pilot crew);
 *   - two AccountingRuleFilters via the J-8 API — a FlightTime line (legacyId 30,
 *     glider-scoped, min=0 ⇒ bills the whole duration) emitting `ART-FT`, and a
 *     LandingTax line (legacyId 60) emitting `ART-LT`.
 * The engine then produces a DETERMINISTIC two-item delivery `[ART-FT, ART-LT]`,
 * so the dry-run fill, the SUCCESS run, and the perturbed FAILURE diff are stable.
 *
 * The ignore flags `ignoreDeliveryInformation` / `ignoreAdditionalInformation` +
 * the four recipient-ignore flags ride the create (the T-12 DeliveryDetailsStage
 * deferral leaves those snapshot fields null — without the ignores a run fails on
 * them). The dry-run capture PRECEDES save (the T-21 captureExpected contract: a
 * saved harness persists the expected set the just-captured dry-run produced).
 *
 * ── REAL-IDP HYGIENE (hard-won) ──────────────────────────────────────────────
 *   - ENTER via the masterdata nav dropdown (`enterViaNav`), NOT a bare goto for
 *     the chrome-reachability assertion;
 *   - prefer WARM in-app navigation; do NOT `clearCookies` (kills session
 *     restore) and avoid a cold `page.goto` reopen mid-flow where a warm nav
 *     works ([[project_real_idp_goto_reboot_renew_stall]]);
 *   - read a created id off the 201 `Location` header / a re-GET, never the POST
 *     body (the SPA list re-fetch evicts it,
 *     [[project_spa_nav_evicts_post_response_body]]);
 *   - track every created harness id + `afterAll` DELETE it so a Playwright retry
 *     starts on a clean seed-club-1 (the shared, never-truncated seed tenant).
 */

const DCT = '/api/v1/deliverycreationtests';
const FILTERS = '/api/v1/accounting-rule-filters';
const FLIGHTS = '/api/v1/flights';

const FILTER_TYPE_FLIGHT_TIME = '019e2e15-2c00-7652-8000-000000004652';
const FILTER_TYPE_LANDING_TAX = '019e2e15-2c00-7655-8000-000000004655';
const UNIT_MINUTES = '019e2e15-2c00-7a38-8000-000000004a38';
const UNIT_LANDINGS = '019e2e15-2c00-7a3a-8000-000000004a3a';
const LEGACY_FLIGHT_TIME = 30;
const LEGACY_LANDING_TAX = 60;

const CREW_TYPE_PILOT = '019e2e15-2c00-76b0-8000-0000000036b0';

const FT_ARTICLE = 'ART-FT';
const LT_ARTICLE = 'ART-LT';

interface DeliveryItem {
  position?: number;
  articleNumber?: string;
  itemText?: string;
  quantity?: number;
  unitType?: string;
}

interface MigratedFlightItem {
  id: string;
  flightAircraftType?: string;
}

interface DctDetail {
  id: string;
  expectedDelivery: { items?: DeliveryItem[] };
  expectedMatchedFilterIds: string[];
  lastTestSuccessful?: boolean | null;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/** All 9 boolean flags present (FAIL_ON_NULL_FOR_PRIMITIVES) + the rule scope. */
function filterConfig(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    isRuleForGliderFlights: false,
    isRuleForTowingFlights: false,
    isRuleForMotorFlights: false,
    noLandingTaxForGlider: false,
    noLandingTaxForTowingAircraft: false,
    noLandingTaxForAircraft: false,
    includeFlightTypeName: false,
    extendMatchingFlightTypeCodesToGliderAndTowFlight: false,
    includeThresholdText: false,
    ...extra,
  };
}

/** Create an AccountingRuleFilter via the real J-8 API; return its bare UUID. */
async function createFilter(
  api: APIRequestContext,
  bearer: string,
  created: string[],
  body: Record<string, unknown>,
): Promise<string> {
  const res = await api.post(FILTERS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: body,
  });
  expect(res.status(), `filter create must 201 — got ${res.status()}: ${await res.text()}`).toBe(
    201,
  );
  const location = res.headers()['location'];
  expect(location, 'filter create must return a 201 Location header').toBeTruthy();
  const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
  created.push(id);
  return id;
}

/**
 * Seed the deterministic engine inputs as seed-club-1: the masterdata, a glider
 * Flight (90-min, 1 landing, pilot crew), and the FlightTime + LandingTax line
 * filters. Returns the created Flight's id + the two filter ids.
 */
async function seedScenario(
  api: APIRequestContext,
  bearer: string,
  createdFilters: string[],
): Promise<{
  flightId: string;
  ftFilterId: string;
  ltFilterId: string;
  ltWrite: Record<string, unknown>;
}> {
  const md: FlightMasterdata = await seedFlightMasterdata(api, bearer);

  const flightRes = await api.post(FLIGHTS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightAircraftType: 'GLIDER',
      aircraftId: md.gliderAircraftId,
      flightDate: '2026-05-15',
      startDateTime: '2026-05-15T08:00:00Z',
      ldgDateTime: '2026-05-15T09:30:00Z',
      startLocationId: md.locationId,
      ldgLocationId: md.locationId,
      flightTypeId: md.gliderFlightTypeId,
      nrOfLdgs: 1,
      nrOfLdgsOnStartLocation: 1,
      noStartTimeInformation: false,
      noLdgTimeInformation: false,
      isSoloFlight: false,
      crew: [{ personId: md.pilotPersonId, flightCrewTypeId: CREW_TYPE_PILOT }],
    },
  });
  expect(
    flightRes.status(),
    `flight create must 201 — got ${flightRes.status()}: ${await flightRes.text()}`,
  ).toBe(201);
  const flightLoc = flightRes.headers()['location']!;
  const flightId = new URL(flightLoc, 'http://localhost').pathname.split('/').pop()!;

  // min=0 ⇒ the FlightTime loop bills the whole active duration as one item.
  const ftFilterId = await createFilter(api, bearer, createdFilters, {
    filterTypeId: FILTER_TYPE_FLIGHT_TIME,
    filterTypeLegacyId: LEGACY_FLIGHT_TIME,
    accountingUnitTypeId: UNIT_MINUTES,
    ruleFilterName: `J-9 FlightTime ${Date.now().toString(36)}`,
    active: true,
    articleNumber: FT_ARTICLE,
    deliveryLineText: 'Flugzeit',
    filterConfig: filterConfig({ isRuleForGliderFlights: true }),
  });
  const ltWrite: Record<string, unknown> = {
    filterTypeId: FILTER_TYPE_LANDING_TAX,
    filterTypeLegacyId: LEGACY_LANDING_TAX,
    accountingUnitTypeId: UNIT_LANDINGS,
    ruleFilterName: `J-9 LandingTax ${Date.now().toString(36)}`,
    active: true,
    articleNumber: LT_ARTICLE,
    deliveryLineText: 'Landetaxe',
    filterConfig: filterConfig({ isRuleForGliderFlights: true }),
  };
  const ltFilterId = await createFilter(api, bearer, createdFilters, ltWrite);

  return { flightId, ftFilterId, ltFilterId, ltWrite };
}

function articleNumbers(items: DeliveryItem[] | undefined): string[] {
  return (items ?? []).map((i) => i.articleNumber ?? '').sort();
}

// ===========================================================================
// The real chain: nav-entry + list → author a harness (pick flight → dry-run
// fills the expected set → save) → run SUCCESS + matched-rule link to J-8 → a
// perturbed run FAILURE + the cell-level diff → cross-tenant 404.
// ===========================================================================
test.describe('Delivery creation test harness — rules-engine real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the @TenantId club). */
  let adminBearer: string;
  /** A real second club + admin (club B) — the cross-tenant-404 probe. */
  let twoClubs: TwoClubFixture;
  let scenario: {
    flightId: string;
    ftFilterId: string;
    ltFilterId: string;
    ltWrite: Record<string, unknown>;
  };
  /** Filters created in seed-club-1 — deleted in afterAll (retry-isolation). */
  const createdFilters: string[] = [];
  /** Harness ids created in seed-club-1 — deleted in afterAll. */
  const createdHarnesses: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdFilters.length = 0;
    createdHarnesses.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    twoClubs = await provisionTwoClubs(browser, baseURL, 'dct');
    cleanupCtx = await browser.newContext({ baseURL });
    scenario = await seedScenario(cleanupCtx.request, adminBearer, createdFilters);
  });

  // ux_dct_club_flight_partial permits one live harness per (club, flight), and
  // every test authors on the one shared seeded flight — so each must drop its
  // harness before the next creates, else the second create 409s.
  test.afterEach(async () => {
    for (const id of createdHarnesses) {
      await cleanupCtx.request
        .delete(`${DCT}/${id}`, { headers: { authorization: adminBearer } })
        .catch((err) => console.warn(`[J-9] harness cleanup ${id}: ${(err as Error).message}`));
    }
    createdHarnesses.length = 0;
  });

  test.afterAll(async () => {
    for (const id of createdFilters) {
      await cleanupCtx.request
        .delete(`${FILTERS}/${id}`, { headers: { authorization: adminBearer } })
        .catch((err) => console.warn(`[J-9] filter cleanup ${id}: ${(err as Error).message}`));
    }
    await cleanupCtx?.close();
    await twoClubs?.dispose();
  });

  test('[happy] author a harness: nav → pick flight → dry-run fills the tiered set → save → run SUCCESS + matched-rule link to J-8', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // ENTER via the chrome nav (the chrome-reachability AC): open the Masterdata
      // dropdown, click the nested harness entry, the list renders.
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliverycreationtests');
      await expect(page).toHaveURL('/deliverycreationtests');
      await expect(page.getByTestId('dct-table')).toBeVisible();

      // New harness → pick the seeded flight → "Create test delivery" DRY-RUNS the
      // engine (no persist) and fills the expected DeliveryItem set. The engine
      // ran live over the seeded flight + the two J-8 filters → the two line items.
      await page.getByTestId('dct-new-button').locator('button').click();
      await expect(page).toHaveURL('/deliverycreationtests/new');
      await page
        .getByTestId('dct-name')
        .locator('input')
        .fill('Glider — flight time + landing tax');
      await page.getByTestId('dct-flight-picker').selectOption(scenario.flightId);

      await page.getByTestId('dct-create-test-delivery').locator('button').click();
      await expect(page.getByTestId('dct-expected-item-0')).toBeVisible();
      await expect(page.getByTestId('dct-expected-item-1')).toBeVisible();
      const dryRunText = await page.getByTestId('dct-expected-section').innerText();
      expect(dryRunText).toContain(FT_ARTICLE);
      expect(dryRunText).toContain(LT_ARTICLE);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-dct-dry-run.png`,
        fullPage: true,
      });

      // Save: the captured dry-run rides the create as the expected set (T-21).
      // Read the created id off the 201 Location (the list re-fetch evicts the POST
      // body) by watching the create response.
      const createResp = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === DCT &&
          r.status() === 201,
      );
      await page.getByTestId('dct-save-button').locator('button').click();
      const created = await createResp;
      const harnessId = new URL(created.headers()['location']!, 'http://localhost').pathname
        .split('/')
        .pop()!;
      createdHarnesses.push(harnessId);
      await expect(page).toHaveURL('/deliverycreationtests');

      // The harness round-trips its persisted expected set (re-GET, not the POST
      // body): the two line items + their matched filter ids.
      const detail = (await ctx.request
        .get(`${DCT}/${harnessId}`, { headers: { authorization: adminBearer } })
        .then((r) => r.json())) as DctDetail;
      expect(articleNumbers(detail.expectedDelivery.items)).toEqual([FT_ARTICLE, LT_ARTICLE]);
      expect(detail.expectedMatchedFilterIds.length).toBe(2);

      // Run the harness: the engine re-runs vs the stored expectation → SUCCESS,
      // and each matched AccountingRuleFilter id links into the J-8 rule editor.
      await page.getByTestId(`dct-row-${harnessId}`).click();
      await expect(page).toHaveURL(new RegExp(`/deliverycreationtests/${harnessId}/edit$`));
      await expect(page.getByTestId('dct-edit-form')).toBeVisible();
      await page.getByTestId('dct-run').locator('button').click();
      await expect(page.getByTestId('dct-result')).toContainText('Success');
      const matchedLink = page.getByTestId(`dct-matched-rule-${scenario.ftFilterId}`);
      await expect(matchedLink).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-dct-run-success.png`,
        fullPage: true,
      });

      await matchedLink.click();
      await expect(page).toHaveURL(`/accountingrules/${scenario.ftFilterId}/edit`);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-9',
        caption:
          'J-9 · delivery creation test · a club admin logs in via real Keycloak, ENTERS via the ' +
          'masterdata nav, authors a harness over a seeded glider flight + two J-8 rule filters — ' +
          '"Create test delivery" DRY-RUNS the rules engine (no persist) and fills the expected ' +
          'DeliveryItem set, Save persists it, "Run test" re-runs the engine → SUCCESS, and a ' +
          'matched AccountingRuleFilter link opens the J-8 rule editor (the sacred-cow engine ' +
          'proven end to end over the real stack)',
        acTag: 'happy',
      });
    }
  });

  test('[key-error] a rule change makes the engine output diverge → run FAILURE + the cell-level diff', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Author a fresh harness capturing the current engine output (the SUCCESS
      // baseline) through the real chain.
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliverycreationtests');
      await expect(page).toHaveURL('/deliverycreationtests');
      await page.getByTestId('dct-new-button').locator('button').click();
      await expect(page).toHaveURL('/deliverycreationtests/new');
      await page.getByTestId('dct-name').locator('input').fill('Glider — perturbed rule');
      await page.getByTestId('dct-flight-picker').selectOption(scenario.flightId);
      await page.getByTestId('dct-create-test-delivery').locator('button').click();
      await expect(page.getByTestId('dct-expected-item-1')).toBeVisible();

      const createResp = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === DCT &&
          r.status() === 201,
      );
      await page.getByTestId('dct-save-button').locator('button').click();
      const created = await createResp;
      const harnessId = new URL(created.headers()['location']!, 'http://localhost').pathname
        .split('/')
        .pop()!;
      createdHarnesses.push(harnessId);
      await expect(page).toHaveURL('/deliverycreationtests');

      // The operator's daily rule-tuning move: change a rule so the engine now
      // emits a DIFFERENT line for the landing tax — a real divergence the harness
      // must catch (not a DB tamper). Re-point the LandingTax filter to a new
      // article number via the real J-8 PUT (the write-request shape, not the
      // detail projection — the detail omits the required filterTypeLegacyId).
      const putRes = await ctx.request.put(`${FILTERS}/${scenario.ltFilterId}`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { ...scenario.ltWrite, articleNumber: 'ART-LT-CHANGED' },
      });
      expect(putRes.status(), `filter PUT must 200 — got ${putRes.status()}`).toBe(200);

      // Run: the engine now produces ART-LT-CHANGED, diverging from the stored
      // expectation (ART-LT) → FAILURE with a cell-level diff on the changed item.
      await page.getByTestId(`dct-row-${harnessId}`).click();
      await expect(page.getByTestId('dct-edit-form')).toBeVisible();
      await page.getByTestId('dct-run').locator('button').click();
      await expect(page.getByTestId('dct-result')).toContainText('Failure');
      await expect(page.getByTestId('dct-diff')).toBeVisible();
      await expect(page.getByTestId('dct-diff')).toContainText('ART-LT-CHANGED');

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-dct-run-failure-diff.png`,
        fullPage: true,
      });
    } finally {
      // Restore the LandingTax filter so the SUCCESS test stays order-independent.
      await ctx.request
        .put(`${FILTERS}/${scenario.ltFilterId}`, {
          headers: { authorization: adminBearer, 'content-type': 'application/json' },
          data: scenario.ltWrite,
        })
        .catch((err) => console.warn(`[J-9] filter restore: ${(err as Error).message}`));
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-9',
        caption:
          'J-9 · delivery creation test · the operator changes a rule (the LandingTax filter article) ' +
          'so the engine output diverges from the harness’s stored expectation — "Run test" returns ' +
          'FAILURE and the cell-level diff surfaces the differing DeliveryItem (the daily ' +
          'rule-tuning tool, all over the real engine + real diff)',
        acTag: 'key-error',
      });
    }
  });

  test('[edge] tenant isolation: club B cannot read seed-club-1’s harness (cross-tenant GET → 404)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // Author a harness as seed-club-1 (clubadmin4 / adminBearer) directly over
      // the REST API — the dry-run fills + the write-request captures the expected
      // set (the same chain the UI drives).
      const example = await ctx.request
        .get(`${DCT}/example/${scenario.flightId}`, { headers: { authorization: adminBearer } })
        .then((r) => r.json());
      const createRes = await ctx.request.post(DCT, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          flightId: scenario.flightId,
          testName: `J-9 tenant harness ${Date.now().toString(36)}`,
          active: true,
          expectedDelivery: example.delivery,
          expectedMatchedFilterIds: example.matchedFilterIds,
          ignoreDeliveryInformation: true,
          ignoreAdditionalInformation: true,
          ignoreRecipientName: true,
          ignoreRecipientAddress: true,
          ignoreRecipientPersonId: true,
          ignoreRecipientClubMemberNumber: true,
        },
      });
      expect(createRes.status()).toBe(201);
      const id = new URL(createRes.headers()['location']!, 'http://localhost').pathname
        .split('/')
        .pop()!;
      createdHarnesses.push(id);

      // Capture club B's admin Bearer through real Keycloak (a DIFFERENT tenant).
      const bCtx = await browser.newContext({ baseURL });
      const bPage = await bCtx.newPage();
      let clubBBearer: string;
      try {
        const reqPromise = bPage.waitForRequest(
          (req) =>
            req.url().includes('/api/v1/') &&
            typeof req.headers()['authorization'] === 'string' &&
            /^Bearer /i.test(req.headers()['authorization']!),
        );
        await loginAsClubAdmin(bPage, twoClubs.clubB);
        await bPage.goto('/deliverycreationtests');
        clubBBearer = (await reqPromise).headers()['authorization']!;
      } finally {
        await bCtx.close();
      }

      // seed-club-1's admin reads it fine (the positive control — the id is valid).
      const ownRead = await ctx.request.get(`${DCT}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own harness').toBe(200);

      // Club B's admin CANNOT read it — the @TenantId-scoped finder never returns
      // another club's row → 404 (NOT 403; the row is invisible, not forbidden).
      const crossTenant = await ctx.request.get(`${DCT}/${id}`, {
        headers: { authorization: clubBBearer },
      });
      expect(
        crossTenant.status(),
        `club B must NOT read club A's harness (cross-tenant 404) — got ${crossTenant.status()}`,
      ).toBe(404);

      // The run endpoint is tenant-scoped too — club B's run → 404, not a leak.
      const crossRun = await ctx.request.post(`${DCT}/${id}/run`, {
        headers: { authorization: clubBBearer },
      });
      expect(crossRun.status(), 'club B cannot run club A’s harness').toBe(404);
    } finally {
      await ctx.close();
    }
  });
});

// ===========================================================================
// MIGRATED-DATA real chain — the engine done-bar over migrated J-2 flights +
// J-8 filters (S-107). The fanout's REAL legacy export migrates the WHOLE
// TestClub base seed, including the HB-3256 static-seed glider flight and the
// full set of FlightTime + LandingTax AccountingRuleFilters. Running the harness
// dry-run over that migrated flight must drive the migrated filter set through
// the engine → its genuine delivery (the FlightTime + LandingTax lines) —
// proving producer-bound J-8 filters + J-2 flights reach the rules engine end to
// end, no new mapper. Rides the SAME real bundle the J-0c fan-out spec ingests;
// runs only when the fanout's real export ran.
// ===========================================================================
// HB-3256 is the unique-immatriculation static legacy-seed glider (`6 Insert Test
// Flights.sql:138`, FlightAircraftType glider, LdgDateTime=DATEADD(n,22,start)).
// Driven over the REAL migrated bundle, the migrated FlightTime (5001, with T-01's
// deliveryLineText) + LandingTax (6001) filters produce its genuine delivery — assert
// that exact pair (the migration promise: a migrated club bills its own data correctly).
const MIGRATED_FT_ARTICLE = '5001';
const MIGRATED_FT_ITEM_TEXT = 'HB-3256 Glider flight minutes';
const MIGRATED_FT_EXPECTED_QTY = 22;
const MIGRATED_FT_UNIT = 'Minuten';

const MIGRATED_LT_ARTICLE = '6001';
const MIGRATED_LT_ITEM_TEXT = 'Landegebuehr LSZK';
const MIGRATED_LT_EXPECTED_QTY = 2;
const MIGRATED_LT_UNIT = 'Landung';

test.describe('Delivery creation test harness — migrated inputs drive the engine (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  test.skip(
    !useRealBundle(),
    'the migrated-input engine run requires the real legacy export (J5_BUNDLE_SOURCE=real, fanout only)',
  );

  let baseURL: string;
  let migratedAdmin: MigratedClubAdmin;
  let migratedBearer: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // The migrated CLUB gets a fresh provisioned UUID, so resolve the loginable
    // admin by OWNERSHIP (the J-5/J-8 migrated-read pattern) — it owns the same
    // migrated TestClub the static-seed glider flights + filters reconciled onto.
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL);
    migratedAdmin = resolved.admin;
    migratedBearer = resolved.bearer;
  });

  test('[migration/parity] the migrated HB-3256 glider flight + the migrated filter set drive the engine → its genuine FlightTime + LandingTax delivery', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

      // List the migrated TestClub's glider flights. The HB-3256 static-seed
      // glider is among them; the engine dry-run over it must drive the migrated
      // filter set to its genuine FlightTime + LandingTax delivery.
      const flightsRes = await ctx.request.get(`${FLIGHTS}?limit=200`, {
        headers: { authorization: migratedBearer },
      });
      expect(flightsRes.status(), 'the migrated TestClub lists its migrated flights').toBe(200);
      const flightItems =
        ((await flightsRes.json()) as { items?: MigratedFlightItem[] }).items ?? [];
      const gliderFlights = flightItems.filter((f) => f.flightAircraftType === 'GLIDER');
      expect(
        gliderFlights.length,
        `the migrated TestClub must carry the migrated HB-3256 glider flight — ` +
          `got ${flightItems.length} flight(s)`,
      ).toBeGreaterThan(0);

      // Dry-run the engine (GET example, no persist) over each migrated glider and
      // pin the HB-3256 one by a STABLE attribute — the FlightTime line's itemText
      // (the immatriculation-bearing deliveryLineText), NOT the fresh migrated
      // UUID. Other migrated gliders (the two HB-3407 flights) carry a different
      // FlightTime itemText + quantity, so this never false-matches them.
      let hb3256Items: DeliveryItem[] | undefined;
      let hb3256FlightId: string | undefined;
      for (const flight of gliderFlights) {
        const exampleRes = await ctx.request.get(`${DCT}/example/${flight.id}`, {
          headers: { authorization: migratedBearer },
        });
        if (exampleRes.status() !== 200) {
          continue;
        }
        const items =
          ((await exampleRes.json()) as { delivery?: { items?: DeliveryItem[] } }).delivery
            ?.items ?? [];
        if (
          items.some(
            (i) => i.articleNumber === MIGRATED_FT_ARTICLE && i.itemText === MIGRATED_FT_ITEM_TEXT,
          )
        ) {
          hb3256Items = items;
          hb3256FlightId = flight.id;
          break;
        }
      }

      expect(
        hb3256Items,
        `the engine, run over the MIGRATED HB-3256 glider flight, must emit its genuine FlightTime line ` +
          `(article ${MIGRATED_FT_ARTICLE}, "${MIGRATED_FT_ITEM_TEXT}") — proving the migrated J-8 filters + ` +
          `J-2 flight drive the rules engine end to end over genuine legacy seed. A missing line after the ` +
          `deployment is COMPLETED (the shared-bundle ingest polls to terminal before this read) means the ` +
          `migrated filters did not match the migrated flight, not a read race.`,
      ).toBeTruthy();

      // The genuine migrated delivery: the 5001 FlightTime line (HB-3256 glider is
      // DATEADD(n,22,start) ⇒ 22 'Minuten') + the 6001 LandingTax line (1 ldg at
      // LSZK ⇒ 2 'Landung'). Both filters drive the engine over genuine seed.
      const ftLine = hb3256Items!.find(
        (i) => i.articleNumber === MIGRATED_FT_ARTICLE && i.itemText === MIGRATED_FT_ITEM_TEXT,
      );
      expect(ftLine!.quantity).toBe(MIGRATED_FT_EXPECTED_QTY);
      expect(ftLine!.unitType).toBe(MIGRATED_FT_UNIT);

      const ltLine = hb3256Items!.find(
        (i) => i.articleNumber === MIGRATED_LT_ARTICLE && i.itemText === MIGRATED_LT_ITEM_TEXT,
      );
      expect(
        ltLine,
        `the HB-3256 delivery must carry the migrated LandingTax line (article ${MIGRATED_LT_ARTICLE}, ` +
          `"${MIGRATED_LT_ITEM_TEXT}")`,
      ).toBeTruthy();
      expect(ltLine!.quantity).toBe(MIGRATED_LT_EXPECTED_QTY);
      expect(ltLine!.unitType).toBe(MIGRATED_LT_UNIT);

      // RENDER the migrated delivery on screen (the gallery video proof): drive
      // the harness dry-run UI over the migrated HB-3256 flight, mirroring the
      // clean-seed block. The picker is sourced from the SAME @TenantId flights
      // read the loop paged, so the migrated flight is selectable; "Create test
      // delivery" dry-runs the migrated filter set through the engine and renders
      // its genuine FlightTime + LandingTax lines — what the video must film,
      // not the empty stored-runs list (the migrated TestClub has none).
      await page.goto('/deliverycreationtests?lang=en');
      await page.getByTestId('dct-new-button').locator('button').click();
      await expect(page).toHaveURL('/deliverycreationtests/new');
      await page
        .getByTestId('dct-name')
        .locator('input')
        .fill('Migrated HB-3256 — flight time + landing tax');
      await page.getByTestId('dct-flight-picker').selectOption(hb3256FlightId!);

      await page.getByTestId('dct-create-test-delivery').locator('button').click();
      await expect(page.getByTestId('dct-expected-item-0')).toBeVisible();
      await expect(page.getByTestId('dct-expected-item-1')).toBeVisible();
      const dryRunText = await page.getByTestId('dct-expected-section').innerText();
      expect(dryRunText).toContain(MIGRATED_FT_ITEM_TEXT);
      expect(dryRunText).toContain(MIGRATED_LT_ARTICLE);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-dct-migrated-inputs.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-27',
        caption:
          'J-27 · migration-fidelity · the rules engine, run over the MIGRATED HB-3256 static-seed ' +
          'glider flight + the migrated filter set, produces its genuine delivery (the 5001 FlightTime ' +
          'line, 22 Minuten + the 6001 LandingTax line, 2 Landung) — the producer-bound filters + flight ' +
          'drive the sacred-cow engine end to end over real migrated legacy seed, the migration promise ' +
          'made true: a migrated club sees its own real legacy data, correctly',
        acTag: 'happy',
      });
    }
  });
});
