import { expect, test, type Page, type Route } from '@playwright/test';

import { enterViaNav } from '../_helpers/nav';

/**
 * Deliveries — the read-only `/deliveries` viewer (list + view).
 *
 * A Delivery is the immutable invoice-draft output of the rules engine: the
 * billing line items + a frozen recipient snapshot for one flight. This iteration
 * is READ-ONLY — the screen renders MIGRATED legacy deliveries tenant-scoped and
 * lets an operator browse them; create / book / delete + the state machine are
 * J-10b. This spec commits the SCREEN SHAPE: the `data-testid` contract, the
 * masterdata-nav entry, the paged+sortable list, the read-only view (line items +
 * frozen recipient block + flight link), the ABSENCE of any write affordance, and
 * the cross-tenant 404.
 *
 * The list / view / cross-tenant flows run LIVE against the built screen (T-06);
 * the `[migration/parity]` case stays `test.fixme` for the real-idp spec (T-10).
 *
 * Booted under the `chromium` (mock-auth) project: the principal is a mocked
 * SYSTEM_ADMINISTRATOR + CLUB_ADMINISTRATOR (see `app.config.mock.ts`), so the
 * masterdata nav renders even though the role gate truly lives on the server. All
 * `/api/v1/*` calls are intercepted via `page.route` — NO live backend, NO
 * real-idp, NO DB (the real migrated-parity run is the real-idp spec, T-10).
 *
 * Legacy contract (flsweb/src/masterdata/deliveries/, read at carve):
 *   - List: DeliveriesEditController.js — `POST /api/v1/deliveries/page/{start}/{size}`
 *     (the canonical AlpenFlight paged-read seam), the club's deliveries (delivery
 *     number / recipient / batch / state), default sort BatchId desc then
 *     RecipientName asc, tenant-scoped, click-to-view.
 *   - View: deliveries-edit.html — read-only line items (Position / ArticleNumber /
 *     ItemText / Quantity / UnitType), the frozen RecipientDetails block, and the
 *     FlightInformation link. No book/delete actions render this iteration.
 *
 * data-testid contract (committed here; the screen wires these T-06):
 *   del-table              — the list table
 *   del-row-<id>           — one delivery row (links to its view page)
 *   del-state-<id>         — that row's process-state badge cell
 *   del-detail             — the view root (absent on a not-found load)
 *   del-item-<n>           — one read-only line-item row (position/article/itemText/qty/unit)
 *   del-recipient-<field>  — one frozen recipient-snapshot field
 *   del-flight-link        — the read-only flight link
 *   del-not-found          — the cross-tenant / missing-id not-found marker
 */

const DELIVERY_ID = 'dlv-019e30c3-2c00-7001-8000-000000000001';

const FLIGHT_ID = 'flt-019e30c3-2c00-7001-8000-000000000010';

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

// process_state_id 10 Prepared / 20 Booked / 30 Error / 99 Cancelled — displayed,
// never transitioned this iteration.
const PROCESS_STATE_PREPARED = 10;

interface MockDeliveryItem {
  position: number;
  articleNumber: string;
  itemText: string;
  quantity: number;
  unitType: string;
}

// The 9-field frozen recipient snapshot (OR Art. 957a) — captured at delivery
// time, never re-resolved from the live person.
interface MockRecipient {
  firstName: string;
  lastName: string;
  clubMemberNumber: string;
  addressLine1: string;
  addressLine2?: string;
  zipCode: string;
  city: string;
  country: string;
  personId?: string;
}

interface MockFlightInfo {
  flightId: string;
  flightDate: string;
  aircraftImmatriculation: string;
  pilotName: string;
}

interface MockDelivery {
  id: string;
  deliveryNumber: number;
  batchId: number;
  processStateId: number;
  recipient: MockRecipient;
  flight: MockFlightInfo;
  items: MockDeliveryItem[];
}

const tieredItems: MockDeliveryItem[] = [
  {
    position: 1,
    articleNumber: 'A-FT-1',
    itemText: 'Flight time tier 1',
    quantity: 1800,
    unitType: 'Sec',
  },
  {
    position: 2,
    articleNumber: 'A-FT-2',
    itemText: 'Flight time tier 2',
    quantity: 1800,
    unitType: 'Sec',
  },
  { position: 3, articleNumber: 'A-LDG', itemText: 'Landing tax', quantity: 1, unitType: 'Pcs' },
];

const seededDelivery: MockDelivery = {
  id: DELIVERY_ID,
  deliveryNumber: 2001,
  batchId: 42,
  processStateId: PROCESS_STATE_PREPARED,
  recipient: {
    firstName: 'Test',
    lastName: 'Pilot',
    clubMemberNumber: '1234',
    addressLine1: 'Flugplatzstrasse 1',
    zipCode: '8000',
    city: 'Zürich',
    country: 'CH',
    personId: 'per-019e30c3-2c00-7001-8000-000000000001',
  },
  flight: {
    flightId: FLIGHT_ID,
    flightDate: '2026-05-01',
    aircraftImmatriculation: 'HB-3001',
    pilotName: 'Test Pilot',
  },
  items: tieredItems,
};

const mockClubs = [{ id: CLUB_A_ID, name: 'Test Club A', slug: 'test-club-a' }];

interface MockDeliveryListRow {
  id: string;
  deliveryNumber: number;
  recipientName: string;
  batchId: number;
  processStateId: number;
}

function toListRow(d: MockDelivery): MockDeliveryListRow {
  return {
    id: d.id,
    deliveryNumber: d.deliveryNumber,
    recipientName: `${d.recipient.lastName} ${d.recipient.firstName}`,
    batchId: d.batchId,
    processStateId: d.processStateId,
  };
}

/** Project a mock delivery onto the read DETAIL shape the view consumes. */
function toDetail(d: MockDelivery): Record<string, unknown> {
  return {
    id: d.id,
    deliveryNumber: d.deliveryNumber,
    batchId: d.batchId,
    processStateId: d.processStateId,
    recipient: d.recipient,
    flight: d.flight,
    items: d.items,
  };
}

/** Stub the read-only lookups the chrome consumes (clubs for the tenant switcher). */
async function stubReferenceData(page: Page): Promise<void> {
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubs),
    }),
  );
}

/**
 * In-memory `/api/v1/deliveries` READ backend. POST paged list (the canonical
 * AlpenFlight paged-read seam) + GET by id (404 when absent — the cross-tenant
 * case). No write verbs on a row: the screen is a viewer this iteration, so a
 * PUT/DELETE here is out of contract and falls through. The list envelope mirrors
 * the SPA-compat page shape (`items` + `pageStart` + `pageSize` + `totalRows`).
 */
function setupBackend(items: MockDelivery[]) {
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/deliveries\/(dlv-[^/]+)$/);
    const pageMatch = path.match(/^\/api\/v1\/deliveries\/page\/(\d+)\/(\d+)$/);

    if (method === 'POST' && pageMatch) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: items.map(toListRow),
          pageStart: Number(pageMatch[1]),
          pageSize: Number(pageMatch[2]),
          totalRows: items.length,
        }),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = items.find((d) => d.id === idMatch[1]);
      // A cross-tenant id is absent from this club's `items` → 404 (the
      // @TenantId-scoped finder never returns another club's row).
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ? toDetail(found) : {}),
      });
      return;
    }
    await route.fallback();
  };
}

async function bootBackend(page: Page, items: MockDelivery[]): Promise<void> {
  await stubReferenceData(page);
  await page.route('**/api/v1/deliveries**', setupBackend(items));
}

// ── nav entry (chrome-reachable contract) ──────────────────────────────────
test('deliveries: a nav entry under masterdata reaches /deliveries (ENTER via nav)', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededDelivery }]);

  await page.goto('/clubs?lang=de');
  await enterViaNav(page, '/deliveries');

  await expect(page).toHaveURL('/deliveries');
  await expect(page.getByTestId('del-table')).toBeVisible();
});

// ── list ───────────────────────────────────────────────────────────────────
test('deliveries: list renders the club’s deliveries (number, recipient, batch, state) tenant-scoped', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededDelivery }]);

  await page.goto('/deliveries');

  await expect(page.getByTestId('del-table')).toBeVisible();
  const row = page.getByTestId(`del-row-${seededDelivery.id}`);
  await expect(row).toBeVisible();
  await expect(row).toContainText(String(seededDelivery.deliveryNumber));
  await expect(row).toContainText('Pilot Test');
  // The batch + state badge are sibling cells of the row link.
  await expect(page.getByTestId('del-table')).toContainText(String(seededDelivery.batchId));
  await expect(page.getByTestId(`del-state-${seededDelivery.id}`)).toBeVisible();
});

// ── view: read-only line items + frozen recipient + flight link ──────────────
test('deliveries: view a delivery → read-only line items, frozen recipient, flight link; NO write actions', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededDelivery }]);

  await page.goto('/deliveries');
  await page.getByTestId(`del-row-${seededDelivery.id}`).click();
  await expect(page).toHaveURL(`/deliveries/${seededDelivery.id}`);

  await expect(page.getByTestId('del-detail')).toBeVisible();

  // Read-only line items — engine output, never hand-editable. Each row shows
  // position / article / itemText / quantity / unitType.
  for (const [i, item] of seededDelivery.items.entries()) {
    const row = page.getByTestId(`del-item-${i}`);
    await expect(row).toContainText(String(item.position));
    await expect(row).toContainText(item.articleNumber);
    await expect(row).toContainText(item.itemText);
    await expect(row).toContainText(String(item.quantity));
    await expect(row).toContainText(item.unitType);
  }

  // The frozen recipient snapshot renders as read-only fields.
  await expect(page.getByTestId('del-recipient-lastName')).toContainText('Pilot');
  await expect(page.getByTestId('del-recipient-city')).toContainText('Zürich');

  // The read-only flight link.
  await expect(page.getByTestId('del-flight-link')).toBeVisible();

  // READ-ONLY this iteration: there is NO book / delete affordance anywhere on
  // the view (the write side is J-10b).
  await expect(page.getByTestId('del-book-button')).toHaveCount(0);
  await expect(page.getByTestId('del-delete-button')).toHaveCount(0);
});

// ── cross-tenant isolation ───────────────────────────────────────────────────
test('deliveries: cross-tenant GET of another club’s delivery → 404 (not-found, no detail)', async ({
  page,
}) => {
  // The @TenantId 404 is proven for real in the real-idp parity spec (T-10); here
  // the mock backend 404s an id absent from this club's set.
  await bootBackend(page, [{ ...seededDelivery }]);
  const otherClubDeliveryId = 'dlv-019e30c3-2c00-7001-8000-0000000000ff';

  await page.goto(`/deliveries/${otherClubDeliveryId}`);

  await expect(page.getByTestId('del-not-found')).toBeVisible();
  await expect(page.getByTestId('del-detail')).toHaveCount(0);
});

// ── migration / parity placeholder ───────────────────────────────────────────
test.fixme('deliveries: migrated legacy deliveries render under their migrated TestClub tenant (parity)', async ({
  page,
}) => {
  // The migrated done-bar — the Delivery + DeliveryItem mappers bind in T-05 and
  // the bit-exact migrated assertion is the real-idp parity spec (T-10), which
  // renders MIGRATED legacy deliveries against the real chain (not this mock).
  await bootBackend(page, [{ ...seededDelivery }]);

  await page.goto('/deliveries');

  await expect(page.getByTestId('del-table')).toBeVisible();
});
