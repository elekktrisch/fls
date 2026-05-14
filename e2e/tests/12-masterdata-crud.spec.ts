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
import { test, expect, gotoRoute } from '../fixtures';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/locations';
const NONCE = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const NAME = `E2E Test Location ${NONCE}`;
const ICAO = `E2E${NONCE.slice(-4)}`.toUpperCase().slice(0, 6);
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

async function submitForm(page: Page) {
  // Cancel + Save buttons are siblings inside `locations-edit.html`. Save is
  // the `<button type="submit">` (Cancel is `type="button"`).
  await page.locator('form button[type="submit"]').click();
  // After save the controller navigates back to `/masterdata/locations`; wait
  // for the list to be back on screen.
  await page.waitForURL('**/#/masterdata/locations', { timeout: 10_000 });
  await page.waitForLoadState('networkidle');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

test('masterdata-crud:locations create-edit-delete', async ({ loggedInPage, freshDb }) => {
  void freshDb;
  const page = loggedInPage;

  // ----- CREATE -----------------------------------------------------------
  await openListAndWait(page);
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/locations/new', { timeout: 10_000 });
  await page.locator('#LocationName').waitFor({ state: 'visible' });

  await page.locator('#LocationName').fill(NAME);
  await page.locator('#IcaoCode').fill(ICAO);
  await page.locator('#Description').fill(DESC_INITIAL);
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

  // Re-filter (the list state may have been reset) and re-open to confirm the
  // edit persisted server-side.
  await nameFilter.fill(NAME);
  const editedRow = rowByName(page, NAME);
  await expect(editedRow).toHaveCount(1, { timeout: 10_000 });
  await editedRow.click();
  await page.locator('#Description').waitFor({ state: 'visible' });
  await expect(page.locator('#Description')).toHaveValue(DESC_EDITED);
  // Navigate back via Cancel (type=button).
  await page.locator('form button[type="button"]').first().click();
  await page.waitForURL('**/#/masterdata/locations', { timeout: 10_000 });
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });

  // ----- DELETE -----------------------------------------------------------
  await nameFilter.fill(NAME);
  const rowToDelete = rowByName(page, NAME);
  await expect(rowToDelete).toHaveCount(1, { timeout: 10_000 });
  page.once('dialog', dialog => dialog.accept());
  await rowToDelete.locator('a.delete-link').click();

  // The list controller rebuilds `$scope.locations` after the delete; wait
  // until the matching row is gone.
  await expect(page.locator('tbody [data-testid="row"]', { hasText: NAME })).toHaveCount(0, {
    timeout: 10_000,
  });
});
