/**
 * Full CRUD cycle for a PersonCategory via `/masterdata/personCategories` (#31 in
 * e2e/PLAN.md).
 *
 * PersonCategory is unusual among the masterdata entities: the UI is a
 * tree directive (`fls-editable-tree`, see
 * `flsweb/src/core/directives/tree/editable-tree-directive.html`) rather than
 * an ng-table. Mutations are driven by **native browser dialogs**:
 *   - Add:    `window.prompt('Person-Category Name:')` returns the new name.
 *   - Edit:   `window.prompt('Person-Category Name:', currentName)` returns the new name.
 *   - Delete: `window.confirm("Really delete 'X'?")` -> true to proceed.
 * See `flsweb/src/masterdata/personCategories/PersonCategoriesController.js`.
 *
 * The `_test-fixture.sql` seeds three root categories for the test club:
 *   `Vorstand`, `Fluglehrer`, `Gaeste`. We add a fresh one with a unique
 *   nonce, rename it, then delete it -- leaving the seeded set intact.
 *
 * Flow:
 *   1. CREATE: click the trailing "+" anchor (the only `.tree-node-manipulation-link`
 *              that lives outside an `.editable-tree-row`), reply to the prompt
 *              with our nonce-tagged name. After `$route.reload()` the new row
 *              renders inside `.editable-tree-row` with our name.
 *   2. EDIT:   click the pencil anchor (first `.tree-node-manipulation-link`
 *              inside our row), reply to the prompt with the edited name.
 *              Assert the row renders with the new name.
 *   3. DELETE: click the trash anchor (third `.tree-node-manipulation-link`
 *              inside the row), accept the confirm. Assert the row disappears
 *              and the three seeded categories are still present.
 *
 * Uses `loggedInPage` (fast session-storage auth) and `freshDb` (worker-scoped
 * re-seed; this spec mutates state).
 *
 * TODO testid: the editable-tree row (`.editable-tree-row`) and its three
 * manipulation anchors (edit / add / delete) currently have no `data-testid`
 * markers and aren't covered by `e2e/SELECTORS.md`. Falls back to class
 * selectors + child-anchor ordering, which is acceptable for one spec but
 * worth promoting (e.g. `tree-row`, `tree-row-edit`, `tree-row-delete`) in a
 * consolidation pass.
 */
import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page, Locator } from '@playwright/test';

const LIST_PATH = '/masterdata/personCategories';
const NONCE = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const NAME_INITIAL = `E2E Cat ${NONCE}`;
const NAME_EDITED = `E2E Cat Edited ${NONCE}`;
const SEEDED = ['Vorstand', 'Fluglehrer', 'Gaeste'];

function rowByName(page: Page, name: string): Locator {
  // ng-bind sets text content exactly, so `hasText` (substring) is safe given
  // our nonce-tagged names are unique.
  return page.locator('.editable-tree-row', { hasText: name });
}

async function waitForTreeReady(page: Page): Promise<void> {
  // The controller toggles $scope.busy = false once `loadPersonCategories()`
  // resolves; gotoRoute already polls the busy-indicator. Then wait for at
  // least one of the seeded root nodes to render.
  await page.locator('.editable-tree-row', { hasText: SEEDED[0] }).waitFor({ state: 'visible' });
}

test('person-category-crud:add-edit-delete', async ({ loggedInPage, freshDb }) => {
  void freshDb;
  const page = loggedInPage;

  await gotoRoute(page, LIST_PATH);
  await waitForTreeReady(page);

  // Sanity: confirm the fixture seeds three root categories.
  for (const name of SEEDED) {
    await expect(rowByName(page, name)).toHaveCount(1);
  }

  // ----- CREATE -----------------------------------------------------------
  // The trailing root-level "+" is the .tree-node-manipulation-link that
  // sits OUTSIDE every .editable-tree-row (the in-row anchors are all inside
  // one). Filter accordingly.
  const rootAddButton = page
    .locator('.tree-node-manipulation-link')
    .filter({ hasNot: page.locator('xpath=ancestor::div[contains(@class, "editable-tree-row")]') })
    .last();

  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('prompt');
    await dialog.accept(NAME_INITIAL);
  });
  await rootAddButton.click();

  // $route.reload() rebuilds the tree; wait for our new row to appear.
  await expect(rowByName(page, NAME_INITIAL)).toHaveCount(1, { timeout: 10_000 });
  await waitForTreeReady(page);

  // ----- EDIT -------------------------------------------------------------
  // Anchors inside an .editable-tree-row are, in order: edit (pencil),
  // add (plus-circle), delete (trash). Click the first to trigger editNode.
  const createdRow = rowByName(page, NAME_INITIAL);
  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('prompt');
    expect(dialog.defaultValue()).toBe(NAME_INITIAL);
    await dialog.accept(NAME_EDITED);
  });
  await createdRow.locator('.tree-node-manipulation-link').nth(0).click();

  await expect(rowByName(page, NAME_EDITED)).toHaveCount(1, { timeout: 10_000 });
  await expect(rowByName(page, NAME_INITIAL)).toHaveCount(0);
  await waitForTreeReady(page);

  // ----- DELETE -----------------------------------------------------------
  const editedRow = rowByName(page, NAME_EDITED);
  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('confirm');
    expect(dialog.message()).toContain(NAME_EDITED);
    await dialog.accept();
  });
  await editedRow.locator('.tree-node-manipulation-link').nth(2).click();

  await expect(rowByName(page, NAME_EDITED)).toHaveCount(0, { timeout: 10_000 });

  // Seeded categories must remain after our delete.
  await waitForTreeReady(page);
  for (const name of SEEDED) {
    await expect(rowByName(page, name)).toHaveCount(1);
  }
  await screenshot(loggedInPage, '31-person-category-crud-01');
});
