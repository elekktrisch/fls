import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * J-8 — AccountingRuleFilter config screen (`/accountingrules` list + edit).
 *
 * SPEC STUB (T-01). The screen does not exist yet — this file commits the SCREEN
 * SHAPE: the `data-testid` contract, the nav entry, the conditional-section flow,
 * the match-list invert toggle, the required-field inline errors, and the
 * cross-tenant 404. Every test that exercises the not-yet-built screen is
 * `test.fixme(...)` with a one-line note; T-14 thickens them to live assertions
 * (mock inner-loop) + flips the real-idp gate spec on. The ONE assertion that can
 * run today is the nav-entry presence (the chrome-reachable contract) — but the
 * `/accountingrules` route + nav item land in T-11, so even that is fixme until
 * the feature scaffold exists.
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
 *     The load-bearing behavior is `AccountingRuleFilterTypeId`-driven section
 *     visibility (legacy predicate fns):
 *       targetTypeArticleVisible()   → type ∉ {5, 10}   (Article + DeliveryLineText + AccountingUnitType)
 *       targetTypeRecipientVisible() → type == 10        (recipient member-number)
 *       isRuleTypeAircraftFilter()   → type == 30        (flight-duration range + ThresholdText)
 *       isRuleTypeNoLandingTax()     → type == 20        (no-landing-tax sections)
 *     Match-lists (each with a `UseRuleForAll<X>ExceptListed` invert toggle):
 *       MatchedAircraftImmatriculations, MatchedStartTypes, MatchedFlightTypeCodes,
 *       MatchedStartLocations, MatchedLdgLocations, MatchedClubMemberNumbers.
 *
 * Parity facts (J-8 journey "Legacy parity facts", oracle):
 *   - filter-type 5 = DoNotInvoice, 10 = recipient-target, 20 = no-landing-tax,
 *     30 = aircraft-filter (duration/threshold), 55 = StartTax.
 *   - each match-list config is `{useAllExcept (default true), matched[]}`.
 *   - cross-tenant Update/Delete is a legacy tenant-leak BUG → the new stack
 *     scopes by `@TenantId`, so a cross-tenant load → 404.
 *   - required-field rules are NEW: name + filter-type required (on the aggregate,
 *     ADR 0022 §2); legacy permits empty targets (no per-type target requirement).
 *
 * Coverage (shape committed now; fixme until the screen lands):
 *   - nav entry under masterdata reaches /accountingrules (ENTER via nav, not goto).
 *   - list renders the club's rule-filter rows (name, type, active), tenant-scoped.
 *   - create via the edit form → appears in the list → reload round-trips fields.
 *   - selecting an AccountingRuleFilterType shows/hides the conditional sections.
 *   - a match-list with its "use for all except listed" invert toggle round-trips.
 *   - required fields (filter type, name) block Save with inline as-you-type errors.
 *   - cross-tenant GET of another club's filter → 404.
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

// AccountingRuleFilterType ids (oracle enum). Drives conditional sections.
const TYPE_ARTICLE_TARGET = 40; // ∉ {5,10} → article-target sections visible
const TYPE_RECIPIENT_TARGET = 10; // recipient member-number section
const TYPE_NO_LANDING_TAX = 20; // no-landing-tax sections
const TYPE_AIRCRAFT_FILTER = 30; // flight-duration range + threshold

interface MockMatchList {
  useAllExcept: boolean;
  matched: string[];
}

interface MockRuleFilterDetail {
  id: string;
  ruleFilterName: string;
  accountingRuleFilterTypeId: number;
  active: boolean;
  stopRuleEngineWhenRuleApplied: boolean;
  description?: string;
  isForGliderFlights: boolean;
  isForTowFlights: boolean;
  isForMotorFlights: boolean;
  // article-target (type ∉ {5,10})
  articleId?: string;
  deliveryLineText?: string;
  accountingUnitTypeId?: number;
  // recipient-target (type == 10)
  recipientClubMemberNumber?: string;
  recipientName?: string;
  // aircraft-filter (type == 30)
  minFlightTimeInSecondsMatchingValue?: number;
  maxFlightTimeInSecondsMatchingValue?: number;
  thresholdText?: string;
  // match-lists (each {useAllExcept, matched[]})
  matchedAircraftImmatriculations: MockMatchList;
  matchedStartTypes: MockMatchList;
  matchedFlightTypeCodes: MockMatchList;
  matchedStartLocations: MockMatchList;
  matchedLdgLocations: MockMatchList;
  matchedClubMemberNumbers: MockMatchList;
}

interface MockRuleFilterListItem {
  id: string;
  ruleFilterName: string;
  accountingRuleFilterTypeId: number;
  active: boolean;
}

function emptyList(): MockMatchList {
  return { useAllExcept: true, matched: [] };
}

const seededArticleFilter: MockRuleFilterDetail = {
  id: 'arf-019e30c3-2c00-7001-8000-000000000001',
  ruleFilterName: 'Landing fee — gliders',
  accountingRuleFilterTypeId: TYPE_ARTICLE_TARGET,
  active: true,
  stopRuleEngineWhenRuleApplied: false,
  description: 'Standard landing fee for glider flights',
  isForGliderFlights: true,
  isForTowFlights: false,
  isForMotorFlights: false,
  articleId: 'art-019e30c3-2c00-7001-8000-0000000000a1',
  deliveryLineText: 'Landing fee',
  accountingUnitTypeId: 1,
  matchedAircraftImmatriculations: { useAllExcept: false, matched: ['HB-3001'] },
  matchedStartTypes: emptyList(),
  matchedFlightTypeCodes: emptyList(),
  matchedStartLocations: emptyList(),
  matchedLdgLocations: emptyList(),
  matchedClubMemberNumbers: emptyList(),
};

const mockClubs = [{ id: CLUB_A_ID, name: 'Test Club A', slug: 'test-club-a' }];

const mockFilterTypes = [
  { id: 5, name: 'Do not invoice' },
  { id: TYPE_RECIPIENT_TARGET, name: 'Recipient' },
  { id: TYPE_NO_LANDING_TAX, name: 'No landing tax' },
  { id: TYPE_AIRCRAFT_FILTER, name: 'Aircraft filter' },
  { id: TYPE_ARTICLE_TARGET, name: 'Article target' },
  { id: 55, name: 'Start tax' },
];

function toListItem(d: MockRuleFilterDetail): MockRuleFilterListItem {
  return {
    id: d.id,
    ruleFilterName: d.ruleFilterName,
    accountingRuleFilterTypeId: d.accountingRuleFilterTypeId,
    active: d.active,
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
 * cross-tenant case), POST (201 + Location), PUT, DELETE.
 */
function setupAccountingRulesBackend(items: MockRuleFilterDetail[]) {
  let nextId = 1000;
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
      const body = req.postDataJSON() as Omit<MockRuleFilterDetail, 'id'>;
      const created: MockRuleFilterDetail = {
        ...body,
        id: `arf-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
      };
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
      const body = req.postDataJSON() as Omit<MockRuleFilterDetail, 'id'>;
      const idx = items.findIndex((f) => f.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const prev = items[idx]!;
      items[idx] = { ...prev, ...body, id: prev.id };
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

async function bootBackend(page: Page, items: MockRuleFilterDetail[]): Promise<void> {
  await stubReferenceData(page);
  await page.route('**/api/v1/accounting-rule-filters**', setupAccountingRulesBackend(items));
}

// ── nav entry (chrome-reachable contract) ──────────────────────────────────
test('accounting-rules: a nav entry under masterdata reaches /accountingrules (ENTER via nav)', async ({
  page,
}) => {
  // T-11 adds the `/accountingrules` route + the nav item; until then there is
  // no nav link to click. The spec ENTERS via the nav link (never bare-goto)
  // per the journey AC — assert the link exists, then click it.
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/clubs?lang=de');
  const navLink = page.getByTestId('af-nav-section-/accountingrules');
  await expect(navLink).toBeVisible();
  await navLink.click();

  await expect(page).toHaveURL('/accountingrules');
  await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
});

// ── list ───────────────────────────────────────────────────────────────────
test('accounting-rules: list renders the club’s rule filters (name, type, active)', async ({
  page,
}) => {
  // T-11 builds the list page. Tenant-scoped: the backend stub only ever
  // returns this club's rows (the @TenantId finder).
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/accountingrules');

  await expect(page.locator('h1')).toHaveText('Accounting rules');
  await expect(page.getByTestId('accounting-rules-table')).toBeVisible();
  const row = page.getByTestId(`accounting-rules-row-${seededArticleFilter.id}`);
  await expect(row).toBeVisible();
  await expect(row).toContainText('Landing fee — gliders');
});

// ── create + round-trip ──────────────────────────────────────────────────────
test.fixme('accounting-rules: create via the edit form → appears in the list → reload round-trips', async ({
  page,
}) => {
  // T-11 (list + nav) + T-12 (edit core) + T-13 (match-lists) build this.
  await bootBackend(page, [{ ...seededArticleFilter }]);

  await page.goto('/accountingrules');
  await page.getByTestId('accounting-rules-new-button').locator('button').click();
  await expect(page).toHaveURL('/accountingrules/new');

  // core fields — filter-type drives the rest of the form (T-12 crux).
  await page.getByTestId('accounting-rules-filter-type').selectOption(String(TYPE_ARTICLE_TARGET));
  await page.locator('#RuleFilterName').fill('Tow fee');
  await page.getByTestId('accounting-rules-flag-towing').check();
  await page.getByTestId('accounting-rules-save-button').locator('button').click();

  await expect(page).toHaveURL('/accountingrules');
  const created = page
    .locator('[data-testid^="accounting-rules-row-"]')
    .filter({ hasText: 'Tow fee' });
  await expect(created).toBeVisible();

  // reload round-trips: re-open the created row, the name persisted.
  await created.click();
  await expect(page).toHaveURL(/\/accountingrules\/.+\/edit$/);
  await page.reload();
  await expect(page.locator('#RuleFilterName')).toHaveValue('Tow fee');
});

// ── filter-type drives the conditional sections (the legacy form crux) ─────────
test.fixme('accounting-rules: selecting a filter-type shows/hides the conditional sections', async ({
  page,
}) => {
  // T-12 builds the show/hide. Mirrors the legacy predicate matrix:
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

  await typeSelect.selectOption(String(TYPE_ARTICLE_TARGET));
  await expect(articleSection).toBeVisible();
  await expect(recipientSection).toBeHidden();

  await typeSelect.selectOption(String(TYPE_RECIPIENT_TARGET));
  await expect(recipientSection).toBeVisible();
  await expect(articleSection).toBeHidden();

  await typeSelect.selectOption(String(TYPE_AIRCRAFT_FILTER));
  await expect(aircraftSection).toBeVisible();

  await typeSelect.selectOption(String(TYPE_NO_LANDING_TAX));
  await expect(noLandingTaxSection).toBeVisible();
});

// ── match-list invert toggle round-trips ─────────────────────────────────────
test.fixme('accounting-rules: a match-list "use for all except listed" toggle persists', async ({
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

// ── required-field inline errors (J-6b liveFieldErrors, NOT touched-gated) ─────
test.fixme('accounting-rules: required fields (filter type, name) block Save with inline errors', async ({
  page,
}) => {
  // T-12 wires the J-6b `liveFieldErrors` bar. name + filter-type are required
  // on the aggregate (ADR 0022 §2); as-you-type debounced inline errors, NOT
  // the legacy touched-gated pattern. Empty required fields keep Save disabled.
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
test.fixme('accounting-rules: cross-tenant GET of another club’s filter → 404', async ({
  page,
}) => {
  // The legacy stack leaks cross-tenant Update/Delete (a BUG); the new stack
  // scopes by @TenantId, so another club's id is never in this club's list →
  // GET by that id → 404 → the edit page surfaces a not-found, not the row.
  await bootBackend(page, [{ ...seededArticleFilter }]);
  const otherClubFilterId = 'arf-019e30c3-2c00-7001-8000-0000000000ff';

  await page.goto(`/accountingrules/${otherClubFilterId}/edit`);

  await expect(page.getByTestId('accounting-rules-not-found')).toBeVisible();
  await expect(page.locator('#RuleFilterName')).toHaveCount(0);
});
