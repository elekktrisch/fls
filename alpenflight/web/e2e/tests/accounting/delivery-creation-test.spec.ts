import { type Page, type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const FLIGHT_ID = 'flt-019e30c3-2c00-7001-8000-000000000010';

interface MockDeliveryItem {
  position?: number;
  articleNumber: string;
  itemText: string;
  quantity: number;
  unitType: string;
}

interface MockDeliveryCreationTest {
  id: string;
  name: string;
  description?: string;
  active: boolean;
  flightId: string;
  expectedDeliveryItems: MockDeliveryItem[];
  lastTestSuccessful?: boolean;
  lastTestResultMessage?: string;
  lastTestMatchedFilterIds?: string[];
}

interface MockDeliveryCreationTestListItem {
  id: string;
  testName: string;
  flightId: string;
  active: boolean;
  lastTestSuccessful?: boolean;
}

const MATCHED_RULE_ID = 'arf-019e30c3-2c00-7001-8000-000000000001';

const tieredExpectedItems: MockDeliveryItem[] = [
  { articleNumber: 'A-FT-1', itemText: 'Flight time tier 1', quantity: 1800, unitType: 'Sec' },
  { articleNumber: 'A-FT-2', itemText: 'Flight time tier 2', quantity: 1800, unitType: 'Sec' },
  { articleNumber: 'A-FT-3', itemText: 'Flight time tier 3', quantity: 600, unitType: 'Sec' },
];

const seededTest: MockDeliveryCreationTest = {
  id: 'dct-019e30c3-2c00-7001-8000-000000000001',
  name: 'Glider — tiered flight time',
  description: 'Tiered FlightTime decrement loop over the standard glider rules',
  active: true,
  flightId: FLIGHT_ID,
  expectedDeliveryItems: tieredExpectedItems,
  lastTestSuccessful: true,
  lastTestMatchedFilterIds: [MATCHED_RULE_ID],
};

const mockClubs = [{ id: CLUB_A_ID, name: 'Test Club A', slug: 'test-club-a' }];

const mockFlights = [
  {
    id: FLIGHT_ID,
    flightDate: '2026-05-01',
    aircraftImmatriculation: 'HB-3001',
    pilotName: 'Test Pilot',
  },
];

function toListItem(t: MockDeliveryCreationTest): MockDeliveryCreationTestListItem {
  const item: MockDeliveryCreationTestListItem = {
    id: t.id,
    testName: t.name,
    flightId: t.flightId,
    active: t.active,
  };
  if (t.lastTestSuccessful !== undefined) item.lastTestSuccessful = t.lastTestSuccessful;
  return item;
}

async function stubReferenceData(page: Page): Promise<void> {
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubs),
    }),
  );
  await page.route('**/api/v1/flights**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: mockFlights }),
    }),
  );
}

function toDetail(t: MockDeliveryCreationTest): Record<string, unknown> {
  return {
    id: t.id,
    flightId: t.flightId,
    testName: t.name,
    description: t.description,
    active: t.active,
    mustNotCreateDeliveryForFlight: false,
    ignoreRecipientName: false,
    ignoreRecipientAddress: false,
    ignoreRecipientPersonId: false,
    ignoreRecipientClubMemberNumber: false,
    ignoreDeliveryInformation: true,
    ignoreAdditionalInformation: true,
    ignoreItemPositioning: false,
    ignoreItemText: false,
    ignoreItemAdditionalInformation: false,
    expectedDelivery: { items: t.expectedDeliveryItems },
    expectedMatchedFilterIds: t.lastTestMatchedFilterIds ?? [],
    lastTestSuccessful: t.lastTestSuccessful,
    lastTestResultMessage: t.lastTestResultMessage,
    lastTestMatchedFilterIds: t.lastTestMatchedFilterIds ?? [],
  };
}

interface MockWriteRequest {
  testName: string;
  description?: string;
  active?: boolean;
  flightId: string;
}

function setupBackend(
  items: MockDeliveryCreationTest[],
  opts: { runOutcome?: { successful: boolean; engineItems: MockDeliveryItem[] } } = {},
) {
  let nextId = 1000;
  let lastDryRunItems: MockDeliveryItem[] = [];
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/deliverycreationtests\/(dct-[^/]+)$/);
    const runMatch = path.match(/^\/api\/v1\/deliverycreationtests\/(dct-[^/]+)\/run$/);
    const exampleMatch = path.match(/^\/api\/v1\/deliverycreationtests\/example\/(flt-[^/]+)$/);

    if (method === 'GET' && exampleMatch) {
      lastDryRunItems = tieredExpectedItems;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          delivery: { items: tieredExpectedItems },
          matchedFilterIds: [MATCHED_RULE_ID],
        }),
      });
      return;
    }
    if (method === 'POST' && runMatch) {
      const found = items.find((t) => t.id === runMatch[1]);
      if (!found) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const successful = opts.runOutcome?.successful ?? true;
      const engineItems = opts.runOutcome?.engineItems ?? found.expectedDeliveryItems;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          lastTestSuccessful: successful,
          lastTestResultMessage: successful ? '' : 'Items differ',
          lastTestCreatedDelivery: { items: engineItems },
          lastTestMatchedFilterIds: [MATCHED_RULE_ID],
        }),
      });
      return;
    }
    if (method === 'GET' && path === '/api/v1/deliverycreationtests') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = items.find((t) => t.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ? toDetail(found) : {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/deliverycreationtests') {
      const body = req.postDataJSON() as MockWriteRequest;
      const created: MockDeliveryCreationTest = {
        id: `dct-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
        name: body.testName,
        active: body.active ?? true,
        flightId: body.flightId,
        expectedDeliveryItems: lastDryRunItems,
      };
      if (body.description) created.description = body.description;
      items.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/deliverycreationtests/${created.id}` },
        body: JSON.stringify(toDetail(created)),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as MockWriteRequest;
      const idx = items.findIndex((t) => t.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const prev = items[idx]!;
      items[idx] = {
        ...prev,
        name: body.testName,
        active: body.active ?? prev.active,
        flightId: body.flightId,
        expectedDeliveryItems:
          lastDryRunItems.length > 0 ? lastDryRunItems : prev.expectedDeliveryItems,
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(toDetail(items[idx]!)),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = items.findIndex((t) => t.id === idMatch[1]);
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

async function bootBackend(
  page: Page,
  items: MockDeliveryCreationTest[],
  opts: { runOutcome?: { successful: boolean; engineItems: MockDeliveryItem[] } } = {},
): Promise<void> {
  await stubReferenceData(page);
  await page.route('**/api/v1/deliverycreationtests**', setupBackend(items, opts));
}

test('delivery-creation-test: a nav entry under masterdata reaches /deliverycreationtests (ENTER via nav)', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededTest }]);

  await page.goto('/clubs?lang=de');
  await enterViaNav(page, '/deliverycreationtests');

  await expect(page).toHaveURL('/deliverycreationtests');
  await expect(page.getByTestId('dct-table')).toBeVisible();
});

test('delivery-creation-test: list renders the club’s tests (name, active, last-result) tenant-scoped', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededTest }]);

  await page.goto('/deliverycreationtests');

  await expect(page.getByTestId('dct-table')).toBeVisible();
  const row = page.getByTestId(`dct-row-${seededTest.id}`);
  await expect(row).toBeVisible();
  await expect(row).toContainText('Glider — tiered flight time');
  await expect(page.getByTestId(`dct-row-result-${seededTest.id}`)).toBeVisible();
});

test('delivery-creation-test: create a test, dry-run the engine to fill expected items, save → appears → reload round-trips', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededTest }]);

  await page.goto('/deliverycreationtests');
  await page.getByTestId('dct-new-button').locator('button').click();
  await expect(page).toHaveURL('/deliverycreationtests/new');

  await page.getByTestId('dct-name').locator('input').fill('Motor — single-pass fees');
  await page.getByTestId('dct-flight-picker').selectOption(FLIGHT_ID);

  await page.getByTestId('dct-create-test-delivery').locator('button').click();
  for (const [i, item] of tieredExpectedItems.entries()) {
    const row = page.getByTestId(`dct-expected-item-${i}`);
    await expect(row).toContainText(item.articleNumber);
    await expect(row).toContainText(String(item.quantity));
    await expect(row).toContainText(item.unitType);
  }
  await expect(page.getByTestId('dct-expected-item-3')).toHaveCount(0);

  await page.getByTestId('dct-save-button').locator('button').click();
  await expect(page).toHaveURL('/deliverycreationtests');
  const created = page
    .locator('[data-testid^="dct-row-"]')
    .filter({ hasText: 'Motor — single-pass fees' });
  await expect(created).toBeVisible();

  await created.click();
  await expect(page).toHaveURL(/\/deliverycreationtests\/.+\/edit$/);
  await page.reload();
  await expect(page.getByTestId('dct-name').locator('input')).toHaveValue(
    'Motor — single-pass fees',
  );
  await expect(page.getByTestId('dct-expected-item-0')).toContainText('A-FT-1');
  await expect(page.getByTestId('dct-expected-item-2')).toContainText('A-FT-3');
});

test('delivery-creation-test: run a matching test → Success + matched-rule links navigate to /accountingrules', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededTest }]);

  await page.goto(`/deliverycreationtests/${seededTest.id}/edit?lang=en`);
  await page.getByTestId('dct-run').locator('button').click();

  await expect(page.getByTestId('dct-result')).toContainText('Success');
  const matchedLink = page.getByTestId(`dct-matched-rule-${MATCHED_RULE_ID}`);
  await expect(matchedLink).toBeVisible();
  await matchedLink.click();
  await expect(page).toHaveURL(`/accountingrules/${MATCHED_RULE_ID}/edit`);
});

test('delivery-creation-test: run a test whose engine output differs → Failure + the diff shows which items differed', async ({
  page,
}) => {
  const perturbed: MockDeliveryItem[] = [
    tieredExpectedItems[0]!,
    { ...tieredExpectedItems[1]!, quantity: 1500 },
    tieredExpectedItems[2]!,
  ];
  await bootBackend(page, [{ ...seededTest }], {
    runOutcome: { successful: false, engineItems: perturbed },
  });

  await page.goto(`/deliverycreationtests/${seededTest.id}/edit`);
  await page.getByTestId('dct-run').locator('button').click();

  await expect(page.getByTestId('dct-result')).toContainText('Failure');
  await expect(page.getByTestId('dct-diff')).toBeVisible();
  await expect(page.getByTestId('dct-diff-item-1')).toContainText('1500');
});

test('delivery-creation-test: cross-tenant GET of another club’s test → 404 (not-found, no edit form)', async ({
  page,
}, testInfo) => {
  allowConsoleErrors(testInfo, /\b404\b/);
  await bootBackend(page, [{ ...seededTest }]);
  const otherClubTestId = 'dct-019e30c3-2c00-7001-8000-0000000000ff';

  await page.goto(`/deliverycreationtests/${otherClubTestId}/edit`);

  await expect(page.getByTestId('dct-not-found')).toBeVisible();
  await expect(page.getByTestId('dct-edit-form')).toHaveCount(0);
  await expect(page.getByTestId('dct-name')).toHaveCount(0);
});
