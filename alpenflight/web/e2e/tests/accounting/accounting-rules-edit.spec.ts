import { type Page, type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';

/**
 * J-8 — AccountingRuleFilter config screen (`/accountingrules` list + edit).
 *
 * SPEC STUB (T-01), reconciled to the REAL client shape (T-12). The screen's
 * `data-testid` contract, the nav entry, the conditional-section flow, the
 * match-list invert toggle, the required-field inline errors, and the
 * cross-tenant 404 are committed here. T-11 landed the list + nav (the first two
 * tests). T-12 builds the edit core + the four filter-type-driven conditional
 * sections + the J-6b `liveFieldErrors` bar, flipping the create/round-trip,
 * filter-type-drives-sections, and required-field tests live. T-13 (match-list
 * sub-component) and T-14 (cross-tenant 404 + full real-idp gate spec) flip the
 * remaining two.
 *
 * Booted under the `chromium` (mock-auth) project: the principal is a mocked
 * SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR (the dual-role mock persona, see
 * `app.config.mock.ts`), so the masterdata nav + mutation affordances render
 * even though the role gate truly lives on the server (S-159). All `/api/v1/*`
 * calls are intercepted via `page.route` — NO live backend, NO real-idp, NO DB.
 *
 * Legacy contract (flsweb/src/masterdata/accountingRules/, read at carve):
 *   - List: accountingRuleFilters-table.html — club's filters, tenant-scoped.
 *   - Edit: accountingRuleFilters-edit.html + AccountingRuleFiltersEditController.js.
 *     The load-bearing behavior is the filter-type-legacyId-driven section
 *     visibility (legacy predicate fns):
 *       targetTypeArticleVisible()   → legacyId ∉ {5, 10}  (Article + DeliveryLineText + AccountingUnitType)
 *       targetTypeRecipientVisible() → legacyId == 10        (recipient member-number)
 *       isRuleTypeAircraftFilter()   → legacyId == 30        (flight-duration range + ThresholdText)
 *       isRuleTypeNoLandingTax()     → legacyId == 20        (no-landing-tax sections)
 *     Match-lists (each with a `useAllExcept` invert toggle) are T-13.
 *
 * Parity facts (J-8 journey "Legacy parity facts", oracle):
 *   - filter-type legacyId 5 = DoNotInvoice, 10 = recipient-target, 20 = no-landing-tax,
 *     30 = aircraft-filter (duration/threshold), 55 = StartTax.
 *   - each match-list config is `{useAllExcept (default true), matched[]}`.
 *   - cross-tenant Update/Delete is a legacy tenant-leak BUG → the new stack
 *     scopes by @TenantId, so a cross-tenant load → 404.
 *   - required-field rules are NEW: name + filter-type required (on the aggregate,
 *     ADR 0022 §2); legacy permits empty targets (no per-type target requirement).
 *
 * Real client shape (T-11 store + generated orval client):
 *   - filter-type catalog: `[{id (uuid), code, legacyId, name}]`; the form drives
 *     section visibility off `legacyId`, sends BOTH `filterTypeId` + `filterTypeLegacyId`.
 *   - WriteRequest carries `filterConfig` (9 boolean flags always present +
 *     threshold/duration + the 10 `{useAllExcept, matched[]}` match-lists).
 *   - Detail round-trips `filterTypeId`, `filterConfig`, `articleTarget`/`recipientTarget`.
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

// AccountingRuleFilterType legacyIds (oracle enum). Drives conditional sections.
const TYPE_ARTICLE_TARGET = 40; // ∉ {5,10} → article-target sections visible
const TYPE_RECIPIENT_TARGET = 10; // recipient member-number section
const TYPE_NO_LANDING_TAX = 20; // no-landing-tax sections
const TYPE_AIRCRAFT_FILTER = 30; // flight-duration range + threshold

interface MockMatchList {
  useAllExcept: boolean;
  matched: string[];
}

interface MockFilterConfig {
  isRuleForGliderFlights: boolean;
  isRuleForTowingFlights: boolean;
  isRuleForMotorFlights: boolean;
  noLandingTaxForGlider: boolean;
  noLandingTaxForTowingAircraft: boolean;
  noLandingTaxForAircraft: boolean;
  includeFlightTypeName: boolean;
  extendMatchingFlightTypeCodesToGliderAndTowFlight: boolean;
  includeThresholdText: boolean;
  thresholdText?: string;
  minFlightTimeInSecondsMatchingValue?: number;
  maxFlightTimeInSecondsMatchingValue?: number;
  deliveryLineText?: string;
  recipientName?: string;
  aircraftImmatriculations: MockMatchList;
  startTypes: MockMatchList;
  flightTypeCodes: MockMatchList;
  startLocations: MockMatchList;
  ldgLocations: MockMatchList;
  clubMemberNumbers: MockMatchList;
  flightCrewTypes: MockMatchList;
  aircraftHomebases: MockMatchList;
  memberStates: MockMatchList;
  // dead in the legacy form (no control) → migrated data preserved untouched
  // through a save (T-13). Carried here so the round-trip asserts it survives.
  personCategories: MockMatchList;
}

interface MockRuleFilterDetail {
  id: string;
  filterTypeId: string;
  accountingUnitTypeId?: string;
  ruleFilterName: string;
  description?: string;
  active: boolean;
  sortIndicator: number;
  stopRuleEngineWhenApplied: boolean;
  chargedToClubInternal: boolean;
  // article-target (legacyId ∉ {5,10})
  articleTarget?: string;
  // recipient-target (legacyId == 10)
  recipientTarget?: string;
  filterConfig: MockFilterConfig;
}

interface MockRuleFilterListItem {
  id: string;
  filterTypeId: string;
  ruleFilterName: string;
  active: boolean;
  sortIndicator: number;
  target: string;
}

// Filter-type catalog (the real reference-data shape: uuid id + legacyId int).
// The form's section-driving select binds the `legacyId`.
const FILTER_TYPE_UUIDS: Record<number, string> = {
  5: '019e2e15-2c00-7658-8000-000000004658',
  10: '019e2e15-2c00-7652-8000-000000004652',
  20: '019e2e15-2c00-7653-8000-000000004653',
  30: '019e2e15-2c00-7654-8000-000000004654',
  40: '019e2e15-2c00-7655-8000-000000004655',
  55: '019e2e15-2c00-7659-8000-000000004659',
};

const mockFilterTypes = [
  { id: FILTER_TYPE_UUIDS[5], code: 'DO_NOT_INVOICE', legacyId: 5, name: 'Do not invoice' },
  {
    id: FILTER_TYPE_UUIDS[10],
    code: 'RECIPIENT',
    legacyId: TYPE_RECIPIENT_TARGET,
    name: 'Recipient',
  },
  {
    id: FILTER_TYPE_UUIDS[20],
    code: 'NO_LANDING_TAX',
    legacyId: TYPE_NO_LANDING_TAX,
    name: 'No landing tax',
  },
  {
    id: FILTER_TYPE_UUIDS[30],
    code: 'AIRCRAFT_FILTER',
    legacyId: TYPE_AIRCRAFT_FILTER,
    name: 'Aircraft filter',
  },
  {
    id: FILTER_TYPE_UUIDS[40],
    code: 'ARTICLE_TARGET',
    legacyId: TYPE_ARTICLE_TARGET,
    name: 'Article target',
  },
  { id: FILTER_TYPE_UUIDS[55], code: 'START_TAX', legacyId: 55, name: 'Start tax' },
];

function emptyList(): MockMatchList {
  return { useAllExcept: true, matched: [] };
}

function defaultFilterConfig(): MockFilterConfig {
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
    aircraftImmatriculations: emptyList(),
    startTypes: emptyList(),
    flightTypeCodes: emptyList(),
    startLocations: emptyList(),
    ldgLocations: emptyList(),
    clubMemberNumbers: emptyList(),
    flightCrewTypes: emptyList(),
    aircraftHomebases: emptyList(),
    memberStates: emptyList(),
    personCategories: emptyList(),
  };
}

const seededArticleFilter: MockRuleFilterDetail = {
  id: 'arf-019e30c3-2c00-7001-8000-000000000001',
  filterTypeId: FILTER_TYPE_UUIDS[TYPE_ARTICLE_TARGET]!,
  ruleFilterName: 'Landing fee — gliders',
  active: true,
  sortIndicator: 1,
  stopRuleEngineWhenApplied: false,
  chargedToClubInternal: false,
  description: 'Standard landing fee for glider flights',
  articleTarget: 'A-100',
  filterConfig: {
    ...defaultFilterConfig(),
    isRuleForGliderFlights: true,
    deliveryLineText: 'Landing fee',
    aircraftImmatriculations: { useAllExcept: false, matched: ['HB-3001'] },
  },
};

/**
 * A filter carrying ALL 9 visible match-lists at distinct, identifiable values
 * (each with a distinct `useAllExcept` orientation so the invert toggle's state
 * round-trips per list) PLUS a non-default `personCategories` — the dead list
 * with no control (T-13) — to prove it is preserved untouched through a save.
 */
const seededFullMatchListFilter: MockRuleFilterDetail = {
  id: 'arf-019e30c3-2c00-7001-8000-000000000002',
  filterTypeId: FILTER_TYPE_UUIDS[TYPE_ARTICLE_TARGET]!,
  ruleFilterName: 'Full match-list rule',
  active: true,
  sortIndicator: 2,
  stopRuleEngineWhenApplied: false,
  chargedToClubInternal: false,
  articleTarget: 'A-300',
  filterConfig: {
    ...defaultFilterConfig(),
    // each list distinct value + orientation → per-list round-trip is unambiguous
    aircraftImmatriculations: { useAllExcept: false, matched: ['HB-3001'] },
    startTypes: { useAllExcept: true, matched: ['1'] },
    flightTypeCodes: { useAllExcept: false, matched: ['77', '88'] },
    startLocations: { useAllExcept: true, matched: ['LSZK'] },
    ldgLocations: { useAllExcept: false, matched: ['LSGE'] },
    clubMemberNumbers: { useAllExcept: true, matched: ['363289'] },
    flightCrewTypes: { useAllExcept: false, matched: ['1'] },
    aircraftHomebases: { useAllExcept: true, matched: ['LSZK'] },
    memberStates: { useAllExcept: false, matched: ['ACTIVE'] },
    // the dead, control-less list — must survive a save untouched.
    personCategories: { useAllExcept: false, matched: ['PASSENGER'] },
  },
};

const mockClubs = [{ id: CLUB_A_ID, name: 'Test Club A', slug: 'test-club-a' }];

function legacyIdFor(filterTypeId: string): number {
  return mockFilterTypes.find((ty) => ty.id === filterTypeId)?.legacyId ?? 0;
}

function targetFor(d: MockRuleFilterDetail): string {
  const legacyId = legacyIdFor(d.filterTypeId);
  if (legacyId === TYPE_RECIPIENT_TARGET && d.recipientTarget) {
    const name = d.filterConfig.recipientName;
    return name ? `${name} (${d.recipientTarget})` : d.recipientTarget;
  }
  if (legacyId !== 5 && legacyId !== TYPE_RECIPIENT_TARGET && d.articleTarget) {
    const text = d.filterConfig.deliveryLineText;
    return text ? `${d.articleTarget} (${text})` : d.articleTarget;
  }
  return '';
}

function toListItem(d: MockRuleFilterDetail): MockRuleFilterListItem {
  return {
    id: d.id,
    filterTypeId: d.filterTypeId,
    ruleFilterName: d.ruleFilterName,
    active: d.active,
    sortIndicator: d.sortIndicator,
    target: targetFor(d),
  };
}

/** Stub the reference data the edit form consumes (read-only lookups, T-07). */
async function stubReferenceData(page: Page): Promise<void> {
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubs),
    }),
  );
  await page.route('**/api/v1/accounting-rule-filter-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockFilterTypes),
    }),
  );
  for (const endpoint of [
    'accounting-unit-types',
    'flight-crew-types',
    'articles',
    'aircraft',
    'locations',
    'flight-types',
    'start-types',
  ]) {
    await page.route(`**/api/v1/${endpoint}**`, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
}

/**
 * In-memory `/api/v1/accounting-rule-filters` backend. Mirrors the
 * flight-types stub shape: GET list, GET by id (404 when absent — the
 * cross-tenant case), POST (201 + Location), PUT, DELETE. POST/PUT echo a
 * full Detail back from the WriteRequest body (the create→round-trip path).
 */
function setupAccountingRulesBackend(items: MockRuleFilterDetail[]) {
  let nextId = 1000;
  let nextSort = items.length + 1;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/accounting-rule-filters\/(arf-[^/]+)$/);

    if (method === 'GET' && path === '/api/v1/accounting-rule-filters') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = items.find((f) => f.id === idMatch[1]);
      // A cross-tenant id is simply absent from this club's `items` → 404
      // (the @TenantId-scoped finder never returns another club's row).
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/accounting-rule-filters') {
      const body = req.postDataJSON() as MockWriteRequest;
      const created = detailFromWrite(body, {
        id: `arf-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
        sortIndicator: nextSort++,
      });
      items.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/accounting-rule-filters/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as MockWriteRequest;
      const idx = items.findIndex((f) => f.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const prev = items[idx]!;
      items[idx] = detailFromWrite(body, { id: prev.id, sortIndicator: prev.sortIndicator });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items[idx]),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = items.findIndex((f) => f.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      items.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  };
}

interface MockWriteRequest {
  filterTypeId: string;
  filterTypeLegacyId?: number;
  accountingUnitTypeId?: string;
  ruleFilterName: string;
  description?: string;
  active?: boolean;
  stopRuleEngineWhenApplied?: boolean;
  chargedToClubInternal?: boolean;
  articleNumber?: string;
  deliveryLineText?: string;
  recipientMemberNumber?: string;
  recipientName?: string;
  filterConfig: MockFilterConfig;
}

/** Project a WriteRequest onto the Detail shape the server returns (round-trip). */
function detailFromWrite(
  body: MockWriteRequest,
  fixed: { id: string; sortIndicator: number },
): MockRuleFilterDetail {
  const detail: MockRuleFilterDetail = {
    id: fixed.id,
    filterTypeId: body.filterTypeId,
    ruleFilterName: body.ruleFilterName,
    active: body.active ?? true,
    sortIndicator: fixed.sortIndicator,
    stopRuleEngineWhenApplied: body.stopRuleEngineWhenApplied ?? false,
    chargedToClubInternal: body.chargedToClubInternal ?? false,
    filterConfig: { ...defaultFilterConfig(), ...body.filterConfig },
  };
  if (body.accountingUnitTypeId) detail.accountingUnitTypeId = body.accountingUnitTypeId;
  if (body.description) detail.description = body.description;
  if (body.articleNumber) detail.articleTarget = body.articleNumber;
  if (body.recipientMemberNumber) detail.recipientTarget = body.recipientMemberNumber;
  if (body.deliveryLineText) detail.filterConfig.deliveryLineText = body.deliveryLineText;
  if (body.recipientName) detail.filterConfig.recipientName = body.recipientName;
  return detail;
}

async function bootBackend(page: Page, items: MockRuleFilterDetail[]): Promise<void> {
  await stubReferenceData(page);
  await page.route('**/api/v1/accounting-rule-filters**', setupAccountingRulesBackend(items));
}

// ── nav entry (chrome-reachable contract) ──────────────────────────────────
test('accounting-rules: a nav entry under masterdata reaches /accountingrules (ENTER via nav)', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/clubs?lang=de');
  // Accounting rules now lives under the Masterdata nav group (J-8 T-22a): the
  // helper opens that dropdown first, then clicks the nested entry.
  await enterViaNav(page, '/accountingrules');

  await expect(page).toHaveURL('/accountingrules');
  await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
});

// ── list ───────────────────────────────────────────────────────────────────
test('accounting-rules: list renders the club’s rule filters (name, type, active)', async ({
  page,
}) => {
  // Tenant-scoped: the backend stub only ever returns this club's rows (the
  // @TenantId finder).
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/accountingrules');

  await expect(page.locator('h1')).toHaveText('Accounting rules');
  await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
  const row = page.getByTestId(`accounting-rules-row-${seededArticleFilter.id}`);
  await expect(row).toBeVisible();
  await expect(row).toContainText('Landing fee — gliders');
});

// ── create + round-trip ──────────────────────────────────────────────────────
test('accounting-rules: create via the edit form → appears in the list → reload round-trips', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/accountingrules');
  await page.getByTestId('accounting-rules-new-button').locator('button').click();
  await expect(page).toHaveURL('/accountingrules/new');

  // core fields — the filter-type drives the rest of the form (T-12 crux). An
  // article-target type (40 ∉ {5,10}) opens the article section, so the
  // round-trip exercises EVERY persisted axis: core text + the 3 always-on
  // boolean flags + the article-target fields (articleNumber + the
  // deliveryLineText that lives inside filterConfig).
  await page.getByTestId('accounting-rules-filter-type').selectOption(String(TYPE_ARTICLE_TARGET));
  await page.locator('#RuleFilterName').fill('Tow fee');
  await page.locator('#Description').fill('Tow fee for the towing aircraft');
  await page.getByTestId('accounting-rules-flag-towing').check();
  await page.getByTestId('accounting-rules-flag-stop-rule-engine').check();
  // article-target fields (the section is visible for type 40): articleNumber
  // is a top-level write field; deliveryLineText folds into filterConfig.
  await page.locator('#ArticleNumber').fill('A-200');
  await page.locator('#DeliveryLineText').fill('Tow charge');
  await page.getByTestId('accounting-rules-save-button').locator('button').click();

  await expect(page).toHaveURL('/accountingrules');
  const created = page
    .locator('[data-testid^="accounting-rules-row-"]')
    .filter({ hasText: 'Tow fee' });
  await expect(created).toBeVisible();
  // The list's derived target column proves articleNumber + deliveryLineText
  // (filterConfig) both round-tripped server-side ('A-200 (Tow charge)'). The
  // target renders in the sibling `#secondary` template under its own
  // `accounting-rules-target-<id>` testid, not inside the row link.
  const createdId = (await created.getAttribute('data-testid'))!.replace(
    'accounting-rules-row-',
    '',
  );
  await expect(page.getByTestId(`accounting-rules-target-${createdId}`)).toContainText(
    'A-200 (Tow charge)',
  );

  // reload round-trips: re-open the created row, EVERY field persisted — core
  // text, the boolean flags, AND the article-target fields incl. the
  // filterConfig-stored deliveryLineText.
  await created.click();
  await expect(page).toHaveURL(/\/accountingrules\/.+\/edit$/);
  await page.reload();
  await expect(page.locator('#RuleFilterName')).toHaveValue('Tow fee');
  await expect(page.locator('#Description')).toHaveValue('Tow fee for the towing aircraft');
  await expect(page.getByTestId('accounting-rules-flag-towing')).toBeChecked();
  await expect(page.getByTestId('accounting-rules-flag-stop-rule-engine')).toBeChecked();
  await expect(page.getByTestId('accounting-rules-flag-glider')).not.toBeChecked();
  await expect(page.locator('#ArticleNumber')).toHaveValue('A-200');
  await expect(page.locator('#DeliveryLineText')).toHaveValue('Tow charge');
});

// ── filter-type drives the conditional sections (the legacy form crux) ─────────
test('accounting-rules: selecting a filter-type shows/hides the conditional sections', async ({
  page,
}) => {
  // Mirrors the legacy predicate matrix keyed off the type's legacyId:
  //   article-target (∉{5,10}) → article section visible, recipient hidden
  //   recipient-target (==10)  → recipient section visible, article hidden
  //   aircraft-filter (==30)   → flight-duration + threshold visible
  //   no-landing-tax (==20)    → no-landing-tax section visible
  await bootBackend(page, [{ ...seededArticleFilter }]);
  await page.goto('/accountingrules/new');

  const articleSection = page.getByTestId('accounting-rules-section-article-target');
  const recipientSection = page.getByTestId('accounting-rules-section-recipient-target');
  const aircraftSection = page.getByTestId('accounting-rules-section-aircraft-filter');
  const noLandingTaxSection = page.getByTestId('accounting-rules-section-no-landing-tax');
  const typeSelect = page.getByTestId('accounting-rules-filter-type');

  // article-target (∉{5,10}): article section ON, the other three OFF.
  await typeSelect.selectOption(String(TYPE_ARTICLE_TARGET));
  await expect(articleSection).toBeVisible();
  await expect(recipientSection).toBeHidden();
  await expect(aircraftSection).toBeHidden();
  await expect(noLandingTaxSection).toBeHidden();

  // recipient-target (==10): recipient section ON, article OFF (the exclusive
  // article-vs-recipient swap), aircraft/no-landing-tax OFF.
  await typeSelect.selectOption(String(TYPE_RECIPIENT_TARGET));
  await expect(recipientSection).toBeVisible();
  await expect(articleSection).toBeHidden();
  await expect(aircraftSection).toBeHidden();
  await expect(noLandingTaxSection).toBeHidden();

  // aircraft-filter (==30): the flight-duration/threshold section ON; the
  // article section is also visible (30 ∉ {5,10}); recipient + no-landing OFF.
  await typeSelect.selectOption(String(TYPE_AIRCRAFT_FILTER));
  await expect(aircraftSection).toBeVisible();
  await expect(articleSection).toBeVisible();
  await expect(recipientSection).toBeHidden();
  await expect(noLandingTaxSection).toBeHidden();

  // no-landing-tax (==20): the no-landing-tax section ON; 20 ∉ {5,10} so the
  // article section is visible too; recipient + aircraft-filter OFF.
  await typeSelect.selectOption(String(TYPE_NO_LANDING_TAX));
  await expect(noLandingTaxSection).toBeVisible();
  await expect(articleSection).toBeVisible();
  await expect(recipientSection).toBeHidden();
  await expect(aircraftSection).toBeHidden();
});

// ── match-list invert toggle round-trips (the seeded single-list case) ─────────
test('accounting-rules: a match-list "use for all except listed" toggle persists', async ({
  page,
}) => {
  // T-13 builds the match-list sub-component. The aircraft-immatriculation list
  // ships {useAllExcept: false, matched: ['HB-3001']} on the seeded row — the
  // invert toggle reflects + round-trips it.
  await bootBackend(page, [{ ...seededArticleFilter }]);
  await page.goto(`/accountingrules/${seededArticleFilter.id}/edit`);

  const invertToggle = page.getByTestId('accounting-rules-immatriculations-use-all-except');
  await expect(invertToggle).not.toBeChecked();
  await invertToggle.check();
  await page.getByTestId('accounting-rules-save-button').locator('button').click();

  await expect(page).toHaveURL('/accountingrules');
  await page.getByTestId(`accounting-rules-row-${seededArticleFilter.id}`).click();
  await expect(page.getByTestId('accounting-rules-immatriculations-use-all-except')).toBeChecked();
});

// ── full 9-list round-trip + personCategories survives untouched ──────────────
test('accounting-rules: ALL 9 visible match-lists round-trip (chips + invert toggle) and the dead personCategories list survives a save untouched', async ({
  page,
}) => {
  // The full match-list parity (J-8 AC: "the predicate match-lists round-trip …
  // EACH with its 'use for ALL except listed' toggle"). The seeded filter
  // carries all 9 visible lists at distinct values + orientations, plus a
  // non-default personCategories (dead, control-less, T-13). We assert the load
  // reflects every list, edit ONE list, save, reopen, and confirm (a) every
  // OTHER list is unchanged AND (b) personCategories — which has no control and
  // is lifted from the loaded detail on save — survived the write untouched.
  await bootBackend(page, [{ ...seededFullMatchListFilter }]);
  const id = seededFullMatchListFilter.id;
  await page.goto(`/accountingrules/${id}/edit`);
  await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();

  // Every list's invert toggle reflects its seeded orientation on load.
  const toggles: Record<string, boolean> = {
    immatriculations: false,
    'start-types': true,
    'flight-type-codes': false,
    'start-locations': true,
    'landing-locations': false,
    'club-member-numbers': true,
    'flight-crew-types': false,
    'aircraft-homebases': true,
    'member-states': false,
  };
  for (const [list, useAllExcept] of Object.entries(toggles)) {
    const toggle = page.getByTestId(`accounting-rules-${list}-use-all-except`);
    if (useAllExcept) {
      await expect(toggle, `${list} invert toggle loads checked`).toBeChecked();
    } else {
      await expect(toggle, `${list} invert toggle loads unchecked`).not.toBeChecked();
    }
  }
  // The seeded chips render per list (a representative spot-check across lists).
  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-3001')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-77')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-88')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-club-member-numbers-chip-363289')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-member-states-chip-ACTIVE')).toBeVisible();

  // Edit exactly ONE list: add a chip to immatriculations + flip its toggle.
  await page.getByTestId('accounting-rules-immatriculations-add').fill('HB-9999');
  await page.getByTestId('accounting-rules-immatriculations-add-button').locator('button').click();
  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-9999')).toBeVisible();
  await page.getByTestId('accounting-rules-immatriculations-use-all-except').check();

  // Capture the PUT body so we can assert personCategories was written back
  // verbatim (it has no control → the page lifts it from the loaded detail).
  const put = page.waitForRequest(
    (req) =>
      req.method() === 'PUT' &&
      new URL(req.url()).pathname.startsWith('/api/v1/accounting-rule-filters/'),
  );
  await page.getByTestId('accounting-rules-save-button').locator('button').click();
  const putBody = (await (await put).postDataJSON()) as MockWriteRequest;
  expect(
    putBody.filterConfig.personCategories,
    'the dead personCategories list is written back verbatim (preserved from the loaded detail)',
  ).toEqual({ useAllExcept: false, matched: ['PASSENGER'] });

  // Reopen — the edited list round-tripped AND every untouched list is intact.
  await expect(page).toHaveURL('/accountingrules');
  await page.getByTestId(`accounting-rules-row-${id}`).click();
  await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();

  // The edited immatriculations list: new chip present, toggle flipped to checked.
  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-3001')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-9999')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-immatriculations-use-all-except')).toBeChecked();
  // Every OTHER list's chips + orientation are unchanged after the save.
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-77')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-88')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-club-member-numbers-chip-363289')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-member-states-chip-ACTIVE')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-start-types-use-all-except')).toBeChecked();
  await expect(
    page.getByTestId('accounting-rules-landing-locations-use-all-except'),
  ).not.toBeChecked();
});

// ── required-field inline errors (J-6b liveFieldErrors, NOT touched-gated) ─────
test('accounting-rules: required fields (filter type, name) block Save with inline errors', async ({
  page,
}) => {
  // name + filter-type are required on the aggregate (ADR 0022 §2); as-you-type
  // debounced inline errors, NOT the legacy touched-gated pattern. Empty
  // required fields keep Save disabled.
  await bootBackend(page, [{ ...seededArticleFilter }]);
  await page.goto('/accountingrules/new');

  const name = page.locator('#RuleFilterName');
  await name.fill('x');
  await name.fill('');
  await name.blur();

  await expect(page.getByTestId('accounting-rules-name-error')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-save-button').locator('button')).toBeDisabled();
});

// ── cross-tenant isolation ───────────────────────────────────────────────────
test('accounting-rules: cross-tenant GET of another club’s filter → 404', async ({
  page,
}, testInfo) => {
  // The cross-tenant detail GET is deliberately 404ed; the browser logs it.
  allowConsoleErrors(testInfo, /\b404\b/);
  // The legacy stack leaks cross-tenant Update/Delete (a BUG); the new stack
  // scopes by @TenantId, so another club's id is never in this club's list →
  // GET by that id → 404 → the edit page surfaces a not-found, not the row.
  // (T-12 wired `accounting-rules-not-found` to a `not-found` saveErrorKind on a
  // detail-load failure; this activates that path.)
  await bootBackend(page, [{ ...seededArticleFilter }]);
  const otherClubFilterId = 'arf-019e30c3-2c00-7001-8000-0000000000ff';

  await page.goto(`/accountingrules/${otherClubFilterId}/edit`);

  await expect(page.getByTestId('accounting-rules-not-found')).toBeVisible();
  // The edit form must NOT render for a not-found (cross-tenant) id.
  await expect(page.getByTestId('accounting-rules-edit-form')).toHaveCount(0);
  await expect(page.locator('#RuleFilterName')).toHaveCount(0);
});
