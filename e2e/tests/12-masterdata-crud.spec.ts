// Spec #12: canonical CRUD on a masterdata entity (Locations).
// TODO testid: `.fls-new-button button`, `a.delete-link`, form Save/Cancel.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/locations';
const DESC_INITIAL = 'created by e2e';
const DESC_EDITED = 'edited by e2e';

async function openListAndWait(page: Page) {
  await gotoRoute(page, LIST_PATH);
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

async function ensureLocationDeleted(page: Page, name: string): Promise<void> {
  // Drop prior-run leftover so CREATE doesn't duplicate.
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try { return JSON.parse(raw).access_token as string; } catch { return null; }
  });
  if (!token) return;
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const listRes = await page.request.post(`${API_BASE}/api/v1/locations/page/0/100`, {
    headers,
    data: { Sorting: {}, SearchFilter: { LocationName: name } },
  });
  if (!listRes.ok()) return;
  const body = await listRes.json() as { Items?: { LocationId: string; LocationName: string }[] };
  for (const row of body.Items ?? []) {
    if (row.LocationName !== name) continue;
    await page.request.post(`${API_BASE}/api/v1/locations/${row.LocationId}`, {
      headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' },
    });
  }
}

async function fillRequiredDropdowns(page: Page): Promise<void> {
  // Country + LocationType selectize dropdowns have no testids — drive via $scope.
  await page.waitForFunction(() => {
    const w = window as unknown as {
      angular: { element: (n: Element) => { scope: () => unknown } };
    };
    const form = document.querySelector('form[name="locationForm"]');
    if (!form) return false;
    const s = w.angular.element(form).scope() as {
      md?: { countries?: unknown[]; locationTypes?: unknown[] };
    };
    return Array.isArray(s.md?.countries) && (s.md?.countries.length ?? 0) > 0
      && Array.isArray(s.md?.locationTypes) && (s.md?.locationTypes.length ?? 0) > 0;
  }, undefined, { timeout: 10_000 });
  await page.evaluate(() => {
    const w = window as unknown as {
      angular: { element: (n: Element) => { scope: () => unknown } };
    };
    const form = document.querySelector('form[name="locationForm"]')!;
    const s = w.angular.element(form).scope() as {
      location?: { CountryId?: string; LocationTypeId?: string };
      md: {
        countries: { CountryId: string; CountryName: string }[];
        locationTypes: { LocationTypeId: string; LocationTypeName: string }[];
      };
      $apply: (fn?: () => void) => void;
    };
    if (!s.location) return;
    s.location.CountryId =
      s.md.countries.find(c => c.CountryName === 'Schweiz')?.CountryId
      ?? s.md.countries[0].CountryId;
    s.location.LocationTypeId = s.md.locationTypes[0].LocationTypeId;
    s.$apply();
  });
}

async function submitForm(page: Page) {
  await page.locator('form[name="locationForm"] button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/locations', { timeout: 10_000 });
  await page.waitForLoadState('domcontentloaded');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

// Locations list grows long under accumulated state; ng-table filter inputs
// lag — see TEST_WRITING.md §6. The spec drives CREATE/EDIT via the UI form
// (the surface that matters) but navigates by direct URL instead of filtering
// the table to find rows. DELETE goes via API since the list-row trash anchor
// has the same ng-table dependency.
test.setTimeout(90_000);

test('masterdata-crud:locations create-edit-delete', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const NAME = id.name;
  const ICAO = id.short;
  const page = loggedInPage;

  await ensureLocationDeleted(page, NAME);

  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    try { return raw ? (JSON.parse(raw).access_token as string) : null; } catch { return null; }
  });
  const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  // CREATE via UI form.
  await openListAndWait(page);
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/locations/new', { timeout: 10_000 });
  await page.locator('#LocationName').waitFor({ state: 'visible' });

  await page.locator('#LocationName').fill(NAME);
  await page.locator('#IcaoCode').fill(ICAO);
  await page.locator('#Description').fill(DESC_INITIAL);
  await fillRequiredDropdowns(page);
  await submitForm(page);

  // Find the new row via API instead of the ng-table filter input.
  const lookupRes = await page.request.post(`${API_BASE}/api/v1/locations/page/0/100`, {
    headers: auth,
    data: { Sorting: {}, SearchFilter: { LocationName: NAME } },
  });
  expect(lookupRes.ok(), `locations/page lookup: ${lookupRes.status()}`).toBeTruthy();
  const lookupBody = await lookupRes.json() as { Items?: Array<{ LocationId: string; LocationName: string }> };
  const created = (lookupBody.Items ?? []).find(l => l.LocationName === NAME);
  expect(created, 'created location should be in paged listing').toBeTruthy();

  // EDIT via direct-URL navigation (skips the unreliable list-row click).
  await gotoRoute(page, `/masterdata/locations/${created!.LocationId}`);
  await page.locator('#Description').waitFor({ state: 'visible' });
  await expect(page.locator('#LocationName')).toHaveValue(NAME);
  await expect(page.locator('#Description')).toHaveValue(DESC_INITIAL);
  await page.locator('#Description').fill(DESC_EDITED);
  await submitForm(page);

  // API readback to confirm edit persisted (skips the ng-table filter wait).
  const verifyRes = await page.request.get(`${API_BASE}/api/v1/locations/${created!.LocationId}`, { headers: auth });
  expect(verifyRes.ok()).toBeTruthy();
  const verifyBody = await verifyRes.json() as { Description?: string };
  expect(verifyBody.Description).toBe(DESC_EDITED);

  // DELETE via API (UI trash-link path is exercised by other CRUD specs
  // against shorter tables — locations list is too big to filter reliably).
  await page.request.post(`${API_BASE}/api/v1/locations/${created!.LocationId}`, {
    headers: { ...auth, 'X-HTTP-Method-Override': 'DELETE' },
  });
  await screenshot(loggedInPage, '12-masterdata-crud-01');
});
