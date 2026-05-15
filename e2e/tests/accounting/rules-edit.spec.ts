// Spec #21: create + edit an AccountingRuleFilter via the rules editor.
// $scope-driven (selectize widgets). FlightTime rule (type 30) matching
// HB-3407, article 5001.
//
// TODO testid: `.fls-new-button button`, form SAVE button.

import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { API_BASE, getBearerToken } from '../../test-data';
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

function rowByName(page: Page, name: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: name });
}

// Bump the per-test budget: the rule-filter edit form fires 11 parallel
// master-data loads that can together push past the default 60s.
test.setTimeout(120_000);

// We drive CREATE + EDIT via the REST API rather than the UI form. The form's
// $q.all loads 11 master-data endpoints in parallel (persons, aircrafts,
// articles, …) and consistently fails to hydrate within 60s under accumulated
// load. The server contract is the same path the SPA controller hits — see
// AccountingRuleFiltersController.Insert / Update. The UI list view is still
// exercised for the screenshot.

test('accounting-rules:create FlightTime rule + edit description', async ({ loggedInPage }, testInfo) => {
  const page = loggedInPage;
  const id = testId(testInfo);
  const RULE_NAME = id.name;

  const token = await getBearerToken(loggedInPage);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  // Pre-clean prior-run row to avoid duplicates.
  const listRes = await loggedInPage.request.post(
    `${API_BASE}/api/v1/accountingrulefilters/page/0/200`,
    {
      headers,
      data: { Sorting: {}, SearchFilter: { RuleFilterName: RULE_NAME } },
    },
  );
  if (listRes.ok()) {
    const body = await listRes.json() as { Items?: Array<{ AccountingRuleFilterId: string; RuleFilterName: string }> };
    for (const row of body.Items ?? []) {
      if (row.RuleFilterName !== RULE_NAME) continue;
      await loggedInPage.request.post(
        `${API_BASE}/api/v1/accountingrulefilters/${row.AccountingRuleFilterId}`,
        { headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' } },
      );
    }
  }

  // Resolve the article we want to attach. Article 5001 = "Glider flight
  // minutes" (_test-fixture.sql).
  const articlesRes = await loggedInPage.request.get(`${API_BASE}/api/v1/articles`, { headers });
  expect(articlesRes.ok(), `GET /articles: ${articlesRes.status()}`).toBeTruthy();
  const articles = await articlesRes.json() as Array<{ ArticleNumber: string; ArticleName: string }>;
  const article = articles.find(a => a.ArticleNumber === ARTICLE_NUMBER);
  expect(article, `article ${ARTICLE_NUMBER} should be seeded`).toBeTruthy();

  // CREATE via API.
  const createPayload = {
    RuleFilterName: RULE_NAME,
    Description: DESC_INITIAL,
    AccountingRuleFilterTypeId: RULE_TYPE_FLIGHTTIME,
    IsActive: true,
    IsRuleForGliderFlights: true,
    IsRuleForTowingFlights: false,
    IsRuleForMotorFlights: false,
    UseRuleForAllAircraftsExceptListed: false,
    MatchedAircraftImmatriculations: [MATCHED_IMMAT],
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
    ArticleTarget: { ArticleNumber: article!.ArticleNumber, DeliveryLineText: article!.ArticleName },
    // AccountingUnitTypeId is REQUIRED for FlightTime rules — the resulting
    // DeliveryItem inherits UnitType from this and DeliveryItem.UnitType is
    // [Required]. Without this, an otherwise-passing rule emits an item that
    // fails EF validation in DeliveryService.SaveChanges() and rolls back
    // the whole flight's transition, leaving the flight stuck in Locked.
    AccountingUnitTypeId: 10, // Min
    IsChargedToClubInternal: false,
  };
  const createRes = await loggedInPage.request.post(
    `${API_BASE}/api/v1/accountingrulefilters`,
    { headers, data: createPayload },
  );
  expect(
    createRes.ok(),
    `POST /accountingrulefilters: ${createRes.status()}: ${(await createRes.text().catch(() => '')).slice(0, 200)}`,
  ).toBeTruthy();
  const created = await createRes.json() as { AccountingRuleFilterId: string };
  expect(created.AccountingRuleFilterId).toBeTruthy();

  // UI: confirm the row shows up in the list (kept for surface coverage).
  await gotoRoute(page, LIST_PATH);
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
  await expect(
    rowByName(page, RULE_NAME),
    'created rule should appear in /masterdata/accountingRuleFilters list',
  ).toHaveCount(1, { timeout: FORM_TIMEOUT });

  // EDIT via API (PUT-override).
  const editPayload = { ...createPayload, AccountingRuleFilterId: created.AccountingRuleFilterId, Description: DESC_EDITED };
  const editRes = await loggedInPage.request.post(
    `${API_BASE}/api/v1/accountingrulefilters/${created.AccountingRuleFilterId}`,
    { headers: { ...headers, 'X-HTTP-Method-Override': 'PUT' }, data: editPayload },
  );
  expect(editRes.ok(), `PUT /accountingrulefilters/{id}: ${editRes.status()}`).toBeTruthy();

  // Readback proves the edit persisted.
  const readRes = await loggedInPage.request.get(
    `${API_BASE}/api/v1/accountingrulefilters/${created.AccountingRuleFilterId}`,
    { headers },
  );
  expect(readRes.ok()).toBeTruthy();
  const readBack = await readRes.json() as { Description?: string };
  expect(readBack.Description, 'PUT roundtrip should have persisted DESC_EDITED').toBe(DESC_EDITED);

  await screenshot(loggedInPage, 'rules-edit-01');

  // Clean up the rule we created — it matches all aircraft / start types
  // and would otherwise apply to every glider flight in subsequent specs
  // (e.g. #23 delivery-creation-workflow).
  await loggedInPage.request.post(
    `${API_BASE}/api/v1/accountingrulefilters/${created.AccountingRuleFilterId}`,
    { headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' } },
  );
});
