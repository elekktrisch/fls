import { type Page, type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const LEGACY_ID_DO_NOT_INVOICE = 5;
const LEGACY_ID_RECIPIENT_TARGET = 10;
const LEGACY_ID_NO_LANDING_TAX = 20;
const LEGACY_ID_AIRCRAFT_FILTER = 30;
const LEGACY_ID_ARTICLE_TARGET = 40;
const LEGACY_ID_START_TAX = 55;

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
  articleTarget?: string;
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

const FILTER_TYPE_UUID_BY_LEGACY_ID: Record<number, string> = {
  [LEGACY_ID_DO_NOT_INVOICE]: '019e2e15-2c00-7658-8000-000000004658',
  [LEGACY_ID_RECIPIENT_TARGET]: '019e2e15-2c00-7652-8000-000000004652',
  [LEGACY_ID_NO_LANDING_TAX]: '019e2e15-2c00-7653-8000-000000004653',
  [LEGACY_ID_AIRCRAFT_FILTER]: '019e2e15-2c00-7654-8000-000000004654',
  [LEGACY_ID_ARTICLE_TARGET]: '019e2e15-2c00-7655-8000-000000004655',
  [LEGACY_ID_START_TAX]: '019e2e15-2c00-7659-8000-000000004659',
};

const mockFilterTypes = [
  {
    id: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_DO_NOT_INVOICE],
    code: 'DO_NOT_INVOICE',
    legacyId: LEGACY_ID_DO_NOT_INVOICE,
    name: 'Do not invoice',
  },
  {
    id: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_RECIPIENT_TARGET],
    code: 'RECIPIENT',
    legacyId: LEGACY_ID_RECIPIENT_TARGET,
    name: 'Recipient',
  },
  {
    id: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_NO_LANDING_TAX],
    code: 'NO_LANDING_TAX',
    legacyId: LEGACY_ID_NO_LANDING_TAX,
    name: 'No landing tax',
  },
  {
    id: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_AIRCRAFT_FILTER],
    code: 'AIRCRAFT_FILTER',
    legacyId: LEGACY_ID_AIRCRAFT_FILTER,
    name: 'Aircraft filter',
  },
  {
    id: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_ARTICLE_TARGET],
    code: 'ARTICLE_TARGET',
    legacyId: LEGACY_ID_ARTICLE_TARGET,
    name: 'Article target',
  },
  {
    id: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_START_TAX],
    code: 'START_TAX',
    legacyId: LEGACY_ID_START_TAX,
    name: 'Start tax',
  },
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
  filterTypeId: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_ARTICLE_TARGET]!,
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

const seededFullMatchListFilter: MockRuleFilterDetail = {
  id: 'arf-019e30c3-2c00-7001-8000-000000000002',
  filterTypeId: FILTER_TYPE_UUID_BY_LEGACY_ID[LEGACY_ID_ARTICLE_TARGET]!,
  ruleFilterName: 'Full match-list rule',
  active: true,
  sortIndicator: 2,
  stopRuleEngineWhenApplied: false,
  chargedToClubInternal: false,
  articleTarget: 'A-300',
  filterConfig: {
    ...defaultFilterConfig(),
    aircraftImmatriculations: { useAllExcept: false, matched: ['HB-3001'] },
    startTypes: { useAllExcept: true, matched: ['1'] },
    flightTypeCodes: { useAllExcept: false, matched: ['77', '88'] },
    startLocations: { useAllExcept: true, matched: ['LSZK'] },
    ldgLocations: { useAllExcept: false, matched: ['LSGE'] },
    clubMemberNumbers: { useAllExcept: true, matched: ['363289'] },
    flightCrewTypes: { useAllExcept: false, matched: ['1'] },
    aircraftHomebases: { useAllExcept: true, matched: ['LSZK'] },
    memberStates: { useAllExcept: false, matched: ['ACTIVE'] },
    personCategories: { useAllExcept: false, matched: ['PASSENGER'] },
  },
};

const mockClubs = [{ id: CLUB_A_ID, name: 'Test Club A', slug: 'test-club-a' }];

function legacyIdFor(filterTypeId: string): number {
  return mockFilterTypes.find((ty) => ty.id === filterTypeId)?.legacyId ?? 0;
}

function targetFor(d: MockRuleFilterDetail): string {
  const legacyId = legacyIdFor(d.filterTypeId);
  if (legacyId === LEGACY_ID_RECIPIENT_TARGET && d.recipientTarget) {
    const name = d.filterConfig.recipientName;
    return name ? `${name} (${d.recipientTarget})` : d.recipientTarget;
  }
  if (
    legacyId !== LEGACY_ID_DO_NOT_INVOICE &&
    legacyId !== LEGACY_ID_RECIPIENT_TARGET &&
    d.articleTarget
  ) {
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

test('accounting-rules: a nav entry under masterdata reaches /accountingrules (ENTER via nav)', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/clubs?lang=de');
  await enterViaNav(page, '/accountingrules');

  await expect(page).toHaveURL('/accountingrules');
  await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
});

test('accounting-rules: list renders the club’s rule filters (name, type, active)', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/accountingrules');

  await expect(page.locator('h1')).toHaveText('Accounting rules');
  await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
  const row = page.getByTestId(`accounting-rules-row-${seededArticleFilter.id}`);
  await expect(row).toBeVisible();
  await expect(row).toContainText('Landing fee — gliders');
});

test('accounting-rules: create via the edit form → appears in the list → reload round-trips', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/accountingrules');
  await page.getByTestId('accounting-rules-new-button').locator('button').click();
  await expect(page).toHaveURL('/accountingrules/new');

  await page
    .getByTestId('accounting-rules-filter-type')
    .selectOption(String(LEGACY_ID_ARTICLE_TARGET));
  await page.locator('#RuleFilterName').fill('Tow fee');
  await page.locator('#Description').fill('Tow fee for the towing aircraft');
  await page.getByTestId('accounting-rules-flag-towing').check();
  await page.getByTestId('accounting-rules-flag-stop-rule-engine').check();
  await page.locator('#ArticleNumber').fill('A-200');
  await page.locator('#DeliveryLineText').fill('Tow charge');
  await page.getByTestId('accounting-rules-save-button').locator('button').click();

  await expect(page).toHaveURL('/accountingrules');
  const created = page
    .locator('[data-testid^="accounting-rules-row-"]')
    .filter({ hasText: 'Tow fee' });
  await expect(created).toBeVisible();
  const createdId = (await created.getAttribute('data-testid'))!.replace(
    'accounting-rules-row-',
    '',
  );
  await expect(page.getByTestId(`accounting-rules-target-${createdId}`)).toContainText(
    'A-200 (Tow charge)',
  );

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

test('accounting-rules: selecting a filter-type shows/hides the conditional sections', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededArticleFilter }]);
  await page.goto('/accountingrules/new');

  const articleSection = page.getByTestId('accounting-rules-section-article-target');
  const recipientSection = page.getByTestId('accounting-rules-section-recipient-target');
  const aircraftSection = page.getByTestId('accounting-rules-section-aircraft-filter');
  const noLandingTaxSection = page.getByTestId('accounting-rules-section-no-landing-tax');
  const typeSelect = page.getByTestId('accounting-rules-filter-type');

  await typeSelect.selectOption(String(LEGACY_ID_ARTICLE_TARGET));
  await expect(articleSection).toBeVisible();
  await expect(recipientSection).toBeHidden();
  await expect(aircraftSection).toBeHidden();
  await expect(noLandingTaxSection).toBeHidden();

  await typeSelect.selectOption(String(LEGACY_ID_RECIPIENT_TARGET));
  await expect(recipientSection).toBeVisible();
  await expect(articleSection).toBeHidden();
  await expect(aircraftSection).toBeHidden();
  await expect(noLandingTaxSection).toBeHidden();

  await typeSelect.selectOption(String(LEGACY_ID_AIRCRAFT_FILTER));
  await expect(aircraftSection).toBeVisible();
  await expect(articleSection).toBeVisible();
  await expect(recipientSection).toBeHidden();
  await expect(noLandingTaxSection).toBeHidden();

  await typeSelect.selectOption(String(LEGACY_ID_NO_LANDING_TAX));
  await expect(noLandingTaxSection).toBeVisible();
  await expect(articleSection).toBeVisible();
  await expect(recipientSection).toBeHidden();
  await expect(aircraftSection).toBeHidden();
});

test('accounting-rules: a match-list "use for all except listed" toggle persists', async ({
  page,
}) => {
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

test('accounting-rules: ALL 9 visible match-lists round-trip (chips + invert toggle) and the dead personCategories list survives a save untouched', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededFullMatchListFilter }]);
  const id = seededFullMatchListFilter.id;
  await page.goto(`/accountingrules/${id}/edit`);
  await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();

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
  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-3001')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-77')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-88')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-club-member-numbers-chip-363289')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-member-states-chip-ACTIVE')).toBeVisible();

  await page.getByTestId('accounting-rules-immatriculations-add').fill('HB-9999');
  await page.getByTestId('accounting-rules-immatriculations-add-button').locator('button').click();
  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-9999')).toBeVisible();
  await page.getByTestId('accounting-rules-immatriculations-use-all-except').check();

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

  await expect(page).toHaveURL('/accountingrules');
  await page.getByTestId(`accounting-rules-row-${id}`).click();
  await expect(page.getByTestId('accounting-rules-edit-form')).toBeVisible();

  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-3001')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-immatriculations-chip-HB-9999')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-immatriculations-use-all-except')).toBeChecked();
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-77')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-flight-type-codes-chip-88')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-club-member-numbers-chip-363289')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-member-states-chip-ACTIVE')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-start-types-use-all-except')).toBeChecked();
  await expect(
    page.getByTestId('accounting-rules-landing-locations-use-all-except'),
  ).not.toBeChecked();
});

test('accounting-rules: required fields (filter type, name) block Save with inline errors', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededArticleFilter }]);
  await page.goto('/accountingrules/new');

  const name = page.locator('#RuleFilterName');
  await name.fill('x');
  await name.fill('');
  await name.blur();

  await expect(page.getByTestId('accounting-rules-name-error')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-save-button').locator('button')).toBeDisabled();
});

test('accounting-rules: cross-tenant GET of another club’s filter → 404', async ({
  page,
}, testInfo) => {
  allowConsoleErrors(testInfo, /\b404\b/);
  await bootBackend(page, [{ ...seededArticleFilter }]);
  const otherClubFilterId = 'arf-019e30c3-2c00-7001-8000-0000000000ff';

  await page.goto(`/accountingrules/${otherClubFilterId}/edit`);

  await expect(page.getByTestId('accounting-rules-not-found')).toBeVisible();
  await expect(page.getByTestId('accounting-rules-edit-form')).toHaveCount(0);
  await expect(page.locator('#RuleFilterName')).toHaveCount(0);
});
