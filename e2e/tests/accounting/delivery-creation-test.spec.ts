import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import type { APIResponse, Page } from '@playwright/test';

import { testId } from '../../test-id';
import { ensureGliderFlight, getBearerToken } from '../../test-data';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

test('delivery-creation-test: generateExampleDelivery preview returns a DeliveryCreationResult', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  const { flightId: HISTORICAL_FLIGHT_ID } = await ensureGliderFlight(loggedInPage.request, token, {
    comment: id.name,
  });

  let res!: APIResponse;
  let result: any;
  for (let attempt = 1; attempt <= 6; attempt++) {
    res = await loggedInPage.request.get(
      `${API_BASE}/api/v1/deliverycreationtests/testdeliveryforflight/${HISTORICAL_FLIGHT_ID}`,
      { headers: { Authorization: `Bearer ${token}` }, timeout: 30_000 },
    );
    if (!res.ok()) break;
    result = await res.json();
    const matched = (result.MatchedAccountingRuleFilterIds as unknown[]).length;
    const items = result.CreatedDeliveryDetails?.DeliveryItems as unknown[] | undefined;
    const consistentPreviewSnapshot =
      matched === 0 || !result.CreatedDeliveryDetails || (items && items.length > 0);
    if (consistentPreviewSnapshot) break;
    await new Promise(r => setTimeout(r, 300 * attempt));
  }
  expect(res.ok(), `GET testdeliveryforflight -> ${res.status()}: ${await res.text()}`).toBeTruthy();

  expect(result, 'response body should be present').toBeTruthy();
  expect(String(result.FlightId).toLowerCase()).toBe(HISTORICAL_FLIGHT_ID.toLowerCase());
  expect(Array.isArray(result.MatchedAccountingRuleFilterIds)).toBeTruthy();
  expect(Array.isArray(result.MatchedAccountingRuleFilters)).toBeTruthy();

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
    expect(result.CreatedDeliveryDetails.RecipientDetails, 'CreatedDeliveryDetails.RecipientDetails should be present').toBeTruthy();
  }
  await screenshot(loggedInPage, 'delivery-creation-test-01');
});

test('delivery-creation-test: stored regression tests endpoint returns a paged list', async ({ loggedInPage }) => {
  const token = await getBearerToken(loggedInPage);

  const res = await loggedInPage.request.post(
    `${API_BASE}/api/v1/deliverycreationtests/page/0/100`,
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { Sorting: {}, SearchFilter: {} },
    },
  );
  expect(res.ok(), `POST deliverycreationtests/page -> ${res.status()}`).toBeTruthy();

  const body = await res.json();
  expect(body, 'paged response body should be present').toBeTruthy();
  expect(Array.isArray(body.Items), 'PagedList.Items should be an array').toBeTruthy();
  expect(typeof body.TotalRows, 'PagedList.TotalRows should be a number').toBe('number');

  if ((body.Items as Array<{ DeliveryCreationTestId: string }>).length > 0) {
    const storedRegressionTestId = body.Items[0].DeliveryCreationTestId;
    const runRes = await loggedInPage.request.get(
      `${API_BASE}/api/v1/deliverycreationtests/run/${storedRegressionTestId}`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(
      runRes.ok(),
      `GET deliverycreationtests/run/${storedRegressionTestId} -> ${runRes.status()}`,
    ).toBeTruthy();
    const runBody = await runRes.json();
    expect(runBody.DeliveryCreationTestId, 'run result echoes test id').toBeTruthy();
    expect(runBody.LastDeliveryCreationTestResult, 'run result has LastDeliveryCreationTestResult').toBeTruthy();
  }
  await screenshot(loggedInPage, 'delivery-creation-test-02');
});

test('delivery-creation-test: /masterdata/deliveryCreationTests route loads for club-admin (smoke — the seeded list is empty, no rows asserted)', async ({ loggedInPage }) => {
  await gotoRoute(loggedInPage, '/masterdata/deliveryCreationTests');
  await expect(loggedInPage.locator('body')).toBeVisible();
  await screenshot(loggedInPage, 'delivery-creation-test-03');
});
