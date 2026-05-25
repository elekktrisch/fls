import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * Flight delete-from-list flow (S-067 AC).
 *
 * The list-page kebab gains a Delete item (mirrors the legacy two-step
 * confirm). Backend already gates DELIVERY_BOOKED via
 * FlightStateGateException; the UI mirrors the gate by suppressing the
 * Delete affordance on those rows (no surprise 409).
 *
 * Mocks /api/v1/flights with stateful handlers so the test observes the
 * DELETE request body + post-delete refetch in order.
 *
 * Per alpenflight/web/CLAUDE.md §8 — screenshots at each asserted state
 * under screenshots/flights/delete-*.png.
 */

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';
const GLIDER_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';
const PROC_STATE_VALID = '019e2e15-2c00-7100-8000-000000007002';
const PROC_STATE_DELIVERY_BOOKED = '019e2e15-2c00-7a9e-8000-000000003a9e';
const AC_GLI = 'ac-019e30c3-2c00-7001-8000-000000000a01';

interface MockFlightRow {
  id: string;
  flightAircraftType: 'GLIDER' | 'TOW' | 'MOTOR';
  flightDate: string;
  startDateTime: string;
  ldgDateTime: string;
  aircraftId: string;
  processStateId: string;
  processState: string;
  airState: string;
}

const FL_DELETABLE: MockFlightRow = {
  id: 'fl-019e30c3-2c00-7001-8000-000000000010',
  flightAircraftType: 'GLIDER',
  flightDate: '2026-05-21',
  startDateTime: '2026-05-21T08:00:00Z',
  ldgDateTime: '2026-05-21T09:30:00Z',
  aircraftId: AC_GLI,
  processStateId: PROC_STATE_VALID,
  processState: 'VALID',
  airState: 'LANDED',
};

const FL_LOCKED: MockFlightRow = {
  id: 'fl-019e30c3-2c00-7001-8000-000000000020',
  flightAircraftType: 'GLIDER',
  flightDate: '2026-05-22',
  startDateTime: '2026-05-22T08:00:00Z',
  ldgDateTime: '2026-05-22T09:30:00Z',
  aircraftId: AC_GLI,
  processStateId: PROC_STATE_DELIVERY_BOOKED,
  processState: 'DELIVERY_BOOKED',
  airState: 'LANDED',
};

async function stubReferenceData(page: Page): Promise<void> {
  for (const path of [
    '**/api/v1/countries**',
    '**/api/v1/club-states**',
    '**/api/v1/location-types**',
    '**/api/v1/aircraft-types**',
    '**/api/v1/aircraft-states**',
    '**/api/v1/counter-unit-types**',
  ]) {
    await page.route(path, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: CLUB_A_ID, name: 'A', slug: 'a' }]),
    }),
  );
  await page.route('**/api/v1/aircraft', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: AC_GLI,
          ownerClubId: CLUB_A_ID,
          immatriculation: 'HB-GLI',
          aircraftTypeId: GLIDER_TYPE_ID,
          aircraftTypeCode: 'GLIDER',
          hasEngine: false,
          isTowingAircraft: false,
        },
      ]),
    }),
  );
}

interface FlightsBackend {
  handler: (route: Route) => Promise<void>;
  observed: { deletes: { id: string; ifMatch: string | null }[] };
}

function setupBackend(initial: MockFlightRow[]): FlightsBackend {
  const rows = [...initial];
  const observed: FlightsBackend['observed'] = { deletes: [] };
  return {
    observed,
    handler: async (route: Route) => {
      const req = route.request();
      const url = new URL(req.url());
      const method = req.method();
      const match = url.pathname.match(/^\/api\/v1\/flights\/([^/]+)$/);

      if (method === 'GET' && url.pathname === '/api/v1/flights') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: rows }),
        });
        return;
      }
      if (method === 'DELETE' && match) {
        const id = match[1]!;
        observed.deletes.push({
          id,
          ifMatch: req.headers()['if-match'] ?? null,
        });
        const idx = rows.findIndex((r) => r.id === id);
        if (idx >= 0) rows.splice(idx, 1);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      await route.fallback();
    },
  };
}

test.describe('flights list — delete', () => {
  test('kebab Delete → confirm → row removed; DELETE sent with If-Match: *', async ({ page }) => {
    await stubReferenceData(page);
    const backend = setupBackend([FL_DELETABLE]);
    await page.route('**/api/v1/flights**', backend.handler);

    await page.goto('/flights');
    await expect(page.getByTestId('flights-summary')).toContainText('1 flight');
    await page.screenshot({ path: 'screenshots/flights/delete-01-row-visible.png', fullPage: true });

    // Open kebab → Delete entry rendered (not locked).
    await page.getByTestId(`flights-kebab-${FL_DELETABLE.id}`).click();
    await expect(page.getByTestId(`flights-delete-${FL_DELETABLE.id}`)).toBeVisible();
    await page.screenshot({
      path: 'screenshots/flights/delete-02-kebab-open.png',
      fullPage: true,
    });

    await page.getByTestId(`flights-delete-${FL_DELETABLE.id}`).click();
    await expect(page.getByTestId('af-dialog-title')).toContainText('Delete this flight?');
    await page.screenshot({
      path: 'screenshots/flights/delete-03-dialog-open.png',
      fullPage: true,
    });

    await page.getByTestId('af-dialog-confirm').click();

    // DELETE observed with If-Match: * and row removed from the list.
    await expect.poll(() => backend.observed.deletes.length).toBe(1);
    expect(backend.observed.deletes[0]!.id).toBe(FL_DELETABLE.id);
    expect(backend.observed.deletes[0]!.ifMatch).toBe('*');
    await expect(page.getByTestId('flights-empty')).toContainText('No flights yet');
    await page.screenshot({
      path: 'screenshots/flights/delete-04-after-delete.png',
      fullPage: true,
    });
  });

  test('Cancel on the confirm dialog does NOT fire a DELETE', async ({ page }) => {
    await stubReferenceData(page);
    const backend = setupBackend([FL_DELETABLE]);
    await page.route('**/api/v1/flights**', backend.handler);

    await page.goto('/flights');
    await page.getByTestId(`flights-kebab-${FL_DELETABLE.id}`).click();
    await page.getByTestId(`flights-delete-${FL_DELETABLE.id}`).click();
    await expect(page.getByTestId('af-dialog-title')).toBeVisible();

    await page.getByTestId('af-dialog-dismiss').click();

    // Dialog gone, no DELETE issued, row still listed.
    await expect(page.getByTestId('af-dialog-title')).toHaveCount(0);
    expect(backend.observed.deletes).toHaveLength(0);
    await expect(page.getByTestId(`flights-row-${FL_DELETABLE.id}`)).toBeVisible();
    await page.screenshot({
      path: 'screenshots/flights/delete-05-cancelled.png',
      fullPage: true,
    });
  });

  test('DELIVERY_BOOKED row shows Delete (locked) placeholder, no actionable Delete', async ({
    page,
  }) => {
    await stubReferenceData(page);
    const backend = setupBackend([FL_LOCKED]);
    await page.route('**/api/v1/flights**', backend.handler);

    await page.goto('/flights');
    await page.getByTestId(`flights-kebab-${FL_LOCKED.id}`).click();
    await expect(page.getByTestId(`flights-delete-${FL_LOCKED.id}`)).toHaveCount(0);
    await expect(page.getByTestId(`flights-delete-disabled-${FL_LOCKED.id}`)).toBeVisible();
    await page.screenshot({
      path: 'screenshots/flights/delete-06-locked-row.png',
      fullPage: true,
    });
  });
});
