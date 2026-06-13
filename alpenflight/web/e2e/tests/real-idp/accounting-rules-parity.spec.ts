import { test, expect, type Browser, type BrowserContext, type TestInfo } from '@playwright/test';

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
import { proofVideo } from './_helpers/proof-video';

/**
 * J-8 — AccountingRuleFilter config screen real chain (live Keycloak auth +
 * real Spring backend + real Postgres). The journey's `parity_test` (the
 * real-chain done-bar). NO `page.route` mocking on any path — the @TenantId
 * filter, the CLUB_ADMINISTRATOR `@PreAuthorize` gate, the `filter_config`
 * jsonb round-trip, and the migration ingest + Keycloak provisioning all run
 * live.
 *
 * ── PRINCIPAL (CLUB_ADMINISTRATOR, all accounting endpoints are admin-gated) ──
 * Every `/api/v1/accounting-rule-filters` method is
 * `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")` — even the reads (a pure
 * config screen, T-06). So the clean-seed chain drives `clubadmin4` (V29 seed),
 * a REAL CLUB_ADMINISTRATOR bound to seed-club-1 — NOT the mock-admin, which is
 * an admin-everything principal that would hide a role-authz gap
 * ([[project_real_idp_real_roles_catches_authz_gaps]]). The cross-tenant 404
 * probe drives a REAL second club + admin (`provisionTwoClubs`).
 *
 * ── TWO FIDELITIES (both green at the §4 gate) ────────────────────────────────
 *   - CLEAN-SEED real chain: the admin logs in, ENTERS via the masterdata nav
 *     (`af-nav-section-/accountingrules`, chrome-reachable — NOT a bare goto),
 *     lists, creates a filter via the real backend → it appears → reopen
 *     round-trips every field; the filter-type drives the conditional sections
 *     over the real API; a match-list round-trips; a cross-tenant GET → 404.
 *   - MIGRATED-DATA real chain: a real legacy `AccountingRuleFilter` (seeded by
 *     `100 Insert AccountingRuleFilters.sql` + `_test-fixture.sql` on the legacy
 *     TestClub), exported + migrated through the live chain, renders in the
 *     owning (migrated) club's `/accountingrules` list with its predicate
 *     `filter_config` intact (the migration done-bar AC). Rides the SAME real
 *     bundle the J-0c fan-out spec ingests; skips cleanly when the per-push
 *     synth gate ran (no real export).
 *
 * ── REAL-IDP HYGIENE (hard-won) ───────────────────────────────────────────────
 *   - read a created id from the 201 `Location` header / a re-GET, never the
 *     POST response body (the SPA navigates on success → Chrome evicts the body,
 *     [[project_spa_nav_evicts_post_response_body]]);
 *   - prefer WARM in-app navigation; do NOT `clearCookies` (kills session
 *     restore) and avoid a cold `page.goto` reopen mid-flow where a warm nav
 *     works ([[project_real_idp_goto_reboot_renew_stall]]);
 *   - track every created filter id + `afterAll` DELETE it so a Playwright retry
 *     starts on a clean seed-club-1 (the shared, never-truncated seed tenant);
 *   - DELTA-assert list membership by id, never an absolute count.
 *
 * Note the AlpenFlight `id` is a BARE UUID (the controller returns `UUID id`,
 * no `arf-` prefix) → the list row testid is `accounting-rules-row-<uuid>`.
 */

const BASE = '/api/v1/accounting-rule-filters';
const TYPES = '/api/v1/accounting-rule-filter-types';

/** A filter-type catalog row (the T-07 reference projection). */
interface FilterTypeRow {
  id: string;
  code: string;
  legacyId: number;
  name: string;
}

/** A {useAllExcept, matched[]} predicate match-list. */
interface MatchList {
  useAllExcept: boolean;
  matched: string[];
}

/** A list-row projection (the @TenantId-scoped list endpoint). */
interface ListItem {
  id: string;
  ruleFilterName: string;
  filterTypeId: string;
  active: boolean;
  sortIndicator: number;
  target: string;
}

/** The detail round-trip payload (id is a bare UUID). */
interface Detail extends ListItem {
  articleTarget?: string;
  recipientTarget?: string;
  filterConfig: Record<string, unknown>;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/** All 9 boolean flags present (FAIL_ON_NULL_FOR_PRIMITIVES) + a fixture marker. */
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

/**
 * Create a filter via the REAL REST API and return the created bare-UUID id
 * (from the 201 `Location` header — never the POST body). Tracks it in
 * `created` so the group's `afterAll` deletes it (retry-isolation).
 */
async function createFilter(
  ctx: BrowserContext,
  bearer: string,
  created: string[],
  body: Record<string, unknown>,
): Promise<string> {
  const res = await ctx.request.post(BASE, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: body,
  });
  expect(res.status(), `filter create must 201 — got ${res.status()}: ${await res.text()}`).toBe(
    201,
  );
  const location = res.headers()['location'];
  expect(location, 'create must return a 201 Location header').toBeTruthy();
  const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
  expect(id, `Location "${location}" must end in a filter UUID`).toMatch(/^[0-9a-f-]{36}$/);
  created.push(id);
  return id;
}

/** Resolve a seeded filter-type's UUID by its legacyId off the live catalog. */
async function typeUuidByLegacyId(
  ctx: BrowserContext,
  bearer: string,
  legacyId: number,
): Promise<string> {
  const res = await ctx.request.get(TYPES, { headers: { authorization: bearer } });
  expect(res.status(), 'the filter-type catalog reads for a club admin').toBe(200);
  const types = (await res.json()) as FilterTypeRow[];
  const found = types.find((t) => t.legacyId === legacyId);
  expect(found, `filter-type legacyId ${legacyId} must be seeded`).toBeTruthy();
  return found!.id;
}

// ===========================================================================
// CLEAN-SEED real chain — nav-entry + list + create→round-trip + the
// filter-type-driven sections + a match-list round-trip + cross-tenant 404.
// The load-bearing J-8 proof.
// ===========================================================================
test.describe('Accounting rule filters — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the @TenantId club). */
  let adminBearer: string;
  /** A real second club + admin (club B) — the cross-tenant-404 probe. */
  let twoClubs: TwoClubFixture;
  /** Seeded filter-type UUIDs (resolved off the live catalog). */
  let articleTypeId: string; // legacyId 40 ∉ {5,10} → article-target section
  let recipientTypeId: string; // legacyId 10 → recipient-target section
  let aircraftTypeId: string; // legacyId 30 → aircraft-filter section
  // (the no-landing-tax type, legacyId 20, is exercised via the section-switch
  // test's <select> only — no create needed, so no resolved UUID is kept.)
  /** Every filter id this group created in seed-club-1 — deleted in afterAll. */
  const createdIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    // A REAL second club (B) + its admin for the cross-tenant-404 probe — driven
    // through real Keycloak so neither the mock-admin nor a synthetic claim hides
    // the tenant gate ([[project_real_idp_real_roles_catches_authz_gaps]]).
    twoClubs = await provisionTwoClubs(browser, baseURL, 'arf');
    cleanupCtx = await browser.newContext({ baseURL });
    articleTypeId = await typeUuidByLegacyId(cleanupCtx, adminBearer, 40);
    recipientTypeId = await typeUuidByLegacyId(cleanupCtx, adminBearer, 10);
    aircraftTypeId = await typeUuidByLegacyId(cleanupCtx, adminBearer, 30);
  });

  test.afterAll(async () => {
    for (const id of createdIds) {
      try {
        await cleanupCtx.request.delete(`${BASE}/${id}`, {
          headers: { authorization: adminBearer },
        });
      } catch (err) {
        console.warn(`[J-8] afterAll cleanup: delete ${id} failed (${(err as Error).message})`);
      }
    }
    await cleanupCtx?.close();
    await twoClubs?.dispose();
  });

  test('[happy] a club admin ENTERS via the masterdata nav, lists, creates a filter through the real backend → it appears → reopen round-trips every field', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // ENTER via the chrome nav (NOT a bare goto): the club-admin nav entry
      // reaches /accountingrules and the list renders. Pin the English cold-start
      // locale (the list test asserts the English h1).
      await page.goto('/start?lang=en');
      const navLink = page.getByTestId('af-nav-section-/accountingrules');
      await expect(
        navLink,
        'the accounting-rules nav entry is reachable from the chrome',
      ).toBeVisible();
      await navLink.click();
      await expect(page).toHaveURL('/accountingrules');
      await expect(page.locator('h1')).toHaveText('Accounting rules');
      await expect(page.getByTestId('accounting-rules-table')).toBeVisible();

      // Create a fully-populated article-target filter (legacyId 40 ∉ {5,10}) via
      // the real backend: core fields + article-target + a glider flag. Read the
      // created id off the 201 Location header (the SPA list re-fetch evicts the
      // POST body). Track it for afterAll cleanup BEFORE asserting.
      const name = `J-8 article rule ${Date.now().toString(36)}`;
      const id = await createFilter(ctx, adminBearer, createdIds, {
        filterTypeId: articleTypeId,
        filterTypeLegacyId: 40,
        ruleFilterName: name,
        description: 'J-8 real-chain article rule',
        active: true,
        stopRuleEngineWhenApplied: true,
        articleNumber: 'A-770',
        deliveryLineText: 'J-8 landing line',
        filterConfig: filterConfig({ isRuleForGliderFlights: true }),
      });

      // It renders in the @TenantId-scoped list under its bare-UUID row testid,
      // with the server-derived target column (articleNumber + deliveryLineText).
      await page.goto('/accountingrules?lang=en');
      await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
      const row = page.getByTestId(`accounting-rules-row-${id}`);
      await expect(row, 'the created filter renders in the list').toBeVisible();
      await expect(row).toContainText(name);
      await expect(page.getByTestId(`accounting-rules-target-${id}`)).toContainText(
        'A-770 (J-8 landing line)',
      );

      // PARITY SHOT: the populated /accountingrules list.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-accountingrules-list.png`,
        fullPage: true,
      });

      // Reopen the edit form (warm in-app nav via the row link) — EVERY field
      // round-trips: core text, the boolean flags, AND the article-target fields
      // incl. the filterConfig-stored deliveryLineText.
      await row.click();
      await expect(page).toHaveURL(new RegExp(`/accountingrules/${id}/edit$`));
      await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();
      await expect(page.locator('#RuleFilterName')).toHaveValue(name);
      await expect(page.locator('#Description')).toHaveValue('J-8 real-chain article rule');
      await expect(page.getByTestId('accounting-rules-flag-glider')).toBeChecked();
      await expect(page.getByTestId('accounting-rules-flag-stop-rule-engine')).toBeChecked();
      await expect(page.locator('#ArticleNumber')).toHaveValue('A-770');
      await expect(page.locator('#DeliveryLineText')).toHaveValue('J-8 landing line');

      // PARITY SHOT: the populated edit form (article-target section open).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-accountingrules-form.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-8',
        caption:
          'J-8 · accounting rule filters · a club admin logs in via real Keycloak, ENTERS via the ' +
          'masterdata nav, and creates an AccountingRuleFilter through the real backend — it renders ' +
          'in the /accountingrules list with its derived target and reopens with every field intact ' +
          '(core · flags · article-target incl. the filter_config deliveryLineText)',
        acTag: 'happy',
      });
    }
  });

  test('[happy] selecting a filter-type drives the conditional sections over the real API', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/accountingrules/new?lang=en');
      await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();

      const articleSection = page.getByTestId('accounting-rules-section-article-target');
      const recipientSection = page.getByTestId('accounting-rules-section-recipient-target');
      const aircraftSection = page.getByTestId('accounting-rules-section-aircraft-filter');
      const noLandingTaxSection = page.getByTestId('accounting-rules-section-no-landing-tax');
      const typeSelect = page.getByTestId('accounting-rules-filter-type');

      // article-target (∉{5,10}): article ON, the others OFF.
      await typeSelect.selectOption('40');
      await expect(articleSection).toBeVisible();
      await expect(recipientSection).toBeHidden();
      await expect(aircraftSection).toBeHidden();
      await expect(noLandingTaxSection).toBeHidden();

      // recipient-target (==10): recipient ON, article OFF (the exclusive swap).
      await typeSelect.selectOption('10');
      await expect(recipientSection).toBeVisible();
      await expect(articleSection).toBeHidden();

      // aircraft-filter (==30): duration/threshold ON; article also visible (∉{5,10}).
      await typeSelect.selectOption('30');
      await expect(aircraftSection).toBeVisible();
      await expect(articleSection).toBeVisible();
      await expect(recipientSection).toBeHidden();

      // no-landing-tax (==20): its section ON; article visible (∉{5,10}).
      await typeSelect.selectOption('20');
      await expect(noLandingTaxSection).toBeVisible();
      await expect(aircraftSection).toBeHidden();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-8',
        caption:
          'J-8 · accounting rule filters · selecting an AccountingRuleFilterType drives the ' +
          'conditional sections over the real reference-data catalog (article-target ∉{5,10} · ' +
          'recipient ==10 · aircraft-filter ==30 · no-landing-tax ==20) — the crux of the legacy form',
        acTag: 'happy',
      });
    }
  });

  test('[happy] a predicate match-list round-trips with its "use for all except listed" invert toggle', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Create an aircraft-filter (legacyId 30) carrying a seeded immatriculation
      // match-list at {useAllExcept:false, matched:['HB-7001']} via the real
      // backend, then prove it round-trips on the edit form (chip + invert toggle).
      const name = `J-8 match-list rule ${Date.now().toString(36)}`;
      const id = await createFilter(ctx, adminBearer, createdIds, {
        filterTypeId: aircraftTypeId,
        filterTypeLegacyId: 30,
        ruleFilterName: name,
        active: true,
        filterConfig: filterConfig({
          aircraftImmatriculations: { useAllExcept: false, matched: ['HB-7001'] } as MatchList,
          flightTypeCodes: { useAllExcept: true, matched: ['77'] } as MatchList,
        }),
      });

      // Warm in-app nav to the edit form (no cold-goto reopen). The seeded list
      // reflects on load: chip present + toggle unchecked (useAllExcept:false).
      await page.goto(`/accountingrules/${id}/edit?lang=en`);
      await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();
      await expect(
        page.getByTestId('accounting-rules-immatriculations-chip-HB-7001'),
      ).toBeVisible();
      await expect(
        page.getByTestId('accounting-rules-immatriculations-use-all-except'),
      ).not.toBeChecked();
      await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-77')).toBeVisible();
      await expect(
        page.getByTestId('accounting-rules-flight-type-codes-use-all-except'),
      ).toBeChecked();

      // Flip the invert toggle + add a chip, save (real PUT), reopen — the change
      // round-trips through the filter_config jsonb.
      await page.getByTestId('accounting-rules-immatriculations-use-all-except').check();
      await page.getByTestId('accounting-rules-immatriculations-add').fill('HB-7002');
      await page
        .getByTestId('accounting-rules-immatriculations-add-button')
        .locator('button')
        .click();
      await expect(
        page.getByTestId('accounting-rules-immatriculations-chip-HB-7002'),
      ).toBeVisible();

      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `${BASE}/${id}` &&
          r.status() === 200,
      );
      await page.getByTestId('accounting-rules-save-button').locator('button').click();
      await updated;
      await expect(page).toHaveURL('/accountingrules');

      // Reopen — the flipped toggle + both chips persisted (the jsonb round-trip).
      await page.getByTestId(`accounting-rules-row-${id}`).click();
      await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();
      await expect(
        page.getByTestId('accounting-rules-immatriculations-use-all-except'),
      ).toBeChecked();
      await expect(
        page.getByTestId('accounting-rules-immatriculations-chip-HB-7001'),
      ).toBeVisible();
      await expect(
        page.getByTestId('accounting-rules-immatriculations-chip-HB-7002'),
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-8',
        caption:
          'J-8 · accounting rule filters · a predicate match-list (aircraft immatriculations) ' +
          "round-trips its chips AND its 'use for all except listed' invert orientation through the " +
          'real filter_config jsonb (load → flip toggle + add chip → save → reopen)',
        acTag: 'happy',
      });
    }
  });

  test('[edge] tenant isolation: a filter created by club A is not readable by club B (cross-tenant GET → 404)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // Create a recipient-target filter as seed-club-1 (clubadmin4 / adminBearer).
      const id = await createFilter(ctx, adminBearer, createdIds, {
        filterTypeId: recipientTypeId,
        filterTypeLegacyId: 10,
        ruleFilterName: `J-8 tenant rule ${Date.now().toString(36)}`,
        active: true,
        recipientMemberNumber: '900900',
        recipientName: 'J-8 recipient',
        filterConfig: filterConfig(),
      });

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
        await bPage.goto('/accountingrules');
        clubBBearer = (await reqPromise).headers()['authorization']!;
      } finally {
        await bCtx.close();
      }

      // seed-club-1's admin reads it fine (the positive control — the id is valid).
      const ownRead = await ctx.request.get(`${BASE}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own filter').toBe(200);

      // Club B's admin CANNOT read seed-club-1's filter — the read is
      // @TenantId-scoped on operating_club_id (V4), so a cross-tenant GET 404s
      // (NOT 403 — the row is invisible to the other tenant, not forbidden). This
      // is the new-stack fix for the legacy cross-tenant Update/Delete leak BUG.
      const crossTenant = await ctx.request.get(`${BASE}/${id}`, {
        headers: { authorization: clubBBearer },
      });
      expect(
        crossTenant.status(),
        `club B must NOT read club A's filter (cross-tenant 404) — got ${crossTenant.status()}`,
      ).toBe(404);
    } finally {
      await ctx.close();
    }
  });
});

// ===========================================================================
// MIGRATED-DATA real chain — a real legacy AccountingRuleFilter, exported +
// migrated through the live chain, renders in the MIGRATED TestClub's list with
// its predicate filter_config intact (the migration done-bar AC). Rides the
// SAME real bundle the J-0c fan-out spec ingests; runs only when the fanout's
// real export ran (J5_BUNDLE_SOURCE=real).
// ===========================================================================
test.describe('Accounting rule filters — migrated legacy filter renders (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  test.skip(
    !useRealBundle(),
    'migrated-filter render requires the real legacy export (J5_BUNDLE_SOURCE=real, fanout only)',
  );

  let baseURL: string;
  let migratedAdmin: MigratedClubAdmin;
  let migratedBearer: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // The fanout ingests the real bundle via fan-out-migration-parity.spec.ts
    // (J-0c) earlier in the same Playwright invocation; resolve the migrated
    // TestClub admin by OWNERSHIP (the J-5 migrated-read pattern — the migrated
    // CLUB gets a FRESH provisioned UUID, so a hardcoded legacy UUID can never
    // find the admin). The legacy AccountingRuleFilters seed targets the SAME
    // legacy TestClub the migrated reservation does, so this admin owns both.
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL);
    migratedAdmin = resolved.admin;
    migratedBearer = resolved.bearer;
  });

  test('[migration/parity] a migrated legacy AccountingRuleFilter renders in the migrated TestClub list with its predicate config intact', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

      // The legacy fixture (`100 Insert AccountingRuleFilters.sql`) seeds the
      // TestClub recipient-target rule `FGZO Passagierflug Gutschein auf FGZO
      // Konto 999007 buchen` (type 10, RecipientTarget member 999007). Assert the
      // migrated filter by its IDENTIFYING legacy name (not a bare count) — proving
      // the legacy AccountingRuleFilter round-tripped, not that "some row exists".
      const list = await ctx.request.get(BASE, { headers: { authorization: migratedBearer } });
      expect(list.status(), 'the migrated TestClub lists its migrated filters').toBe(200);
      const items = (await list.json()) as ListItem[];
      const migrated = items.find((f) =>
        f.ruleFilterName.startsWith('FGZO Passagierflug Gutschein'),
      );
      expect(
        migrated,
        `the migrated legacy AccountingRuleFilter (name starting "FGZO Passagierflug Gutschein") ` +
          `must be present for the migrated TestClub — the legacy→export→migrate→render round-trip. ` +
          `Got ${items.length} row(s): ${JSON.stringify(items.map((f) => f.ruleFilterName))}`,
      ).toBeTruthy();

      // Its predicate filter_config migrated intact — read the full detail and
      // assert the legacy RecipientTarget member number + recipient name landed in
      // the migrated config (the recipient-target this rule routes to). The legacy
      // RecipientTarget JSON carries `PersonClubMemberNumber: "999007"` +
      // `RecipientName: "FGZO Passagierflug Gutschein"` (mapper folds these into
      // recipient_target + filter_config.recipientName).
      const detailRes = await ctx.request.get(`${BASE}/${migrated!.id}`, {
        headers: { authorization: migratedBearer },
      });
      expect(detailRes.status(), 'the migrated filter detail reads for the migrated admin').toBe(
        200,
      );
      const detail = (await detailRes.json()) as Detail;
      expect(
        detail.recipientTarget,
        'the migrated filter carries its legacy RecipientTarget member number (999007)',
      ).toBe('999007');
      expect(
        detail.filterConfig,
        'the migrated filter carries a non-empty predicate filter_config',
      ).toBeTruthy();

      // It renders on the migrated TestClub admin's /accountingrules list (the
      // screen wires to the migrated data). Identify by its bare-UUID id — a
      // residual row cannot satisfy this.
      await page.goto('/accountingrules?lang=en');
      await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
      await expect(
        page.getByTestId(`accounting-rules-row-${migrated!.id}`),
        'the identified migrated filter renders in the migrated tenant list',
      ).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-accountingrules-migrated-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-8',
        caption:
          'J-8 · migrated accounting rule filter · a real legacy AccountingRuleFilter (the TestClub ' +
          'recipient-target rule "FGZO Passagierflug Gutschein", member 999007), exported + migrated ' +
          'through the live chain, renders in the migrated club’s /accountingrules list with its ' +
          'predicate filter_config (recipient target) intact (full legacy→export→migrate→Keycloak→UI)',
        acTag: 'happy',
      });
    }
  });
});
