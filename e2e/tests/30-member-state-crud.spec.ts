/**
 * Task #30 — Member-state CRUD via /masterdata/memberStates.
 *
 * Member states label PersonClub records ("Aktivmitglied", "Schüler", …).
 * The module is in `flsweb/src/masterdata/memberStates/`:
 *   - List route   : /masterdata/memberStates           (member-states.html → member-states-table.html)
 *   - Edit/new     : /masterdata/memberStates/:id       (member-states-edit.html)
 *   - Service      : MemberState ($resource → /api/v1/memberstates/:id, with
 *                    `$save` POST, `$saveMemberState` POST + X-HTTP-Method-Override: PUT,
 *                    `delete` POST + X-HTTP-Method-Override: DELETE)
 *
 * The table uses the **pencil-link pattern** (see e2e/SELECTORS.md): each row
 * carries `data-testid="row"`, and a separate `<a data-testid="row-edit">`
 * pencil icon opens the edit form. A sibling trash icon (no testid) triggers
 * `deleteMemberState(memberState)` which first calls `window.confirm(...)` —
 * so we install a `dialog` accept handler before clicking it.
 *
 * Flow:
 *   1. Create  — click the `+` button in `<fls-data-table>`, fill MemberStateName, save.
 *   2. Edit    — re-open the new row via the pencil link, change the name, save.
 *   3. Delete  — accept the confirm dialog on the trash icon, verify row is gone.
 *
 * The form field uses `id="MemberStateName"` (member-state-form-fields.html) —
 * we select by that stable id rather than by translated label.
 */

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/memberStates';

async function rowCount(page: Page): Promise<number> {
  return page.locator('tbody [data-testid="row"]').count();
}

async function findRowByName(page: Page, name: string) {
  // The first cell of each row binds `memberState.MemberStateName`; filter rows
  // whose text contains the name. Using hasText keeps this resilient to any
  // sibling controls inside the row.
  return page.locator('tbody [data-testid="row"]', { hasText: name });
}

async function waitForListReady(page: Page): Promise<void> {
  // After save() the controller calls $location.path('/masterdata/memberStates')
  // and the list re-fetches; wait for the spinner to clear and at least one row.
  await page.waitForURL(/\/masterdata\/memberStates$/, { timeout: 10_000 });
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const r = el.getBoundingClientRect();
      return r.width === 0 && r.height === 0;
    });
  }, undefined, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
}

test('masterdata:member-state CRUD via pencil-link list', async ({ loggedInPage, freshDb }) => {
  void freshDb;
  const page = loggedInPage;
  const nonce = `${Date.now().toString().slice(-6)}-${Math.floor(Math.random() * 1000)}`;
  const createName = `E2EState-${nonce}`;
  const renamedName = `${createName}-edited`;

  // --- Create ---------------------------------------------------------------
  await gotoRoute(page, LIST_PATH);
  const initialCount = await rowCount(page);

  // The "+" button lives in <fls-data-table> (data-table-directive.html) and
  // has no testid; .fls-new-button is the wrapper, the only <button> inside.
  // TODO testid: add data-testid="list-new" to the <fls-data-table> new button.
  await page.locator('.fls-new-button button').click();
  await page.waitForURL(/\/masterdata\/memberStates\/new$/, { timeout: 10_000 });
  await page.locator('input#MemberStateName').waitFor({ state: 'visible' });
  await page.locator('input#MemberStateName').fill(createName);
  // TODO testid: add data-testid="form-save" to the save submit button.
  await page.locator('button[type="submit"]').click();

  await waitForListReady(page);
  expect(await rowCount(page)).toBe(initialCount + 1);
  await expect(await findRowByName(page, createName)).toHaveCount(1);

  // --- Edit -----------------------------------------------------------------
  const createdRow = await findRowByName(page, createName);
  await createdRow.locator('[data-testid="row-edit"]').click();
  await page.waitForURL(/\/masterdata\/memberStates\/[0-9a-f-]+$/i, { timeout: 10_000 });
  const nameInput = page.locator('input#MemberStateName');
  await nameInput.waitFor({ state: 'visible' });
  await expect(nameInput).toHaveValue(createName);
  await nameInput.fill(renamedName);
  await page.locator('button[type="submit"]').click();

  await waitForListReady(page);
  await expect(await findRowByName(page, renamedName)).toHaveCount(1);
  await expect(await findRowByName(page, createName)).toHaveCount(0);

  // --- Delete ---------------------------------------------------------------
  // MemberStateService.delete uses a native window.confirm — accept it.
  page.once('dialog', dialog => dialog.accept());
  const renamedRow = await findRowByName(page, renamedName);
  // The trash icon is the only `.fa-trash-o` inside the row (no testid).
  // TODO testid: add data-testid="row-delete" to the trash-icon anchor.
  await renamedRow.locator('a:has(.fa-trash-o)').click();

  // After delete the controller filters the row out of the in-memory array;
  // poll until it's gone.
  await expect(await findRowByName(page, renamedName)).toHaveCount(0, { timeout: 10_000 });
  expect(await rowCount(page)).toBe(initialCount);
  await screenshot(loggedInPage, '30-member-state-crud-01');
});
