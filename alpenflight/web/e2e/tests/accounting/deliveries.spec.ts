import { type Page, type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';


const DELIVERY_ID = 'dlv-019e30c3-2c00-7001-8000-000000000001';

const FLIGHT_ID = 'flt-019e30c3-2c00-7001-8000-000000000010';

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const PROCESS_STATE_PREPARED = 10;

interface MockDeliveryItem {
  position: number;
  articleNumber: string;
  itemText: string;
  quantity: number;
  unitType: string;
}

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

async function stubReferenceData(page: Page): Promise<void> {
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubs),
    }),
  );
}

function setupReadOnlyDeliveriesBackend(items: MockDelivery[]) {
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
  await page.route('**/api/v1/deliveries**', setupReadOnlyDeliveriesBackend(items));
}

test('deliveries: a nav entry under masterdata reaches /deliveries (ENTER via nav)', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededDelivery }]);

  await page.goto('/clubs?lang=de');
  await enterViaNav(page, '/deliveries');

  await expect(page).toHaveURL('/deliveries');
  await expect(page.getByTestId('del-table')).toBeVisible();
});

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
  await expect(page.getByTestId('del-table')).toContainText(String(seededDelivery.batchId));
  await expect(page.getByTestId(`del-state-${seededDelivery.id}`)).toBeVisible();
});

test('deliveries: view a delivery → read-only line items, frozen recipient, flight link; NO write actions', async ({
  page,
}) => {
  await bootBackend(page, [{ ...seededDelivery }]);

  await page.goto('/deliveries');
  await page.getByTestId(`del-row-${seededDelivery.id}`).click();
  await expect(page).toHaveURL(`/deliveries/${seededDelivery.id}`);

  await expect(page.getByTestId('del-detail')).toBeVisible();

  for (const [i, item] of seededDelivery.items.entries()) {
    const row = page.getByTestId(`del-item-${i}`);
    await expect(row).toContainText(String(item.position));
    await expect(row).toContainText(item.articleNumber);
    await expect(row).toContainText(item.itemText);
    await expect(row).toContainText(String(item.quantity));
    await expect(row).toContainText(item.unitType);
  }

  await expect(page.getByTestId('del-recipient-lastName')).toContainText('Pilot');
  await expect(page.getByTestId('del-recipient-city')).toContainText('Zürich');

  await expect(page.getByTestId('del-flight-link')).toBeVisible();

  await expect(page.getByTestId('del-book-button')).toHaveCount(0);
  await expect(page.getByTestId('del-delete-button')).toHaveCount(0);
});

test('deliveries: cross-tenant GET of another club’s delivery → 404 (not-found, no detail)', async ({
  page,
}, testInfo) => {
  allowConsoleErrors(testInfo, /\b404\b/);
  await bootBackend(page, [{ ...seededDelivery }]);
  const otherClubDeliveryId = 'dlv-019e30c3-2c00-7001-8000-0000000000ff';

  await page.goto(`/deliveries/${otherClubDeliveryId}`);

  await expect(page.getByTestId('del-not-found')).toBeVisible();
  await expect(page.getByTestId('del-detail')).toHaveCount(0);
});
