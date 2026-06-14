import { expect, test, type Page, type Route } from '@playwright/test';

import { enterViaNav } from '../_helpers/nav';

/**
 * J-9 — Delivery-creation-test harness (`/deliverycreationtests` list + edit).
 *
 * The rules-engine proof surface. An admin picks a Flight, dry-runs the engine
 * to capture the expected `DeliveryItem` set (no persist), then re-runs it and
 * diffs the engine output against the stored expectation — the operator's daily
 * rule-tuning tool. This spec commits the SCREEN SHAPE: the `data-testid`
 * contract, the masterdata-nav entry, the dry-run/save/round-trip flow, the
 * run → Success/Failure + matched-rule links, the failure diff UI, and the
 * cross-tenant 404. The screen does not exist yet (built T-16/T-17/T-18,
 * thickened to real assertions T-19), so every flow past the nav contract is
 * `test.fixme` — the contract is what this task pins.
 *
 * Booted under the `chromium` (mock-auth) project: the principal is a mocked
 * SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR (the dual-role mock persona, see
 * `app.config.mock.ts`), so the masterdata nav + mutation affordances render
 * even though the role gate truly lives on the server. All `/api/v1/*` calls
 * are intercepted via `page.route` — NO live backend, NO real-idp, NO DB.
 *
 * Legacy contract (flsweb/src/masterdata/deliveryCreationTests/, read at carve):
 *   - List: deliveryCreationTests-table.html — the club's tests (active /
 *     name / description / last-result status), tenant-scoped, click-to-edit.
 *   - Edit: deliveryCreationTests-edit.html + DeliveryCreationTestsEditController.js.
 *     Two engine actions:
 *       createTestDelivery() → generateExampleDelivery(FlightId): the engine
 *         runs as a DRY-RUN (NOT persisted) and fills the expected DeliveryItem
 *         set + the formatted JSON textarea.
 *       runTest(id) → runs the engine vs the stored expectation:
 *         LastTestSuccessful + LastTestResultMessage (the diff) +
 *         LastTestMatchedAccountingRuleFilterIds (clickable → the rule filter).
 *
 * Parity facts (J-9 journey "Going-in state + operator decisions"):
 *   - the dry-run produces DeliveryItems via the legacy CODE-order pipeline
 *     (IgnoreFlight → Recipient → NoLandingTax → FlightTime loop → EngineTime
 *     loop → InstructorFee → tow-recurse → AdditionalFuelFee → StartTax →
 *     LandingTax → VsfFee); the FlightTime decrement loop emits TIERED items.
 *   - a matched AccountingRuleFilter id links to /accountingrules/<id> (J-8).
 *   - cross-tenant load is a tenant-leak the new stack closes via @TenantId, so
 *     another club's test id → 404 (the edit page surfaces not-found, not the row).
 *
 * data-testid contract (committed here; the screen wires these T-16/T-17/T-18):
 *   dct-table                 — the list table
 *   dct-row-<id>              — one test row (links to its edit page)
 *   dct-row-result-<id>       — that row's last-test-result status cell
 *   dct-new-button            — "new test" affordance on the list
 *   dct-edit-form             — the edit form root (absent on a not-found load)
 *   dct-name                  — the test-name input
 *   dct-flight-picker         — the Flight picker the dry-run runs against
 *   dct-create-test-delivery  — "Create test delivery" (the dry-run trigger)
 *   dct-expected-item-<n>     — one expected DeliveryItem row (the dry-run fill)
 *   dct-save-button           — save the test
 *   dct-run                   — "Run test" (engine-vs-expectation)
 *   dct-result                — the run verdict (Success / Failure)
 *   dct-diff                  — the run-failure cell-level diff UI
 *   dct-diff-item-<n>         — one differing DeliveryItem row in the diff
 *   dct-matched-rule-<id>     — a matched-rule link → /accountingrules/<id>
 *   dct-not-found             — the cross-tenant / missing-id not-found marker
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const FLIGHT_ID = 'flt-019e30c3-2c00-7001-8000-000000000010';

interface MockDeliveryItem {
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
  lastTestMatchedAccountingRuleFilterIds?: string[];
}

interface MockDeliveryCreationTestListItem {
  id: string;
  name: string;
  active: boolean;
  lastTestSuccessful?: boolean;
}

const MATCHED_RULE_ID = 'arf-019e30c3-2c00-7001-8000-000000000001';

/**
 * Tiered FlightTime-loop output (the sacred-cow R3 mechanism): first 30min @
 * rate A, next 30 @ rate B, remainder @ rate C → 3 items. Seeded as the stored
 * expectation so the run-success path matches it bit-for-bit and the
 * run-failure path can diff against a perturbed engine output.
 */
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
  lastTestMatchedAccountingRuleFilterIds: [MATCHED_RULE_ID],
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
  const item: MockDeliveryCreationTestListItem = { id: t.id, name: t.name, active: t.active };
  if (t.lastTestSuccessful !== undefined) item.lastTestSuccessful = t.lastTestSuccessful;
  return item;
}

/** Stub the read-only lookups the edit form consumes (clubs + the flight picker). */
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
      body: JSON.stringify(mockFlights),
    }),
  );
}

interface MockWriteRequest {
  name: string;
  description?: string;
  active?: boolean;
  flightId: string;
  expectedDeliveryItems: MockDeliveryItem[];
}

/**
 * In-memory `/api/v1/delivery-creation-tests` backend. GET list, GET by id
 * (404 when absent — the cross-tenant case), POST (201 + Location), PUT, DELETE.
 * Plus the two engine endpoints:
 *   POST .../example/:flightId — the dry-run; returns the expected item set,
 *     does NOT persist (mirrors generateExampleDelivery).
 *   POST .../:id/run          — runs the engine vs the stored expectation;
 *     returns the verdict + matched-rule ids (+ the diff items on a failure).
 * `runOutcome` lets a test force a Failure with a perturbed engine output so
 * the diff UI has something to render.
 */
function setupBackend(
  items: MockDeliveryCreationTest[],
  opts: { runOutcome?: { successful: boolean; engineItems: MockDeliveryItem[] } } = {},
) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/delivery-creation-tests\/(dct-[^/]+)$/);
    const runMatch = path.match(/^\/api\/v1\/delivery-creation-tests\/(dct-[^/]+)\/run$/);
    const exampleMatch = path.match(/^\/api\/v1\/delivery-creation-tests\/example\/(flt-[^/]+)$/);

    if (method === 'POST' && exampleMatch) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ deliveryItems: tieredExpectedItems }),
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
          lastTestCreatedDeliveryItems: engineItems,
          lastTestMatchedAccountingRuleFilterIds: [MATCHED_RULE_ID],
        }),
      });
      return;
    }
    if (method === 'GET' && path === '/api/v1/delivery-creation-tests') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = items.find((t) => t.id === idMatch[1]);
      // A cross-tenant id is simply absent from this club's `items` → 404
      // (the @TenantId-scoped finder never returns another club's row).
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/delivery-creation-tests') {
      const body = req.postDataJSON() as MockWriteRequest;
      const created: MockDeliveryCreationTest = {
        id: `dct-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
        name: body.name,
        active: body.active ?? true,
        flightId: body.flightId,
        expectedDeliveryItems: body.expectedDeliveryItems ?? [],
      };
      if (body.description) created.description = body.description;
      items.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/delivery-creation-tests/${created.id}` },
        body: JSON.stringify(created),
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
        name: body.name,
        active: body.active ?? prev.active,
        flightId: body.flightId,
        expectedDeliveryItems: body.expectedDeliveryItems ?? prev.expectedDeliveryItems,
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items[idx]),
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
  await page.route('**/api/v1/delivery-creation-tests**', setupBackend(items, opts));
}

// ── nav entry (chrome-reachable contract) ──────────────────────────────────
test('delivery-creation-test: a nav entry under masterdata reaches /deliverycreationtests (ENTER via nav)', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededTest }]);

  await page.goto('/clubs?lang=de');
  await enterViaNav(page, '/deliverycreationtests');

  await expect(page).toHaveURL('/deliverycreationtests');
  await expect(page.getByTestId('dct-table')).toBeVisible();
});

// ── list ───────────────────────────────────────────────────────────────────
test.fixme('delivery-creation-test: list renders the club’s tests (name, active, last-result) tenant-scoped', async ({
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

// ── create: dry-run → fill expected items → save → round-trip ────────────────
test.fixme('delivery-creation-test: create a test, dry-run the engine to fill expected items, save → appears → reload round-trips', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededTest }]);

  await page.goto('/deliverycreationtests');
  await page.getByTestId('dct-new-button').locator('button').click();
  await expect(page).toHaveURL('/deliverycreationtests/new');

  await page.getByTestId('dct-name').fill('Motor — single-pass fees');
  await page.getByTestId('dct-flight-picker').selectOption(FLIGHT_ID);

  // Create test delivery = the dry-run: the engine runs (NOT persisted) and
  // fills the expected DeliveryItem set (the tiered FlightTime output here).
  await page.getByTestId('dct-create-test-delivery').locator('button').click();
  await expect(page.getByTestId('dct-expected-item-0')).toContainText('A-FT-1');
  await expect(page.getByTestId('dct-expected-item-2')).toContainText('A-FT-3');

  await page.getByTestId('dct-save-button').locator('button').click();
  await expect(page).toHaveURL('/deliverycreationtests');
  const created = page
    .locator('[data-testid^="dct-row-"]')
    .filter({ hasText: 'Motor — single-pass fees' });
  await expect(created).toBeVisible();

  await created.click();
  await expect(page).toHaveURL(/\/deliverycreationtests\/.+\/edit$/);
  await page.reload();
  await expect(page.getByTestId('dct-name')).toHaveValue('Motor — single-pass fees');
  await expect(page.getByTestId('dct-expected-item-0')).toContainText('A-FT-1');
});

// ── run: Success + matched-rule links → /accountingrules ─────────────────────
test.fixme('delivery-creation-test: run a matching test → Success + matched-rule links navigate to /accountingrules', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededTest }]);

  await page.goto(`/deliverycreationtests/${seededTest.id}/edit`);
  await page.getByTestId('dct-run').locator('button').click();

  await expect(page.getByTestId('dct-result')).toContainText('Success');
  const matchedLink = page.getByTestId(`dct-matched-rule-${MATCHED_RULE_ID}`);
  await expect(matchedLink).toBeVisible();
  await matchedLink.click();
  await expect(page).toHaveURL(`/accountingrules/${MATCHED_RULE_ID}/edit`);
});

// ── run-failure: the cell-level diff UI ──────────────────────────────────────
test.fixme('delivery-creation-test: run a test whose engine output differs → Failure + the diff shows which items differed', async ({
  page,
}) => {
  // The engine returns a perturbed tier-2 quantity, so the run verdict is
  // Failure and the diff surfaces the differing DeliveryItem.
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

// ── cross-tenant isolation ───────────────────────────────────────────────────
test.fixme('delivery-creation-test: cross-tenant GET of another club’s test → 404 (not-found, no edit form)', async ({
  page,
}) => {
  // The new stack scopes by @TenantId, so another club's id is never in this
  // club's list → GET by that id → 404 → the edit page surfaces not-found.
  await bootBackend(page, [{ ...seededTest }]);
  const otherClubTestId = 'dct-019e30c3-2c00-7001-8000-0000000000ff';

  await page.goto(`/deliverycreationtests/${otherClubTestId}/edit`);

  await expect(page.getByTestId('dct-not-found')).toBeVisible();
  await expect(page.getByTestId('dct-edit-form')).toHaveCount(0);
  await expect(page.getByTestId('dct-name')).toHaveCount(0);
});
