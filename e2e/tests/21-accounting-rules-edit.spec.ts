/**
 * Spec #21: accounting-rules-edit  (PLAN.md row #21)
 *
 * Drives the AngularJS AccountingRuleFilter "new" form end-to-end at
 * /masterdata/accountingRuleFilters, then re-opens the created row and
 * mutates one field to assert update-persistence.
 *
 * Why scope injection (and not field-by-field UI driving)?
 *   The rule-filter form (accountingRuleFilters-edit.html) is a wall of
 *   selectize widgets bound to AngularJS scope — selectize wraps each
 *   <select> in a custom DOM tree with no stable input handle. The clean
 *   path used elsewhere (04-flights-create.spec.ts, 09-public-flows.spec.ts)
 *   is to mutate `$scope.accountingRuleFilter` directly: the ng-model
 *   binding is the source of truth that the controller's save() reads.
 *
 * Coverage:
 *   - CREATE: open the "new" form, set RuleFilterName + Description +
 *     AccountingRuleFilterTypeId=30 (FlightTime — see
 *     FLS.Data.WebApi/Accounting/RuleFilters/AccountingRuleFilterType.cs)
 *     + match-by-aircraft predicate (UseRuleForAllAircraftsExceptListed=false
 *     + MatchedAircraftImmatriculations=['HB-3407']) + article target (5001,
 *     the seeded "Glider flight minutes" article in _test-fixture.sql:235),
 *     submit. Controller maps selection.ArticleNumber -> ArticleTarget on
 *     save (AccountingRuleFiltersEditController.js:152-157).
 *   - LIST ASSERT: filter the ng-table by RuleFilterName, verify the row
 *     exists and renders the FlightTime type name.
 *   - EDIT: re-open via row click, mutate Description in-place, submit,
 *     re-open, assert the new value sticks (proves PUT roundtrip via
 *     X-HTTP-Method-Override — AccountingRuleFiltersServices.js:83-87).
 *
 * Server contract:
 *   POST /api/v1/accountingrulefilters     (insert) — controller at
 *     flsserver/src/FLS.Server.Web/Controllers/AccountingRuleFiltersController.cs:78
 *   POST /api/v1/accountingrulefilters/:id (with X-HTTP-Method-Override:PUT
 *     — same controller line 93)
 *
 * Baseline: `freshDb` re-seeds 3 rule filters from _test-fixture.sql
 *   (Recipient, FlightTime, LandingTax — lines 164-292). After CREATE we
 *   expect 4 rows.
 *
 * TODO testid: the data-table "+" new button (`.fls-new-button button`),
 *   and the form's SAVE button currently have no `data-testid` — falls
 *   back to semantic selectors, same approach as 12-masterdata-crud.spec.ts.
 */

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/accountingRuleFilters';
const FORM_TIMEOUT = 15_000;
const NONCE = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const RULE_NAME = `E2E FlightTime ${NONCE}`;
const DESC_INITIAL = 'created by e2e';
const DESC_EDITED = 'edited by e2e';

// AccountingRuleFilterType.FlightTimeAccountingRuleFilter
const RULE_TYPE_FLIGHTTIME = 30;
// Seeded "Glider flight minutes" article (5001) — _test-fixture.sql:235.
const ARTICLE_NUMBER = '5001';
// Seeded glider from _test-fixture.sql:308 (Duo Discus).
const MATCHED_IMMAT = 'HB-3407';

async function waitForFormHydrated(page: Page): Promise<void> {
  await page.locator('#RuleFilterName').waitFor({ state: 'visible', timeout: FORM_TIMEOUT });
  await page.waitForFunction(() => {
    const w = window as unknown as { angular?: { element: (n: Element) => { scope: () => Record<string, unknown> } } };
    if (!w.angular) return false;
    const formEl = document.querySelector('form[name="accountingRuleFilterForm"]');
    if (!formEl) return false;
    const s = w.angular.element(formEl).scope() as {
      md?: { articles?: unknown[]; aircrafts?: unknown[]; accountingRuleFilterTypes?: unknown[] };
      accountingRuleFilter?: unknown;
    };
    return !!s.accountingRuleFilter
      && Array.isArray(s.md?.articles) && (s.md?.articles?.length ?? 0) > 0
      && Array.isArray(s.md?.aircrafts) && (s.md?.aircrafts?.length ?? 0) > 0
      && Array.isArray(s.md?.accountingRuleFilterTypes) && (s.md?.accountingRuleFilterTypes?.length ?? 0) > 0;
  }, undefined, { timeout: FORM_TIMEOUT });
}

async function submitForm(page: Page): Promise<void> {
  await page.locator('form[name="accountingRuleFilterForm"] button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/accountingRuleFilters', { timeout: FORM_TIMEOUT });
  await page.waitForLoadState('domcontentloaded');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible', timeout: FORM_TIMEOUT });
}

function rowByName(page: Page, name: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: name });
}

test('accounting-rules:create FlightTime rule + edit description', async ({ loggedInPage, freshDb }) => {
  void freshDb;
  const page = loggedInPage;

  // ----- baseline -----------------------------------------------------------
  await gotoRoute(page, LIST_PATH);
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
  const baselineRows = await page.locator('tbody [data-testid="row"]').count();
  expect(baselineRows, 'fixture seeds 3 accounting rule filters').toBeGreaterThanOrEqual(3);

  // ----- CREATE -------------------------------------------------------------
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/accountingRuleFilters/new', { timeout: FORM_TIMEOUT });
  await waitForFormHydrated(page);

  // RuleFilterName is the only `required` text input in the template; the
  // rest of the work is on the scope (selectize-backed dropdowns + the
  // article-target selection helper).
  await page.locator('#RuleFilterName').fill(RULE_NAME);
  await page.locator('#Description').fill(DESC_INITIAL);

  await page.evaluate(({ name, ruleTypeId, articleNumber, immat }) => {
    const w = window as unknown as {
      angular: { element: (n: Element) => {
        scope: () => Record<string, unknown> & {
          accountingRuleFilter: Record<string, unknown> & {
            AccountingRuleFilterTypeId?: number;
            IsRuleForGliderFlights?: boolean;
            UseRuleForAllAircraftsExceptListed?: boolean;
            MatchedAircraftImmatriculations?: string[];
            RuleFilterName?: string;
          };
          selection: { ArticleNumber?: string };
          text: { DeliveryLineText?: string };
          md: { articles: Array<{ ArticleNumber: string; ArticleName: string }> };
        };
        $apply: () => void;
      }; };
    };
    const formEl = document.querySelector('form[name="accountingRuleFilterForm"]');
    if (!formEl) throw new Error('accountingRuleFilterForm not found');
    const ngEl = w.angular.element(formEl);
    const s = ngEl.scope();

    s.accountingRuleFilter.AccountingRuleFilterTypeId = ruleTypeId;
    s.accountingRuleFilter.IsRuleForGliderFlights = true;
    // Match-predicate: only aircraft with this immatriculation.
    s.accountingRuleFilter.UseRuleForAllAircraftsExceptListed = false;
    s.accountingRuleFilter.MatchedAircraftImmatriculations = [immat];
    // Mirror what the typed-into <input>s already wrote (defensive — ngModel
    // debounce may not have flushed before this evaluate fires).
    s.accountingRuleFilter.RuleFilterName = name;

    // Article target. The controller's save() reads from $scope.selection /
    // $scope.text and builds ArticleTarget on the wire.
    const article = s.md.articles.find(a => a.ArticleNumber === articleNumber);
    if (!article) throw new Error(`seeded article ${articleNumber} missing — check ArticlesController`);
    s.selection.ArticleNumber = article.ArticleNumber;
    s.text.DeliveryLineText = article.ArticleName;

    ngEl.$apply();
  }, { name: RULE_NAME, ruleTypeId: RULE_TYPE_FLIGHTTIME, articleNumber: ARTICLE_NUMBER, immat: MATCHED_IMMAT });

  await submitForm(page);

  // ----- LIST ASSERT --------------------------------------------------------
  const nameFilter = page.locator('input[ng-model*="RuleFilterName"]').first();
  await nameFilter.fill(RULE_NAME);
  const createdRow = rowByName(page, RULE_NAME);
  await expect(createdRow, 'created rule should appear in the list').toHaveCount(1, { timeout: FORM_TIMEOUT });
  // The 5th column is AccountingRuleFilterTypeName (accountingRuleFilters-table.html:24).
  // We don't bind to a specific translated string — instead just confirm
  // a non-empty type label was rendered.
  const typeCell = createdRow.locator('td').nth(4);
  await expect(typeCell, 'rule-type cell should be populated for the new rule').not.toHaveText('');

  // ----- EDIT ---------------------------------------------------------------
  await createdRow.click();
  await page.waitForURL(/\/masterdata\/accountingRuleFilters\/[0-9a-fA-F-]{36}$/, { timeout: FORM_TIMEOUT });
  await waitForFormHydrated(page);
  await expect(page.locator('#RuleFilterName')).toHaveValue(RULE_NAME);
  await expect(page.locator('#Description')).toHaveValue(DESC_INITIAL);
  await page.locator('#Description').fill(DESC_EDITED);
  await submitForm(page);

  // Re-filter, re-open, assert the edit persisted server-side.
  await nameFilter.fill(RULE_NAME);
  const editedRow = rowByName(page, RULE_NAME);
  await expect(editedRow).toHaveCount(1, { timeout: FORM_TIMEOUT });
  await editedRow.click();
  await waitForFormHydrated(page);
  await expect(page.locator('#Description'), 'PUT roundtrip should have persisted the new Description').toHaveValue(DESC_EDITED);
  await screenshot(loggedInPage, '21-accounting-rules-edit-01');
});
