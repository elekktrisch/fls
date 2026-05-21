import { expect, test, type Route } from '@playwright/test';

/**
 * /admin/locations sysadmin cross-tenant Locations view (S-049c F3).
 * Mocked backend per FE CLAUDE.md §8: `page.route` fulfills the Clubs +
 * LocationsAdmin endpoints so no live server is required. Mocked principal
 * is SYSTEM_ADMINISTRATOR.
 */

interface MockClub {
  id: string;
  name: string;
  clubKey: string;
  countryId: string;
  clubStateId: string;
}

interface MockLocation {
  id: string;
  locationName: string;
  icaoCode?: string;
  locationTypeCode?: string;
  isAirfield: boolean;
  isFastEntryRecord: boolean;
}

const CLUB_A: MockClub = {
  id: 'clb-019e30c3-2c00-7001-8000-0000000000a1',
  name: 'Alpha Soaring',
  clubKey: 'ALPHA',
  countryId: '019e2e15-2c00-74be-8000-0000000004be',
  clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
};
const CLUB_B: MockClub = {
  id: 'clb-019e30c3-2c00-7001-8000-0000000000a2',
  name: 'Bravo Wings',
  clubKey: 'BRAVO',
  countryId: '019e2e15-2c00-74be-8000-0000000004be',
  clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
};

const LOCATIONS_BY_CLUB: Record<string, MockLocation[]> = {
  [CLUB_A.id]: [
    {
      id: 'loc-019e30c3-2c00-7001-8000-000000000A01',
      locationName: 'Alpha Airfield',
      icaoCode: 'LSZA',
      locationTypeCode: 'AIRPORT',
      isAirfield: true,
      isFastEntryRecord: false,
    },
  ],
  [CLUB_B.id]: [
    {
      id: 'loc-019e30c3-2c00-7001-8000-000000000B01',
      locationName: 'Bravo Strip',
      icaoCode: 'LSZB',
      locationTypeCode: 'GLIDER_STRIP',
      isAirfield: true,
      isFastEntryRecord: false,
    },
    {
      id: 'loc-019e30c3-2c00-7001-8000-000000000B02',
      locationName: 'Bravo Outlanding',
      icaoCode: undefined,
      locationTypeCode: 'OUTLANDING',
      isAirfield: false,
      isFastEntryRecord: false,
    },
  ],
};

function fulfillJson(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

test.describe('Locations admin (cross-tenant)', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/clubs', (route) => fulfillJson(route, [CLUB_A, CLUB_B]));
    await page.route(/.*\/api\/v1\/admin\/locations\/([^/]+)$/, (route) => {
      const url = new URL(route.request().url());
      const clubId = decodeURIComponent(url.pathname.split('/').pop() ?? '');
      const items = LOCATIONS_BY_CLUB[clubId] ?? [];
      return fulfillJson(route, items);
    });
  });

  test('picks a club and shows that club’s Locations only', async ({ page }) => {
    await page.goto('/admin/locations');
    await expect(page.getByTestId('admin-club-picker')).toBeVisible();
    // Picker open + select Club B
    await page.getByTestId('admin-club-picker').click();
    await page.getByText('Bravo Wings').click();

    await expect(page.getByTestId('admin-locations-table')).toBeVisible();
    await expect(
      page.getByTestId(`admin-location-row-${LOCATIONS_BY_CLUB[CLUB_B.id]![0]!.id}`),
    ).toContainText('Bravo Strip');
    await expect(page.getByText('Alpha Airfield')).toHaveCount(0);
  });

  test('switching club replaces the list', async ({ page }) => {
    await page.goto('/admin/locations');
    await page.getByTestId('admin-club-picker').click();
    await page.getByText('Alpha Soaring').click();
    await expect(page.getByText('Alpha Airfield')).toBeVisible();

    await page.getByTestId('admin-club-picker').click();
    await page.getByText('Bravo Wings').click();
    await expect(page.getByText('Bravo Strip')).toBeVisible();
    await expect(page.getByText('Alpha Airfield')).toHaveCount(0);
  });

  test('row delete fires admin DELETE and refreshes', async ({ page }) => {
    let deleteHits = 0;
    await page.route(
      /.*\/api\/v1\/admin\/locations\/[^/]+\/[^/]+$/,
      async (route) => {
        if (route.request().method() === 'DELETE') {
          deleteHits += 1;
          return route.fulfill({ status: 204, body: '' });
        }
        return route.continue();
      },
    );
    page.on('dialog', (dialog) => dialog.accept());

    await page.goto('/admin/locations');
    await page.getByTestId('admin-club-picker').click();
    await page.getByText('Bravo Wings').click();
    await expect(page.getByText('Bravo Strip')).toBeVisible();

    await page.getByTestId(`admin-location-delete-${LOCATIONS_BY_CLUB[CLUB_B.id]![0]!.id}`).click();
    await expect.poll(() => deleteHits).toBe(1);
  });
});
