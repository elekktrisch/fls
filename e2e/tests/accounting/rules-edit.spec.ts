
import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { API_BASE, getBearerToken } from '../../test-data';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/accountingRuleFilters';
const FORM_TIMEOUT = 60_000;
const DESC_INITIAL = 'created by e2e';
const DESC_EDITED = 'edited by e2e';

const RULE_TYPE_FLIGHTTIME = 30;
const ARTICLE_NUMBER = '5001';
const MATCHED_IMMAT = 'HB-3407';

function rowByName(page: Page, name: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: name });
}

test.setTimeout(120_000);


test('accounting-rules:create FlightTime rule + edit description', async ({ loggedInPage }, testInfo) => {
  const page = loggedInPage;
  const id = testId(testInfo);
  const RULE_NAME = id.name;

  const token = await getBearerToken(loggedInPage);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

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

  const articlesRes = await loggedInPage.request.get(`${API_BASE}/api/v1/articles`, { headers });
  expect(articlesRes.ok(), `GET /articles: ${articlesRes.status()}`).toBeTruthy();
  const articles = await articlesRes.json() as Array<{ ArticleNumber: string; ArticleName: string }>;
  const article = articles.find(a => a.ArticleNumber === ARTICLE_NUMBER);
  expect(article, `article ${ARTICLE_NUMBER} should be seeded`).toBeTruthy();

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
    AccountingUnitTypeId: 10,
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

  await gotoRoute(page, LIST_PATH);
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
  await expect(
    rowByName(page, RULE_NAME),
    'created rule should appear in /masterdata/accountingRuleFilters list',
  ).toHaveCount(1, { timeout: FORM_TIMEOUT });

  const editPayload = { ...createPayload, AccountingRuleFilterId: created.AccountingRuleFilterId, Description: DESC_EDITED };
  const editRes = await loggedInPage.request.post(
    `${API_BASE}/api/v1/accountingrulefilters/${created.AccountingRuleFilterId}`,
    { headers: { ...headers, 'X-HTTP-Method-Override': 'PUT' }, data: editPayload },
  );
  expect(editRes.ok(), `PUT /accountingrulefilters/{id}: ${editRes.status()}`).toBeTruthy();

  const readRes = await loggedInPage.request.get(
    `${API_BASE}/api/v1/accountingrulefilters/${created.AccountingRuleFilterId}`,
    { headers },
  );
  expect(readRes.ok()).toBeTruthy();
  const readBack = await readRes.json() as { Description?: string };
  expect(readBack.Description, 'PUT roundtrip should have persisted DESC_EDITED').toBe(DESC_EDITED);

  await screenshot(loggedInPage, 'rules-edit-01');

  await loggedInPage.request.post(
    `${API_BASE}/api/v1/accountingrulefilters/${created.AccountingRuleFilterId}`,
    { headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' } },
  );
});
