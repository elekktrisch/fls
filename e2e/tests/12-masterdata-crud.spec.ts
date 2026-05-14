// Spec #12: canonical CRUD on a masterdata entity (Locations).
// TODO testid: `.fls-new-button button`, `a.delete-link`, form Save/Cancel.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/locations';
const DESC_INITIAL = 'created by e2e';
const DESC_EDITED = 'edited by e2e';

function rowByName(page: Page, name: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: name });
}

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

test('masterdata-crud:locations create-edit-delete', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const NAME = id.name;
  const ICAO = id.short;
  const page = loggedInPage;

  await ensureLocationDeleted(page, NAME);

  // CREATE
  await openListAndWait(page);
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/locations/new', { timeout: 10_000 });
  await page.locator('#LocationName').waitFor({ state: 'visible' });

  await page.locator('#LocationName').fill(NAME);
  await page.locator('#IcaoCode').fill(ICAO);
  await page.locator('#Description').fill(DESC_INITIAL);
  await fillRequiredDropdowns(page);
  await submitForm(page);

  const nameFilter = page.locator('input[ng-model*="LocationName"]').first();
  // ng-table filter inputs hydrate after the loader — see TEST_WRITING.md §6.
  await nameFilter.waitFor({ state: 'visible', timeout: 30_000 });
  await nameFilter.fill(NAME);
  const createdRow = rowByName(page, NAME);
  await expect(createdRow).toHaveCount(1, { timeout: 10_000 });

  // EDIT
  await createdRow.click();
  await page.waitForURL(/\/masterdata\/locations\/[0-9a-fA-F-]{36}$/, { timeout: 10_000 });
  await page.locator('#Description').waitFor({ state: 'visible' });
  await expect(page.locator('#LocationName')).toHaveValue(NAME);
  await expect(page.locator('#Description')).toHaveValue(DESC_INITIAL);
  await page.locator('#Description').fill(DESC_EDITED);
  await submitForm(page);

  await nameFilter.fill(NAME);
  const editedRow = rowByName(page, NAME);
  await expect(editedRow).toHaveCount(1, { timeout: 10_000 });

  // DELETE
  await nameFilter.fill(NAME);
  const rowToDelete = rowByName(page, NAME);
  await expect(rowToDelete).toHaveCount(1, { timeout: 10_000 });
  // Delete link is ng-show'd on CanDeleteRecord; skip if not visible.
  const deleteLink = rowToDelete.locator('a.delete-link');
  if (await deleteLink.count() === 0 || !(await deleteLink.first().isVisible())) {
    test.info().annotations.push({
      type: 'delete-skipped',
      description: 'Delete link not visible (CanDeleteRecord=false). Stopping after create+edit.',
    });
    return;
  }
  page.once('dialog', dialog => dialog.accept());
  await deleteLink.first().click();

  await expect(page.locator('tbody [data-testid="row"]', { hasText: NAME })).toHaveCount(0, {
    timeout: 10_000,
  });
  await screenshot(loggedInPage, '12-masterdata-crud-01');
});
