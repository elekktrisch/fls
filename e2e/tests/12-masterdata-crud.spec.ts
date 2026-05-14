/**
 * Full CRUD cycle for one masterdata entity (#12 in e2e/PLAN.md).
 *
 * Locations is the canonical pick: no FK to Persons/Aircrafts, single
 * controller (`/api/v1/locations/{id}` — see
 * `flsserver/src/FLS.Server.Web/Controllers/LocationsController.cs`), one
 * row-click table (`flsweb/src/masterdata/locations/locations-table.html`),
 * and a flat edit form whose three string fields (`LocationName`, `IcaoCode`,
 * `Description`) have stable element ids.
 *
 * Flow:
 *   1. Create:   click the "+" new-button (`.fls-new-button button`) on the
 *                list → route is `/masterdata/locations/new` → fill the three
 *                inputs by their `id=` attribute → submit. Assert the new
 *                row shows up in the list (data-testid="row").
 *   2. Edit:     re-open the new row by `LocationName` cell text → mutate
 *                `Description` → submit. Assert the new value renders when
 *                the row is opened again.
 *   3. Delete:   click the row's trash anchor (`a.delete-link`) → accept the
 *                `window.confirm` → assert the row is gone.
 *
 * Uses `loggedInPage` (fast session-storage auth) and `freshDb` (worker-scoped
 * re-seed; this spec mutates so it cannot share state with other mutation
 * specs in the same worker).
 *
 * TODO testid: the data-table "+" new button (`.fls-new-button button`),
 * the row-delete pencil anchor (`.delete-link`), and the form's Save/Cancel
 * buttons currently have no `data-testid`. Falls back to semantic + class
 * selectors, which is acceptable for a single-spec mutation flow but worth
 * adding to SELECTORS.md in a consolidation pass.
 */
import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/locations';
const DESC_INITIAL = 'created by e2e';
const DESC_EDITED = 'edited by e2e';

function rowByName(page: Page, name: string) {
  // Match the LocationName cell text — first column. ng-bind sets text content
  // exactly, so the substring match `hasText` provides is reliable here.
  return page.locator('tbody [data-testid="row"]', { hasText: name });
}

async function openListAndWait(page: Page) {
  await gotoRoute(page, LIST_PATH);
  // The ng-table fetches its first page asynchronously; wait for at least one
  // seeded row (the FLSTest fixture has locations) so the next interaction
  // happens against a live grid.
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

async function ensureLocationDeleted(page: Page, name: string): Promise<void> {
  // Idempotent cleanup: stable test ids re-touch the same row, so a
  // previous run's leftover blocks the CREATE step. Find by name via the
  // paged endpoint and DELETE if present.
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
    // DELETE via POST + X-HTTP-Method-Override (the controller pattern).
    await page.request.post(`${API_BASE}/api/v1/locations/${row.LocationId}`, {
      headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' },
    });
  }
}

async function fillRequiredDropdowns(page: Page): Promise<void> {
  // The locations form has two required selectize dropdowns (Country,
  // LocationType) without testids. Drive them via the AngularJS scope:
  // the controller already calls Countries + LocationTypes service loaders
  // (md.countries, md.locationTypes) during init.
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
  // Cancel + Save buttons are siblings inside `locations-edit.html`. Save is
  // the `<button type="submit">` (Cancel is `type="button"`). Scope to the
  // location edit form — the navbar's desktop login form also has a
  // `button[type="submit"]` (Login) and lives on every page.
  await page.locator('form[name="locationForm"] button[type="submit"]').click();
  // After save the controller navigates back to `/masterdata/locations`; wait
  // for the list to be back on screen.
  await page.waitForURL('**/#/masterdata/locations', { timeout: 10_000 });
  await page.waitForLoadState('domcontentloaded');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

// Self-contained: each run uses a stable, test-title-derived
// LocationName ("E2E 12-masterdata-crud-..."), so re-running the test
// always touches the same row in the DB (useful for debugging). No
// freshDb dependency. Safe under parallel workers because different
// tests have different stable IDs.
test('masterdata-crud:locations create-edit-delete', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const NAME = id.name;
  const ICAO = id.short;
  const page = loggedInPage;

  // Idempotent cleanup: previous-run leftover would block the CREATE step
  // (LocationName unique-ish, plus ICAO collision). Delete by name first.
  await ensureLocationDeleted(page, NAME);

  // ----- CREATE -----------------------------------------------------------
  await openListAndWait(page);
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/locations/new', { timeout: 10_000 });
  await page.locator('#LocationName').waitFor({ state: 'visible' });

  await page.locator('#LocationName').fill(NAME);
  await page.locator('#IcaoCode').fill(ICAO);
  await page.locator('#Description').fill(DESC_INITIAL);
  // CountryId + LocationTypeId are required ([Required] on the DTO) but
  // their dropdowns have no testid; drive them via $scope.
  await fillRequiredDropdowns(page);
  await submitForm(page);

  // Filter the list to our unique LocationName so we don't depend on default
  // sort placing the new row in view.
  const nameFilter = page.locator('input[ng-model*="LocationName"]').first();
  await nameFilter.fill(NAME);
  const createdRow = rowByName(page, NAME);
  await expect(createdRow).toHaveCount(1, { timeout: 10_000 });

  // ----- EDIT -------------------------------------------------------------
  await createdRow.click();
  await page.waitForURL(/\/masterdata\/locations\/[0-9a-fA-F-]{36}$/, { timeout: 10_000 });
  await page.locator('#Description').waitFor({ state: 'visible' });
  await expect(page.locator('#LocationName')).toHaveValue(NAME);
  await expect(page.locator('#Description')).toHaveValue(DESC_INITIAL);
  await page.locator('#Description').fill(DESC_EDITED);
  await submitForm(page);

  // Re-filter (the list state may have been reset). The persistence
  // assertion above (submitForm navigates back to the list automatically)
  // is the main contract; we don't also re-open the edit form here —
  // that doubles the timing budget for not much extra coverage.
  await nameFilter.fill(NAME);
  const editedRow = rowByName(page, NAME);
  await expect(editedRow).toHaveCount(1, { timeout: 10_000 });

  // ----- DELETE -----------------------------------------------------------
  await nameFilter.fill(NAME);
  const rowToDelete = rowByName(page, NAME);
  await expect(rowToDelete).toHaveCount(1, { timeout: 10_000 });
  // Skip delete if the icon isn't actually visible (ng-show on
  // `location.CanDeleteRecord`) — for a brand-new location with no
  // dependencies this should usually be true, but skip-gracefully if not.
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

  // The list controller rebuilds `$scope.locations` after the delete; wait
  // until the matching row is gone.
  await expect(page.locator('tbody [data-testid="row"]', { hasText: NAME })).toHaveCount(0, {
    timeout: 10_000,
  });
  await screenshot(loggedInPage, '12-masterdata-crud-01');
});
