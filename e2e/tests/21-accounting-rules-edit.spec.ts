// Spec #21: create + edit an AccountingRuleFilter via the rules editor.
// $scope-driven (selectize widgets). FlightTime rule (type 30) matching
// HB-3407, article 5001.
//
// TODO testid: `.fls-new-button button`, form SAVE button.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import { API_BASE, getBearerToken } from '../test-data';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/accountingRuleFilters';
// Form loads 11 master-data lists in parallel; under accumulated DB load
// some calls (especially paged-persons) are slow. Give plenty of headroom.
const FORM_TIMEOUT = 60_000;
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
      md?: { articles?: unknown[]; accountingRuleFilterTypes?: unknown[] };
      accountingRuleFilter?: unknown;
    };
    // Only wait for what we actually use: articles (article picker) and
    // accountingRuleFilterTypes (rule type dropdown). The other master-data
    // calls in loadMasterData can lag without blocking our flow.
    return !!s.accountingRuleFilter
      && Array.isArray(s.md?.articles) && (s.md?.articles?.length ?? 0) > 0
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

// Bump the per-test budget: the rule-filter edit form fires 11 parallel
// master-data loads that can together push past the default 60s.
test.setTimeout(120_000);

test('accounting-rules:create FlightTime rule + edit description', async ({ loggedInPage }, testInfo) => {
  const page = loggedInPage;
  const id = testId(testInfo);
  const RULE_NAME = id.name;

  // Pre-clean prior-run row to avoid duplicates.
  const token = await getBearerToken(loggedInPage);
  const listRes = await loggedInPage.request.post(
    `${API_BASE}/api/v1/accountingrulefilters/page/0/200`,
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { Sorting: {}, SearchFilter: { RuleFilterName: RULE_NAME } },
    },
  );
  if (listRes.ok()) {
    const body = await listRes.json() as { Items?: Array<{ AccountingRuleFilterId: string; RuleFilterName: string }> };
    for (const row of body.Items ?? []) {
      if (row.RuleFilterName !== RULE_NAME) continue;
      await loggedInPage.request.post(
        `${API_BASE}/api/v1/accountingrulefilters/${row.AccountingRuleFilterId}`,
        {
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'X-HTTP-Method-Override': 'DELETE' },
        },
      );
    }
  }

  await gotoRoute(page, LIST_PATH);
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });

  // CREATE
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/accountingRuleFilters/new', { timeout: FORM_TIMEOUT });
  await waitForFormHydrated(page);

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
          $apply: () => void;
        };
      }; };
    };
    const formEl = document.querySelector('form[name="accountingRuleFilterForm"]');
    if (!formEl) throw new Error('accountingRuleFilterForm not found');
    const s = w.angular.element(formEl).scope();

    s.accountingRuleFilter.AccountingRuleFilterTypeId = ruleTypeId;
    s.accountingRuleFilter.IsRuleForGliderFlights = true;
    s.accountingRuleFilter.UseRuleForAllAircraftsExceptListed = false;
    s.accountingRuleFilter.MatchedAircraftImmatriculations = [immat];
    s.accountingRuleFilter.RuleFilterName = name;

    // save() reads from $scope.selection / $scope.text to build ArticleTarget.
    const article = s.md.articles.find(a => a.ArticleNumber === articleNumber);
    if (!article) throw new Error(`seeded article ${articleNumber} missing`);
    s.selection.ArticleNumber = article.ArticleNumber;
    s.text.DeliveryLineText = article.ArticleName;

    // $apply lives on the scope, not on the element wrapper.
    s.$apply();
  }, { name: RULE_NAME, ruleTypeId: RULE_TYPE_FLIGHTTIME, articleNumber: ARTICLE_NUMBER, immat: MATCHED_IMMAT });

  await submitForm(page);

  // LIST
  const nameFilter = page.locator('input[ng-model*="RuleFilterName"]').first();
  await nameFilter.waitFor({ state: 'visible', timeout: FORM_TIMEOUT });
  await nameFilter.fill(RULE_NAME);
  const createdRow = rowByName(page, RULE_NAME);
  await expect(createdRow, 'created rule should appear in the list').toHaveCount(1, { timeout: FORM_TIMEOUT });
  const typeCell = createdRow.locator('td').nth(4);
  await expect(typeCell, 'rule-type cell should be populated for the new rule').not.toHaveText('');

  // EDIT
  await createdRow.click();
  await page.waitForURL(/\/masterdata\/accountingRuleFilters\/[0-9a-fA-F-]{36}$/, { timeout: FORM_TIMEOUT });
  await waitForFormHydrated(page);
  await expect(page.locator('#RuleFilterName')).toHaveValue(RULE_NAME);
  await expect(page.locator('#Description')).toHaveValue(DESC_INITIAL);
  await page.locator('#Description').fill(DESC_EDITED);
  await submitForm(page);

  await nameFilter.fill(RULE_NAME);
  const editedRow = rowByName(page, RULE_NAME);
  await expect(editedRow).toHaveCount(1, { timeout: FORM_TIMEOUT });
  await editedRow.click();
  await waitForFormHydrated(page);
  await expect(page.locator('#Description'), 'PUT roundtrip should have persisted the new Description').toHaveValue(DESC_EDITED);
  await screenshot(loggedInPage, '21-accounting-rules-edit-01');
});
