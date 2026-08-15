import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

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
  discountInPercent?: number;
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

test.describe('Delivery creation test harness — rules-engine real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let adminBearer: string;
  let twoClubs: TwoClubFixture;
  let scenario: {
    flightId: string;
    ftFilterId: string;
    ltFilterId: string;
    ltWrite: Record<string, unknown>;
  };
  const createdFilters: string[] = [];
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

      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliverycreationtests');
      await expect(page).toHaveURL('/deliverycreationtests');
      await expect(page.getByTestId('dct-table')).toBeVisible();

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

      const detail = (await ctx.request
        .get(`${DCT}/${harnessId}`, { headers: { authorization: adminBearer } })
        .then((r) => r.json())) as DctDetail;
      expect(articleNumbers(detail.expectedDelivery.items)).toEqual([FT_ARTICLE, LT_ARTICLE]);
      expect(detail.expectedMatchedFilterIds.length).toBe(2);

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

      const putRes = await ctx.request.put(`${FILTERS}/${scenario.ltFilterId}`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { ...scenario.ltWrite, articleNumber: 'ART-LT-CHANGED' },
      });
      expect(putRes.status(), `filter PUT must 200 — got ${putRes.status()}`).toBe(200);

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

      const ownRead = await ctx.request.get(`${DCT}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own harness').toBe(200);

      const crossTenant = await ctx.request.get(`${DCT}/${id}`, {
        headers: { authorization: clubBBearer },
      });
      expect(
        crossTenant.status(),
        `club B must NOT read club A's harness (cross-tenant 404) — got ${crossTenant.status()}`,
      ).toBe(404);

      const crossRun = await ctx.request.post(`${DCT}/${id}/run`, {
        headers: { authorization: clubBBearer },
      });
      expect(crossRun.status(), 'club B cannot run club A’s harness').toBe(404);
    } finally {
      await ctx.close();
    }
  });
});

const MIGRATED_FT_ARTICLE = '5001';
const MIGRATED_FT_ITEM_TEXT = 'HB-3256 Glider flight minutes';
const MIGRATED_FT_TOTAL_QTY = 22;
const MIGRATED_FT_CREDITED_QTY = 10;
const MIGRATED_FT_REMAINDER_QTY = MIGRATED_FT_TOTAL_QTY - MIGRATED_FT_CREDITED_QTY;
const MIGRATED_FT_DISCOUNT_PERCENT = 25;
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
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL, testInfo);
    migratedAdmin = resolved.admin;
    migratedBearer = resolved.bearer;
  });

  test('[migration/parity] the migrated HB-3256 glider flight + filter set + prepaid credit drive the engine → a credit-split FlightTime + LandingTax delivery', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

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

      const ftLines = hb3256Items!.filter(
        (i) => i.articleNumber === MIGRATED_FT_ARTICLE && i.itemText === MIGRATED_FT_ITEM_TEXT,
      );
      expect(
        ftLines.length,
        `the migrated HB-3256 credit (10-min balance under the 22-min flight) must split the ` +
          `5001 FlightTime tier into a credited line + a remainder — proving the migrated prepaid ` +
          `balance survived cutover and the engine applied it. One line means the credit did not ` +
          `migrate (0 PersonFlightTimeCredit rows in the export) or did not match the recipient.`,
      ).toBe(2);

      const creditedLine = ftLines[0]!;
      const remainderLine = ftLines[1]!;
      expect(creditedLine.quantity, 'the credited line bills the migrated balance minutes').toBe(
        MIGRATED_FT_CREDITED_QTY,
      );
      expect(
        creditedLine.discountInPercent,
        'the credited line carries the migrated credit DiscountInPercent (the discount survived cutover)',
      ).toBe(MIGRATED_FT_DISCOUNT_PERCENT);
      expect(remainderLine.quantity, 'the remainder line bills the over-credit minutes').toBe(
        MIGRATED_FT_REMAINDER_QTY,
      );
      expect(remainderLine.discountInPercent, 'the over-credit remainder is full price').toBe(0);
      expect(creditedLine.unitType).toBe(MIGRATED_FT_UNIT);
      expect(creditedLine.quantity! + remainderLine.quantity!).toBe(MIGRATED_FT_TOTAL_QTY);

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

      await page.goto('/deliverycreationtests?lang=en');
      await page.getByTestId('dct-new-button').locator('button').click();
      await expect(page).toHaveURL('/deliverycreationtests/new');
      await page
        .getByTestId('dct-name')
        .locator('input')
        .fill('Migrated HB-3256 — credited flight time + landing tax');
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
        journey: 'J-9b',
        caption:
          'J-9b · migration-fidelity · the rules engine, run over the MIGRATED HB-3256 static-seed ' +
          'glider flight + the migrated filter set + a MIGRATED prepaid PersonFlightTimeCredit, produces ' +
          'its genuine delivery — the 5001 FlightTime tier split by the migrated credit into a credited ' +
          '10-min line (25% discount) + a 12-min full-price remainder, plus the 6001 LandingTax line ' +
          '(2 Landung). A real prepaid balance survived cutover and the engine applied it over real ' +
          'migrated legacy seed: a migrated club bills its own data, credits and all, correctly',
        acTag: 'happy',
      });
    }
  });
});


const CREDIT_SEED = '/api/v1/internal/person-flight-time-credits';

const CREDIT_DISCOUNT_PERCENT = 25;
const FLIGHT_BILLABLE_MINUTES = 90;
const COST_BALANCE_PILOT_PAYS_ALL = 'fcb-019e2e15-2c00-7268-8000-000000004268';

interface DctExample {
  delivery: { items?: DeliveryItem[] };
  matchedFilterIds: string[];
}

function flightTimeLines(items: DeliveryItem[] | undefined, article: string): DeliveryItem[] {
  return (items ?? []).filter((i) => i.articleNumber === article);
}

function flightTimeLine(
  items: DeliveryItem[] | undefined,
  article: string,
): DeliveryItem | undefined {
  const lines = flightTimeLines(items, article);
  return lines.length === 1 ? lines[0] : undefined;
}

async function dryRun(
  api: APIRequestContext,
  bearer: string,
  flightId: string,
): Promise<DctExample> {
  return (await api
    .get(`${DCT}/example/${flightId}`, { headers: { authorization: bearer } })
    .then((r) => r.json())) as DctExample;
}

async function seedCreditScenario(
  api: APIRequestContext,
  bearer: string,
  createdFilters: string[],
  createdCredits: string[],
  opts: { balanceInSeconds: number; noFlightTimeLimit: boolean },
): Promise<{
  flightId: string;
  immat: string;
  pilotPersonId: string;
  ftArticle: string;
  creditId: string;
}> {
  const md: FlightMasterdata = await seedFlightMasterdata(api, bearer);
  const ftArticle = `CREDIT-FT-${Date.now().toString(36)}`;

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
      flightCostBalanceTypeId: COST_BALANCE_PILOT_PAYS_ALL,
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
  const flightId = new URL(flightRes.headers()['location']!, 'http://localhost').pathname
    .split('/')
    .pop()!;

  await createFilter(api, bearer, createdFilters, {
    filterTypeId: FILTER_TYPE_FLIGHT_TIME,
    filterTypeLegacyId: LEGACY_FLIGHT_TIME,
    accountingUnitTypeId: UNIT_MINUTES,
    ruleFilterName: `J-9b FlightTime ${Date.now().toString(36)}`,
    active: true,
    articleNumber: ftArticle,
    deliveryLineText: 'Flugzeit (Guthaben)',
    filterConfig: filterConfig({
      isRuleForGliderFlights: true,
      aircraftImmatriculations: { useAllExcept: false, matched: [md.gliderImmat] },
    }),
  });

  const creditRes = await api.post(CREDIT_SEED, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      personId: md.pilotPersonId,
      noFlightTimeLimit: opts.noFlightTimeLimit,
      useRuleForAllAircraftsExceptListed: false,
      matchedAircraftImmatriculations: md.gliderImmat,
      discountInPercent: CREDIT_DISCOUNT_PERCENT,
      validUntil: '2099-12-31T00:00:00Z',
      currentFlightTimeBalanceInSeconds: opts.balanceInSeconds,
    },
  });
  expect(
    creditRes.status(),
    `credit seed must 201 (the dev/test-profile internal seed affordance must exist) — ` +
      `got ${creditRes.status()}: ${await creditRes.text()}`,
  ).toBe(201);
  const creditId = new URL(creditRes.headers()['location']!, 'http://localhost').pathname
    .split('/')
    .pop()!;
  createdCredits.push(creditId);

  return { flightId, immat: md.gliderImmat, pilotPersonId: md.pilotPersonId, ftArticle, creditId };
}

test.describe('Delivery creation test harness — flight-time-credit sub-engine (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let adminBearer: string;
  const createdFilters: string[] = [];
  const createdCredits: string[] = [];
  const createdHarnesses: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdFilters.length = 0;
    createdCredits.length = 0;
    createdHarnesses.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterEach(async () => {
    for (const id of createdHarnesses) {
      await cleanupCtx.request
        .delete(`${DCT}/${id}`, { headers: { authorization: adminBearer } })
        .catch((err) => console.warn(`[J-9b] harness cleanup ${id}: ${(err as Error).message}`));
    }
    createdHarnesses.length = 0;
  });

  test.afterAll(async () => {
    for (const id of createdCredits) {
      await cleanupCtx.request
        .delete(`${CREDIT_SEED}/${id}`, {
          headers: { authorization: adminBearer },
        })
        .catch((err) => console.warn(`[J-9b] credit cleanup ${id}: ${(err as Error).message}`));
    }
    for (const id of createdFilters) {
      await cleanupCtx.request
        .delete(`${FILTERS}/${id}`, { headers: { authorization: adminBearer } })
        .catch((err) => console.warn(`[J-9b] filter cleanup ${id}: ${(err as Error).message}`));
    }
    await cleanupCtx?.close();
  });

  test('[happy] a flight covered by a matching credit dry-runs to ONE discounted FlightTime line', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      const scenario = await seedCreditScenario(
        ctx.request,
        adminBearer,
        createdFilters,
        createdCredits,
        { balanceInSeconds: FLIGHT_BILLABLE_MINUTES * 60 * 4, noFlightTimeLimit: false },
      );

      await page.goto('/deliverycreationtests?lang=en');
      await page.getByTestId('dct-new-button').locator('button').click();
      await expect(page).toHaveURL('/deliverycreationtests/new');
      await page.getByTestId('dct-name').locator('input').fill('Glider — fully-covered by credit');
      await page.getByTestId('dct-flight-picker').selectOption(scenario.flightId);
      await page.getByTestId('dct-create-test-delivery').locator('button').click();
      await expect(page.getByTestId('dct-expected-item-0')).toBeVisible();
      const dryRunText = await page.getByTestId('dct-expected-section').innerText();
      expect(dryRunText).toContain(scenario.ftArticle);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-dct-credit-full-cover.png`,
        fullPage: true,
      });

      const example = await dryRun(ctx.request, adminBearer, scenario.flightId);
      const ftLine = flightTimeLine(example.delivery.items, scenario.ftArticle);
      expect(
        ftLine,
        'a fully-covered credit emits exactly ONE FlightTime line (no split)',
      ).toBeTruthy();
      expect(ftLine!.quantity, 'the whole 90-min flight is billed on the single line').toBe(
        FLIGHT_BILLABLE_MINUTES,
      );
      expect(
        ftLine!.discountInPercent,
        'the fully-covered credited line carries the credit DiscountInPercent',
      ).toBe(CREDIT_DISCOUNT_PERCENT);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-9b',
        caption:
          'J-9b · flight-time-credit · a club admin dry-runs a glider flight whose immatriculation ' +
          'matches a pilot’s pre-paid PersonFlightTimeCredit — the engine applies the balance and ' +
          'emits ONE FlightTime line carrying the credit’s DiscountInPercent (fully covered), the ' +
          'credit sub-engine proven through the existing /deliverycreationtests dry-run harness',
        acTag: 'happy',
      });
    }
  });

  test('[happy] over-credit splits into a credited line + a billed-remainder line', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      const creditedMinutes = 30;
      const remainderMinutes = FLIGHT_BILLABLE_MINUTES - creditedMinutes;
      const scenario = await seedCreditScenario(
        ctx.request,
        adminBearer,
        createdFilters,
        createdCredits,
        { balanceInSeconds: creditedMinutes * 60, noFlightTimeLimit: false },
      );

      await page.goto('/deliverycreationtests?lang=en');
      await page.getByTestId('dct-new-button').locator('button').click();
      await expect(page).toHaveURL('/deliverycreationtests/new');
      await page.getByTestId('dct-name').locator('input').fill('Glider — over-credit split');
      await page.getByTestId('dct-flight-picker').selectOption(scenario.flightId);
      await page.getByTestId('dct-create-test-delivery').locator('button').click();
      await expect(page.getByTestId('dct-expected-item-1')).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-dct-credit-over-credit-split.png`,
        fullPage: true,
      });

      const example = await dryRun(ctx.request, adminBearer, scenario.flightId);
      const lines = flightTimeLines(example.delivery.items, scenario.ftArticle);
      expect(lines.length, 'over-credit emits TWO FlightTime lines (credited + remainder)').toBe(2);

      const creditedLine = lines[0]!;
      const remainderLine = lines[1]!;
      expect(creditedLine.quantity, 'the credited line bills the covered minutes').toBe(
        creditedMinutes,
      );
      expect(
        creditedLine.discountInPercent,
        'the credited line carries the credit DiscountInPercent',
      ).toBe(CREDIT_DISCOUNT_PERCENT);
      expect(remainderLine.quantity, 'the remainder line bills the over-credit minutes').toBe(
        remainderMinutes,
      );
      expect(remainderLine.discountInPercent, 'the over-credit remainder is full price').toBe(0);
      expect(
        remainderLine.itemText,
        'the split lines share the same article + itemText (one tier, two lines)',
      ).toBe(creditedLine.itemText);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-9b',
        caption:
          'J-9b · flight-time-credit · the pilot’s pre-paid balance covers only PART of the flight, ' +
          'so the engine splits the FlightTime line in two — a credited line (the covered minutes, ' +
          'carrying the DiscountInPercent) + a billed-remainder line (the over-credit minutes, full ' +
          'price), same article and item text, rendered through the dry-run harness',
        acTag: 'happy',
      });
    }
  });
});

test.describe('Delivery creation test harness — migrated credit drives the engine (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  test.skip(
    !useRealBundle(),
    'the migrated-credit engine run requires the real legacy export (J5_BUNDLE_SOURCE=real, fanout only)',
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

  test('[migration/parity] a migrated credit applies over a migrated flight → a credited FlightTime line', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

      const flightsRes = await ctx.request.get(`${FLIGHTS}?limit=200`, {
        headers: { authorization: migratedBearer },
      });
      expect(flightsRes.status(), 'the migrated TestClub lists its migrated flights').toBe(200);
      const flightItems =
        ((await flightsRes.json()) as { items?: MigratedFlightItem[] }).items ?? [];
      expect(flightItems.length, 'the migrated TestClub carries migrated flights').toBeGreaterThan(
        0,
      );

      let creditedItems: DeliveryItem[] | undefined;
      let creditedFlightId: string | undefined;
      for (const flight of flightItems) {
        const exampleRes = await ctx.request.get(`${DCT}/example/${flight.id}`, {
          headers: { authorization: migratedBearer },
        });
        if (exampleRes.status() !== 200) {
          continue;
        }
        const items =
          ((await exampleRes.json()) as { delivery?: { items?: DeliveryItem[] } }).delivery
            ?.items ?? [];
        if (items.some((i) => (i.discountInPercent ?? 0) > 0)) {
          creditedItems = items;
          creditedFlightId = flight.id;
          break;
        }
      }

      expect(
        creditedItems,
        'the engine, run over a MIGRATED flight whose pilot holds a migrated ' +
          'PersonFlightTimeCredit, must emit a FlightTime line carrying the migrated DiscountInPercent ' +
          '— proving the migrated credit (its IsCurrent balance) reaches the rules engine end to ' +
          'end over genuine legacy seed. A missing discounted line after the deployment is COMPLETED ' +
          'means the migrated credit did not apply, not a read race.',
      ).toBeTruthy();

      const creditedLine = creditedItems!.find((i) => (i.discountInPercent ?? 0) > 0)!;
      expect(creditedLine.discountInPercent).toBeGreaterThan(0);
      expect(
        creditedLine.quantity,
        'the credited line bills a positive flight-time quantity',
      ).toBeGreaterThan(0);

      await page.goto('/deliverycreationtests?lang=en');
      await page.getByTestId('dct-new-button').locator('button').click();
      await expect(page).toHaveURL('/deliverycreationtests/new');
      await page
        .getByTestId('dct-name')
        .locator('input')
        .fill('Migrated credit — discounted flight time');
      await page.getByTestId('dct-flight-picker').selectOption(creditedFlightId!);
      await page.getByTestId('dct-create-test-delivery').locator('button').click();
      await expect(page.getByTestId('dct-expected-item-0')).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-dct-migrated-credit.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-9b',
        caption:
          'J-9b · migration-fidelity · the rules engine, run over a MIGRATED flight whose pilot holds ' +
          'a REAL migrated PersonFlightTimeCredit, produces a credited FlightTime line carrying the ' +
          'migrated DiscountInPercent — the credit (its IsCurrent balance) carried by the migration ' +
          'mapper reaches the sacred-cow engine end to end over real migrated legacy seed',
        acTag: 'happy',
      });
    }
  });
});
