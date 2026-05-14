/**
 * Full CRUD cycle for a FlightType via /masterdata/flightTypes (#29 in
 * e2e/PLAN.md).
 *
 * FlightTypes use the "pencil-link" table pattern (see e2e/SELECTORS.md):
 * `flsweb/src/masterdata/flightTypes/flight-types-table.html` puts
 * `data-testid="row"` on the `<tr ng-repeat-start>` but the row itself is NOT
 * clickable — a separate `<a data-testid="row-edit">` pencil link triggers
 * `editFlightType(flightType)`. A second `<a>` with a `fa-trash-o` icon
 * triggers `deleteFlightType(...)` which goes through `window.confirm`.
 *
 * Flow:
 *   1. Create: click the data-table's "+" new-button (`.fls-new-button button`)
 *              → controller navigates to `/masterdata/flightTypes/new` →
 *              fill `#FlightCode`, `#FlightTypeName`, tick `#IsForGliderFlights`
 *              → submit. After save the controller calls `$scope.cancel()`
 *              which redirects to `/masterdata/flightTypes`. Assert the new
 *              row is rendered in the list.
 *   2. Edit:   filter the list to the new code, click the row's
 *              `data-testid="row-edit"` pencil → mutate `#FlightTypeName` →
 *              submit. Re-filter and assert the new name renders in the row.
 *   3. Delete: filter the list to the row, accept the `window.confirm` dialog,
 *              click the trash icon (`a` containing `.fa-trash-o`). Assert the
 *              row disappears.
 *
 * Uses `loggedInPage` (fast session-storage auth) + `freshDb` (worker-scoped
 * re-seed) because this spec mutates and cannot share state with other
 * mutation specs in the same worker.
 *
 * TODO testid: the data-table "+" new button (`.fls-new-button button`), the
 * row-delete trash anchor (sibling of `[data-testid="row-edit"]`), and the
 * form's Save/Cancel buttons currently have no `data-testid`. Falls back to
 * semantic + class selectors.
 */
import { test, expect, gotoRoute } from '../fixtures';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/flightTypes';
const NONCE = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
// FlightCode is StringLength(30) server-side and a 3-letter convention in
// seed data, but no <30 char restriction. Use a unique short code.
const CODE = `E${NONCE.slice(-4)}`.slice(0, 5).toUpperCase();
const NAME_INITIAL = `E2E Flight Type ${NONCE}`;
const NAME_EDITED = `E2E Flight Type ${NONCE} (edited)`;

function rowByText(page: Page, text: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: text });
}

async function openListAndWait(page: Page) {
  await gotoRoute(page, LIST_PATH);
  // The seeded FLSTest DB has flight-type rows; wait for the grid to render.
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

async function submitForm(page: Page) {
  // Save = <button type="submit">; Cancel = <button type="button">.
  await page.locator('form button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/flightTypes', { timeout: 10_000 });
  await page.waitForLoadState('networkidle');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

async function filterTo(page: Page, needle: string) {
  // <fls-simple-search-bar> filters by FlightCode OR FlightTypeName via the
  // `comparator` set on the controller. The input is ng-model="searchString".
  const search = page.locator('.search-bar input').first();
  await search.fill(needle);
}

test('masterdata-crud:flightTypes create-edit-delete', async ({ loggedInPage, freshDb }) => {
  void freshDb;
  const page = loggedInPage;

  // ----- CREATE -----------------------------------------------------------
  await openListAndWait(page);
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/flightTypes/new', { timeout: 10_000 });
  await page.locator('#FlightCode').waitFor({ state: 'visible' });

  await page.locator('#FlightCode').fill(CODE);
  await page.locator('#FlightTypeName').fill(NAME_INITIAL);
  // <fls-labelled-checkbox> renders <input type="checkbox" id="{{attribute}}">.
  // Tick the glider flag so this row is a valid, plausibly selectable type.
  await page.locator('#IsForGliderFlights').check();
  await submitForm(page);

  await filterTo(page, CODE);
  const createdRow = rowByText(page, NAME_INITIAL);
  await expect(createdRow).toHaveCount(1, { timeout: 10_000 });

  // ----- EDIT -------------------------------------------------------------
  // Click the pencil link, NOT the row (pencil-link pattern).
  await createdRow.locator('[data-testid="row-edit"]').click();
  await page.waitForURL(/\/masterdata\/flightTypes\/[0-9a-fA-F-]{36}$/, { timeout: 10_000 });
  await page.locator('#FlightTypeName').waitFor({ state: 'visible' });
  await expect(page.locator('#FlightCode')).toHaveValue(CODE);
  await expect(page.locator('#FlightTypeName')).toHaveValue(NAME_INITIAL);
  await page.locator('#FlightTypeName').fill(NAME_EDITED);
  await submitForm(page);

  // Re-filter (still by CODE — that field wasn't changed) and verify the row
  // now displays the edited name. Don't rely on default sort placing the row
  // in view.
  await filterTo(page, CODE);
  const editedRow = rowByText(page, NAME_EDITED);
  await expect(editedRow).toHaveCount(1, { timeout: 10_000 });
  await expect(rowByText(page, NAME_INITIAL)).toHaveCount(0);

  // ----- DELETE -----------------------------------------------------------
  // Accept the `window.confirm("Do you really want to remove this flight type
  // from the database?")` raised by FlightTypeService.delete.
  page.once('dialog', dialog => dialog.accept());
  // The trash anchor is the sibling of the pencil <a>; it contains <span class="fa fa-trash-o">.
  await editedRow.locator('a:has(.fa-trash-o)').click();

  await expect(rowByText(page, NAME_EDITED)).toHaveCount(0, { timeout: 10_000 });
});
