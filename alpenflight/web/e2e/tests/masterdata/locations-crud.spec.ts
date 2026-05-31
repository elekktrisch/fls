import { expect, test, type Route } from '@playwright/test';

/**
 * Locations CRUD shape. Parity port of legacy
 * `e2e/tests/masterdata/locations-crud.spec.ts` — observable CRUD behavior
 * only, not legacy URL shape or response envelope (ADR 0022). The page is
 * booted under the `mock-auth` Angular configuration; the principal is a
 * mocked SYSTEM_ADMINISTRATOR so the mutation affordances render. All
 * `/api/v1/*` calls are intercepted via `page.route` so no live backend
 * is required.
 */

interface MockLocationType {
  id: string;
  code: string;
  description: string;
  isAirfield: boolean;
}

interface MockInOutboundPoint {
  id: string;
  pointName: string;
  pointType?: string;
  direction?: string;
  description?: string;
}

interface MockLocation {
  id: string;
  locationName: string;
  locationShortName?: string;
  countryId: string;
  locationTypeId: string;
  icaoCode?: string;
  latitude?: string;
  longitude?: string;
  description?: string;
  isInboundRouteRequired: boolean;
  isOutboundRouteRequired: boolean;
  isFastEntryRecord: boolean;
  inOutboundPoints: MockInOutboundPoint[];
}

interface MockLocationListItem {
  id: string;
  locationName: string;
  locationShortName?: string;
  icaoCode?: string;
  locationTypeCode?: string;
  isAirfield: boolean;
  isFastEntryRecord: boolean;
}

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const AIRPORT_TYPE_ID = '019e2e15-2c00-7110-8000-000000001110';

const mockCountries = [{ id: CH_COUNTRY_ID, iso2Code: 'CH', name: 'Switzerland' }];
const mockClubStates = [
  { id: '019e2e15-2c00-7bb8-8000-000000000bb8', code: 'ACTIVE', name: 'Active' },
];
const mockLocationTypes: MockLocationType[] = [
  { id: AIRPORT_TYPE_ID, code: 'AIRPORT', description: 'Airport', isAirfield: true },
];

const seedLocation: MockLocation = {
  id: 'loc-019e30c3-2c00-7001-8000-000000000001',
  locationName: 'Birrfeld',
  locationShortName: 'BIR',
  countryId: CH_COUNTRY_ID,
  locationTypeId: AIRPORT_TYPE_ID,
  icaoCode: 'LSZF',
  latitude: '47.4435',
  longitude: '8.2336',
  description: 'Seed airport',
  isInboundRouteRequired: false,
  isOutboundRouteRequired: false,
  isFastEntryRecord: false,
  inOutboundPoints: [],
};

function toListItem(l: MockLocation): MockLocationListItem {
  const item: MockLocationListItem = {
    id: l.id,
    locationName: l.locationName,
    isAirfield: true,
    isFastEntryRecord: l.isFastEntryRecord,
  };
  if (l.locationShortName !== undefined) item.locationShortName = l.locationShortName;
  if (l.icaoCode !== undefined) item.icaoCode = l.icaoCode;
  const type = mockLocationTypes.find((t) => t.id === l.locationTypeId);
  if (type) item.locationTypeCode = type.code;
  return item;
}

async function stubReferenceData(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/countries**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockCountries),
    }),
  );
  await page.route('**/api/v1/club-states**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockClubStates),
    }),
  );
  await page.route('**/api/v1/location-types**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocationTypes),
    }),
  );
}

function setupLocationsBackend(locations: MockLocation[]) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/locations\/([^/]+)$/);

    if (method === 'GET' && path === '/api/v1/locations') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(locations.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = locations.find((l) => l.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/locations') {
      const body = req.postDataJSON() as Omit<MockLocation, 'id' | 'inOutboundPoints'> & {
        inOutboundPoints?: Omit<MockInOutboundPoint, 'id'>[];
      };
      if (
        body.icaoCode &&
        locations.some((l) => l.icaoCode?.toUpperCase() === body.icaoCode?.toUpperCase())
      ) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      const created: MockLocation = {
        ...body,
        id: `loc-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
        inOutboundPoints: (body.inOutboundPoints ?? []).map((p, i) => ({
          id: `019e30c3-2c00-7002-8000-${String(i).padStart(12, '0')}`,
          ...p,
        })),
      };
      locations.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/locations/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const body = req.postDataJSON() as Omit<MockLocation, 'id' | 'inOutboundPoints'> & {
        inOutboundPoints?: Omit<MockInOutboundPoint, 'id'>[];
      };
      const idx = locations.findIndex((l) => l.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      if (
        body.icaoCode &&
        locations.some(
          (l, i) => i !== idx && l.icaoCode?.toUpperCase() === body.icaoCode?.toUpperCase(),
        )
      ) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: '{}' });
        return;
      }
      locations[idx] = {
        ...locations[idx]!,
        ...body,
        inOutboundPoints: (body.inOutboundPoints ?? []).map((p, i) => ({
          id: `019e30c3-2c00-7002-8000-${String(i).padStart(12, '0')}`,
          ...p,
        })),
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(locations[idx]),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = locations.findIndex((l) => l.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      locations.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  };
}

test('locations: lists the seeded row at /locations', async ({ page }) => {
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto('/locations');

  await expect(page.locator('h1')).toHaveText('Locations');
  await expect(page.getByTestId('locations-table')).toBeVisible();
  await expect(page.getByTestId(`location-row-${seedLocation.id}`)).toBeVisible();
  await expect(page.getByTestId(`location-row-${seedLocation.id}`)).toHaveText('Birrfeld');
});

test('locations: editing the seeded row updates the list (UI round-trip)', async ({ page }) => {
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto('/locations');
  await page.getByTestId(`location-row-${seedLocation.id}`).click();

  await expect(page).toHaveURL(/\/locations\/.+\/edit$/);
  await page.locator('#LocationName').fill('Birrfeld Renamed');
  await page.locator('#Description').fill('edited by e2e');
  await page.getByTestId('locations-save-button').click();

  await expect(page).toHaveURL('/locations');
  await expect(page.getByTestId(`location-row-${seedLocation.id}`)).toHaveText('Birrfeld Renamed');

  // Persistence round-trip — reload tears down the providedIn:root store,
  // forcing it to refetch from the mock backend.
  await page.reload();
  await page.getByTestId(`location-row-${seedLocation.id}`).click();
  await expect(page).toHaveURL(/\/locations\/.+\/edit$/);
  await expect(page.locator('#LocationName')).toHaveValue('Birrfeld Renamed');
  await expect(page.locator('#Description')).toHaveValue('edited by e2e');
});

test('locations: creating a new location appears in the list', async ({ page }) => {
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto('/locations');
  await page.getByRole('button', { name: 'New location' }).click();

  await expect(page).toHaveURL('/locations/new');
  await page.locator('#LocationName').fill('Saanen');
  await page.locator('#IcaoCode').fill('LSGK');
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Switzerland' }).click();
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Airport' }).click();
  await page.getByTestId('locations-save-button').click();

  await expect(page).toHaveURL('/locations');
  await expect(page.locator('a').filter({ hasText: 'Saanen' })).toBeVisible();
});

test('locations: 409 on duplicate ICAO surfaces inline', async ({ page }) => {
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto('/locations/new');
  await page.locator('#LocationName').fill('Conflict Field');
  await page.locator('#IcaoCode').fill(seedLocation.icaoCode!);
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Switzerland' }).click();
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Airport' }).click();
  await page.getByTestId('locations-save-button').click();

  await expect(page.getByTestId('locations-save-error')).toBeVisible();
  await expect(page.getByTestId('locations-save-error')).toContainText('already in use');
});

test('locations: invalid ICAO pattern keeps Save disabled with an inline error', async ({
  page,
}) => {
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto('/locations/new');
  await page.locator('#LocationName').fill('Bad ICAO');

  const icao = page.locator('#IcaoCode');
  await icao.fill('XX'); // too short
  await icao.blur();

  await expect(page.getByTestId('locations-save-button').locator('button')).toBeDisabled();
  // Assert the user sees an inline error for the ICAO field. The exact error
  // key may shift when transloco wires up (S-057); pin to the inner role=alert
  // span so the assertion fails if no error span is emitted — `af-field-errors`
  // host stays in the DOM even with zero keys.
  await expect(
    page
      .locator('af-form-field')
      .filter({ has: page.locator('#IcaoCode') })
      .locator('af-field-errors [role="alert"]'),
  ).toBeVisible();
});

test('locations: lowercase ICAO is uppercased on save', async ({ page }) => {
  const locations: MockLocation[] = [];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto('/locations/new');
  await page.locator('#LocationName').fill('Saanen');
  await page.locator('#IcaoCode').fill('lsgk');
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Switzerland' }).click();
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Airport' }).click();
  await page.getByTestId('locations-save-button').click();

  await expect(page).toHaveURL('/locations');
  const created = locations.find((l) => l.locationName === 'Saanen');
  expect(created?.icaoCode).toBe('LSGK');
});

test('locations: list ordering follows sortIndicator then name (NULLS LAST)', async ({ page }) => {
  // Mock backend doesn't sort — return rows pre-sorted to lock the SPA's
  // binding-order to the server's ORDER BY contract.
  const sortedSeed: MockLocation[] = [
    {
      ...seedLocation,
      id: 'loc-019e30c3-2c00-7001-8000-000000000010',
      locationName: 'Alpha',
      icaoCode: 'LSAA',
    },
    {
      ...seedLocation,
      id: 'loc-019e30c3-2c00-7001-8000-000000000011',
      locationName: 'Zulu',
      icaoCode: 'LSZZ',
    },
  ];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(sortedSeed));

  await page.goto('/locations');
  await expect(page.getByTestId('locations-table')).toBeVisible();
  const rendered = await page.locator('[data-testid^="location-row-"]').allTextContents();
  expect(rendered.map((t) => t.trim())).toEqual(['Alpha', 'Zulu']);
});

test('locations: soft-delete removes the row from the rendered list', async ({ page }) => {
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto('/locations');
  await expect(page.getByTestId(`location-row-${seedLocation.id}`)).toBeVisible();

  page.once('dialog', (d) => d.accept());
  await page.getByTestId(`location-kebab-${seedLocation.id}`).click();
  await page.getByTestId(`location-delete-${seedLocation.id}`).click();

  await expect(page.getByTestId(`location-row-${seedLocation.id}`)).toHaveCount(0);
});

test('locations: cross-tenant GET-by-id 404s — foreign-club row is absent and not-found surfaces', async ({
  page,
}) => {
  // Tenant-isolation contract at mocked fidelity. The backend (@TenantId
  // auto-filter) never returns another club's Location: it is absent from
  // OUR list, and a direct GET by its id 404s. Here the mock backend only
  // knows our own seeded row; the foreign id is unknown, so the existing
  // setupLocationsBackend 404s the GET-by-id branch exactly as the real
  // server's tenant filter would (S-024 cross-tenant leak guard, J-0 AC
  // "cross-tenant GET by id 404s").
  const foreignClubLocationId = 'loc-019e30c3-2c00-7001-8000-0000000000ff';
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  // 1) The foreign-club row never appears in our list.
  await page.goto('/locations');
  await expect(page.getByTestId('locations-table')).toBeVisible();
  await expect(page.getByTestId(`location-row-${seedLocation.id}`)).toBeVisible();
  await expect(page.getByTestId(`location-row-${foreignClubLocationId}`)).toHaveCount(0);

  // 2) A direct GET-by-id for the foreign row 404s and the UI surfaces
  //    not-found without leaking any of its fields into the form.
  await page.goto(`/locations/${foreignClubLocationId}/edit`);
  await expect(page.getByTestId('locations-save-error')).toBeVisible();
  await expect(page.locator('#LocationName')).toHaveValue('');
});

test('locations: same ICAO is creatable in a different club — uniqueness is (club_id, icao_code), not global', async ({
  page,
}) => {
  // Per-club ICAO uniqueness (J-0 AC "the same physical airport (e.g. LSZH)
  // exists independently in two clubs' catalogs"). The same-club duplicate
  // case lives in "409 on duplicate ICAO surfaces inline" above; this is its
  // cross-club counterpart. The mock backend here represents a DIFFERENT
  // club's catalog that does NOT already hold LSZH, so the create succeeds —
  // no global-duplicate 409. Global ICAO uniqueness was dropped for the
  // partial unique (club_id, icao_code) WHERE icao_code IS NOT NULL AND
  // deleted_on IS NULL.
  const ICAO = 'LSZH';
  // A different club's catalog — note it does NOT contain LSZH even though
  // LSZH exists in some other club. setupLocationsBackend scopes the
  // 409-on-duplicate check to THIS catalog, mirroring the per-club unique
  // index rather than a global one.
  const otherClubCatalog: MockLocation[] = [];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(otherClubCatalog));

  await page.goto('/locations/new');
  await page.locator('#LocationName').fill('Zurich (other club)');
  await page.locator('#IcaoCode').fill(ICAO);
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Switzerland' }).click();
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Airport' }).click();
  await page.getByTestId('locations-save-button').click();

  // No global-duplicate 409 — the create lands and we return to the list.
  await expect(page).toHaveURL('/locations');
  await expect(page.getByTestId('locations-save-error')).toBeHidden();
  const created = otherClubCatalog.find((l) => l.locationName === 'Zurich (other club)');
  expect(created?.icaoCode).toBe(ICAO);
});

test('locations: nested in/outbound points — add 2, remove 1, round-trip', async ({ page }) => {
  const locations: MockLocation[] = [{ ...seedLocation, inOutboundPoints: [] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/locations**', setupLocationsBackend(locations));

  await page.goto(`/locations/${seedLocation.id}/edit`);
  await page.getByTestId('locations-iop-add').click();
  await page.getByTestId('locations-iop-add').click();

  await page.getByTestId('locations-iop-name-0').locator('input').fill('Echo');
  await page.getByTestId('locations-iop-type-0').locator('input').fill('VFR');
  await page.getByTestId('locations-iop-direction-0').locator('input').fill('N');

  await page.getByTestId('locations-iop-name-1').locator('input').fill('Foxtrot');
  await page.getByTestId('locations-iop-direction-1').locator('input').fill('S');

  await page.getByTestId('locations-iop-remove-0').click();

  await page.getByTestId('locations-save-button').click();
  await expect(page).toHaveURL('/locations');

  const saved = locations.find((l) => l.id === seedLocation.id);
  expect(saved?.inOutboundPoints).toHaveLength(1);
  expect(saved?.inOutboundPoints[0]?.pointName).toBe('Foxtrot');
  expect(saved?.inOutboundPoints[0]?.direction).toBe('S');
});
