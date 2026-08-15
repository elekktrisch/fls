import { type Browser, type BrowserContext, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
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

const BASE = '/api/v1/accounting-rule-filters';
const TYPES = '/api/v1/accounting-rule-filter-types';

const SPEC_TOKEN_KEEPING_ADMIN_USERNAMES_DISJOINT = 'arf';

const ARTICLE_TARGET_LEGACY_ID = 40;
const RECIPIENT_TARGET_LEGACY_ID = 10;
const AIRCRAFT_FILTER_LEGACY_ID = 30;
const NO_LANDING_TAX_LEGACY_ID = 20;

interface FilterTypeRow {
  id: string;
  code: string;
  legacyId: number;
  name: string;
}

interface MatchList {
  useAllExcept: boolean;
  matched: string[];
}

interface ListItem {
  id: string;
  ruleFilterName: string;
  filterTypeId: string;
  active: boolean;
  sortIndicator: number;
  target: string;
}

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
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

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

test.describe('Accounting rule filters — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let adminBearer: string;
  let twoClubs: TwoClubFixture;
  let articleTypeId: string;
  let recipientTypeId: string;
  let aircraftTypeId: string;
  const createdIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    twoClubs = await provisionTwoClubs(
      browser,
      baseURL,
      SPEC_TOKEN_KEEPING_ADMIN_USERNAMES_DISJOINT,
    );
    cleanupCtx = await browser.newContext({ baseURL });
    articleTypeId = await typeUuidByLegacyId(cleanupCtx, adminBearer, ARTICLE_TARGET_LEGACY_ID);
    recipientTypeId = await typeUuidByLegacyId(cleanupCtx, adminBearer, RECIPIENT_TARGET_LEGACY_ID);
    aircraftTypeId = await typeUuidByLegacyId(cleanupCtx, adminBearer, AIRCRAFT_FILTER_LEGACY_ID);
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

      await page.goto('/start?lang=en');
      await enterViaNav(page, '/accountingrules');
      await expect(page).toHaveURL('/accountingrules');
      await expect(page.locator('h1')).toHaveText('Accounting rules');
      await expect(page.getByTestId('accounting-rules-table')).toBeVisible();

      const name = `J-8 article rule ${Date.now().toString(36)}`;
      const id = await createFilter(ctx, adminBearer, createdIds, {
        filterTypeId: articleTypeId,
        filterTypeLegacyId: ARTICLE_TARGET_LEGACY_ID,
        ruleFilterName: name,
        description: 'J-8 real-chain article rule',
        active: true,
        stopRuleEngineWhenApplied: true,
        articleNumber: 'A-770',
        deliveryLineText: 'J-8 landing line',
        filterConfig: filterConfig({ isRuleForGliderFlights: true }),
      });

      await page.goto('/accountingrules?lang=en');
      await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
      const row = page.getByTestId(`accounting-rules-row-${id}`);
      await expect(row, 'the created filter renders in the list').toBeVisible();
      await expect(row).toContainText(name);
      await expect(page.getByTestId(`accounting-rules-target-${id}`)).toContainText(
        'A-770 (J-8 landing line)',
      );

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-accountingrules-list.png`,
        fullPage: true,
      });

      await row.click();
      await expect(page).toHaveURL(new RegExp(`/accountingrules/${id}/edit$`));
      await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();
      await expect(page.locator('#RuleFilterName')).toHaveValue(name);
      await expect(page.locator('#Description')).toHaveValue('J-8 real-chain article rule');
      await expect(page.getByTestId('accounting-rules-flag-glider')).toBeChecked();
      await expect(page.getByTestId('accounting-rules-flag-stop-rule-engine')).toBeChecked();
      await expect(page.locator('#ArticleNumber')).toHaveValue('A-770');
      await expect(page.locator('#DeliveryLineText')).toHaveValue('J-8 landing line');

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

      await typeSelect.selectOption(String(ARTICLE_TARGET_LEGACY_ID));
      await expect(articleSection).toBeVisible();
      await expect(recipientSection).toBeHidden();
      await expect(aircraftSection).toBeHidden();
      await expect(noLandingTaxSection).toBeHidden();

      await typeSelect.selectOption(String(RECIPIENT_TARGET_LEGACY_ID));
      await expect(recipientSection).toBeVisible();
      await expect(articleSection).toBeHidden();

      await typeSelect.selectOption(String(AIRCRAFT_FILTER_LEGACY_ID));
      await expect(aircraftSection).toBeVisible();
      await expect(articleSection).toBeVisible();
      await expect(recipientSection).toBeHidden();

      await typeSelect.selectOption(String(NO_LANDING_TAX_LEGACY_ID));
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

      const name = `J-8 match-list rule ${Date.now().toString(36)}`;
      const id = await createFilter(ctx, adminBearer, createdIds, {
        filterTypeId: aircraftTypeId,
        filterTypeLegacyId: AIRCRAFT_FILTER_LEGACY_ID,
        ruleFilterName: name,
        active: true,
        filterConfig: filterConfig({
          aircraftImmatriculations: { useAllExcept: false, matched: ['HB-7001'] } as MatchList,
          flightTypeCodes: { useAllExcept: true, matched: ['77'] } as MatchList,
        }),
      });

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
      const id = await createFilter(ctx, adminBearer, createdIds, {
        filterTypeId: recipientTypeId,
        filterTypeLegacyId: RECIPIENT_TARGET_LEGACY_ID,
        ruleFilterName: `J-8 tenant rule ${Date.now().toString(36)}`,
        active: true,
        recipientMemberNumber: '900900',
        recipientName: 'J-8 recipient',
        filterConfig: filterConfig(),
      });

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

      const ownRead = await ctx.request.get(`${BASE}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own filter').toBe(200);

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
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL, testInfo);
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

      const list = await ctx.request.get(BASE, { headers: { authorization: migratedBearer } });
      expect(list.status(), 'the migrated TestClub lists its migrated filters').toBe(200);
      const items = (await list.json()) as ListItem[];
      const migrated = items.find((f) => f.ruleFilterName === 'FlightTime: Glider per minute');
      expect(
        migrated,
        `the migrated legacy AccountingRuleFilter "FlightTime: Glider per minute" must be present ` +
          `for the migrated TestClub — the legacy→export→migrate→render round-trip. ` +
          `Got ${items.length} row(s): ${JSON.stringify(items.map((f) => f.ruleFilterName))}`,
      ).toBeTruthy();

      const detailRes = await ctx.request.get(`${BASE}/${migrated!.id}`, {
        headers: { authorization: migratedBearer },
      });
      expect(detailRes.status(), 'the migrated filter detail reads for the migrated admin').toBe(
        200,
      );
      const detail = (await detailRes.json()) as Detail;
      expect(
        detail.articleTarget,
        'the migrated filter carries its legacy ArticleTarget article number (5001)',
      ).toBe('5001');
      expect(
        (detail.filterConfig as { deliveryLineText?: string }).deliveryLineText,
        'the legacy DeliveryLineText folded into the migrated filter_config',
      ).toBe('Glider flight minutes');
      expect(
        migrated!.target,
        'the list target column derives from the migrated article + delivery-line text',
      ).toBe('5001 (Glider flight minutes)');

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
          'article-target rule "FlightTime: Glider per minute", article 5001), exported + migrated ' +
          'through the live chain, renders in the migrated club’s /accountingrules list with its ' +
          'predicate config intact — the ArticleTarget number + the DeliveryLineText folded into ' +
          'filter_config both round-tripped (full legacy→export→migrate→Keycloak→UI)',
        acTag: 'happy',
      });
    }
  });
});
