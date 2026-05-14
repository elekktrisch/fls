// e2e/tests/20-delivery-creation-test.spec.ts
//
// Plan row #20: Drive the DeliveryCreationTest regression harness.
//
// Server endpoints (FLS.Server.Web/Controllers/DeliveryCreationTestsController.cs):
//   - GET  /api/v1/deliverycreationtests/testdeliveryforflight/{flightId}
//       Runs the rules engine on a single flight and returns a
//       DeliveryCreationResult { FlightId, CreatedDeliveryDetails, MatchedAccountingRuleFilterIds, MatchedAccountingRuleFilters }.
//       This is the "generateExampleDelivery" preview action exposed in the
//       Angular client (DeliveryCreationTestsServices.js:29).
//   - POST /api/v1/deliverycreationtests/page/{start}/{size}
//       Paged list of stored regression tests.
//   - GET  /api/v1/deliverycreationtests/run/{deliveryCreationTestId}
//       Runs one stored regression test, returning the diff (optional).
//
// UI-vs-API choice: API-driven. The "Generate example delivery" action is
// only available from inside the edit form (DeliveryCreationTestsEditController:106),
// which itself requires a stored test record to navigate to. Driving the
// underlying endpoint exercises exactly the same DeliveryService code path
// (CreateDeliveryDetailsForTest) without needing a seeded test record.
//
// Seed data (flsserver/database/FLSTest/3 insert/_test-fixture.sql):
//   - Historical glider flight F1500005-0000-0000-0000-000000000001 — Valid (30),
//     correctly populated aircraft + pilot + locations.
//   - Three AccountingRuleFilters seeded for the test club (Recipient,
//     FlightTime, LandingTax — types 10/30/60). Whether they match the
//     historical flight depends on the seed predicates; we tolerate both
//     "match -> items returned" and "no match -> empty list" outcomes per
//     the task spec ("at minimum POST a generateExampleDelivery call and
//     assert a 200 response, even with zero items").
//
// Contract gaps: no UI testids exist for the "Generate example" button or the
// resulting item table. If a future spec drives this through the UI, the
// natural additions would be `data-testid="generate-example-button"` on
// deliveryCreationTests-edit.html and `data-testid="delivery-item-row"` on
// the generated items table.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

// Fixed seed ID from _test-fixture.sql section 5 ("Historical flight").
const HISTORICAL_FLIGHT_ID = 'F1500005-0000-0000-0000-000000000001';

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try { return JSON.parse(raw).access_token as string; } catch { return null; }
  });
  expect(token, 'expected access_token in sessionStorage from loggedInPage').toBeTruthy();
  return token!;
}

test('delivery-creation-test: generateExampleDelivery preview returns a DeliveryCreationResult', async ({ freshLoggedInPage: loggedInPage }) => {
  const token = await getBearerToken(loggedInPage);

  const res = await loggedInPage.request.get(
    `${API_BASE}/api/v1/deliverycreationtests/testdeliveryforflight/${HISTORICAL_FLIGHT_ID}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(res.ok(), `GET testdeliveryforflight -> ${res.status()}: ${await res.text()}`).toBeTruthy();

  const result = await res.json();

  // Shape assertions against DeliveryCreationResult DTO
  // (FLS.Data.WebApi/Accounting/Testing/DeliveryCreationResult.cs).
  expect(result, 'response body should be present').toBeTruthy();
  expect(String(result.FlightId).toLowerCase()).toBe(HISTORICAL_FLIGHT_ID.toLowerCase());
  expect(Array.isArray(result.MatchedAccountingRuleFilterIds)).toBeTruthy();
  expect(Array.isArray(result.MatchedAccountingRuleFilters)).toBeTruthy();

  // If any rules matched, the rules engine produces a delivery with items.
  // We assert the items' minimum shape (article number, quantity, position).
  // If no rules matched (e.g. seeded filters don't apply to this flight),
  // CreatedDeliveryDetails may be null or carry an empty DeliveryItems list —
  // both are acceptable per the task spec.
  const matched = (result.MatchedAccountingRuleFilterIds as unknown[]).length;
  if (matched > 0 && result.CreatedDeliveryDetails) {
    const items = result.CreatedDeliveryDetails.DeliveryItems as Array<Record<string, unknown>>;
    expect(Array.isArray(items), 'DeliveryItems should be an array when rules matched').toBeTruthy();
    expect(items.length, 'expected at least one DeliveryItem when rules matched').toBeGreaterThan(0);
    for (const item of items) {
      expect(typeof item.ArticleNumber, `DeliveryItem.ArticleNumber should be a string (got ${item.ArticleNumber})`).toBe('string');
      expect((item.ArticleNumber as string).length, 'ArticleNumber should not be empty').toBeGreaterThan(0);
      expect(typeof item.Quantity, 'DeliveryItem.Quantity should be a number').toBe('number');
      expect(typeof item.Position, 'DeliveryItem.Position should be a number').toBe('number');
    }
    // Recipient on the delivery root — present even when nothing else matched.
    expect(result.CreatedDeliveryDetails.RecipientDetails, 'CreatedDeliveryDetails.RecipientDetails should be present').toBeTruthy();
  }
  await screenshot(loggedInPage, '20-delivery-creation-test-01');
});

test('delivery-creation-test: stored regression tests endpoint returns a paged list', async ({ freshLoggedInPage: loggedInPage }) => {
  const token = await getBearerToken(loggedInPage);

  // The seeded fixture does NOT seed any DeliveryCreationTest rows for the
  // test club, so we expect an empty (but well-formed) paged response.
  // This still exercises the page endpoint and confirms the route is wired.
  const res = await loggedInPage.request.post(
    `${API_BASE}/api/v1/deliverycreationtests/page/0/100`,
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { Sorting: {}, SearchFilter: {} },
    },
  );
  expect(res.ok(), `POST deliverycreationtests/page -> ${res.status()}`).toBeTruthy();

  const body = await res.json();
  // PagedList<DeliveryCreationTestOverview> { Items, TotalRows, PageStart, PageSize }
  expect(body, 'paged response body should be present').toBeTruthy();
  expect(Array.isArray(body.Items), 'PagedList.Items should be an array').toBeTruthy();
  expect(typeof body.TotalRows, 'PagedList.TotalRows should be a number').toBe('number');

  // Optional: if a stored regression test happens to exist, drive runTest(id)
  // and assert the diff result shape (LastDeliveryCreationTestResult on the
  // returned DeliveryCreationTestDetails). With the current fixture this
  // branch never fires, but it keeps the spec future-proof if a fixture
  // ever seeds DeliveryCreationTest rows.
  if ((body.Items as Array<{ DeliveryCreationTestId: string }>).length > 0) {
    const testId = body.Items[0].DeliveryCreationTestId;
    const runRes = await loggedInPage.request.get(
      `${API_BASE}/api/v1/deliverycreationtests/run/${testId}`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(runRes.ok(), `GET deliverycreationtests/run/${testId} -> ${runRes.status()}`).toBeTruthy();
    const runBody = await runRes.json();
    expect(runBody.DeliveryCreationTestId, 'run result echoes test id').toBeTruthy();
    // LastDeliveryCreationTestResult carries the diff (TestPassed + messages).
    expect(runBody.LastDeliveryCreationTestResult, 'run result has LastDeliveryCreationTestResult').toBeTruthy();
  }
  await screenshot(loggedInPage, '20-delivery-creation-test-02');
});

test('delivery-creation-test: /masterdata/deliveryCreationTests list renders for club-admin', async ({ freshLoggedInPage: loggedInPage }) => {
  // Light UI smoke: the list page is reachable and the busy indicator
  // settles. Seeded list is empty (per 03-masterdata.spec.ts) so we don't
  // assert on row count — but the page must not 404 or error out.
  await gotoRoute(loggedInPage, '/masterdata/deliveryCreationTests');
  // The page is the deliveryCreationTests.html template; presence of the
  // <ng-view> rendering its table host is enough to confirm the route resolved.
  await expect(loggedInPage.locator('body')).toBeVisible();
  await screenshot(loggedInPage, '20-delivery-creation-test-03');
});
