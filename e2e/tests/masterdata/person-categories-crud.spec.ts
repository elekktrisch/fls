import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { API_BASE, getBearerToken } from '../../test-data';
import type { Page, Locator } from '@playwright/test';

const LIST_PATH = '/masterdata/personCategories';
const SEEDED = ['Vorstand', 'Fluglehrer', 'Gaeste'];

function rowByName(page: Page, name: string): Locator {
  return page.locator('.editable-tree-row', { hasText: name });
}

async function waitForTreeReady(page: Page): Promise<void> {
  await page.locator('.editable-tree-row', { hasText: SEEDED[0] }).waitFor({ state: 'visible' });
}

test('person-category-crud:add-edit-delete', async ({ loggedInPage }, testInfo) => {
  const page = loggedInPage;
  const id = testId(testInfo);
  const NAME_INITIAL = `Cat-${id.short}-A`;
  const NAME_EDITED  = `Cat-${id.short}-B`;

  const token = await getBearerToken(loggedInPage);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const listRes = await page.request.get(`${API_BASE}/api/v1/personcategories`, { headers });
  if (listRes.ok()) {
    const cats = await listRes.json() as Array<{ PersonCategoryId: string; CategoryName: string }>;
    for (const c of cats) {
      if (c.CategoryName !== NAME_INITIAL && c.CategoryName !== NAME_EDITED) continue;
      await page.request.post(`${API_BASE}/api/v1/personcategories/${c.PersonCategoryId}`, {
        headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' },
      });
    }
  }

  await gotoRoute(page, LIST_PATH);
  await waitForTreeReady(page);

  for (const name of SEEDED) {
    await expect(rowByName(page, name)).toHaveCount(1);
  }

  const rootAddButton = page
    .locator('.tree-node-manipulation-link')
    .filter({ hasNot: page.locator('xpath=ancestor::div[contains(@class, "editable-tree-row")]') })
    .last();

  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('prompt');
    await dialog.accept(NAME_INITIAL);
  });
  await rootAddButton.scrollIntoViewIfNeeded();
  await rootAddButton.click();

  await expect(rowByName(page, NAME_INITIAL)).toHaveCount(1, { timeout: 10_000 });
  await waitForTreeReady(page);

  const createdRow = rowByName(page, NAME_INITIAL);
  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('prompt');
    expect(dialog.defaultValue()).toBe(NAME_INITIAL);
    await dialog.accept(NAME_EDITED);
  });
  await createdRow.scrollIntoViewIfNeeded();
  await createdRow.hover();
  const editLink = createdRow.locator('.tree-node-manipulation-link').nth(0);
  await editLink.click({ force: true });

  await expect(rowByName(page, NAME_EDITED)).toHaveCount(1, { timeout: 10_000 });
  await expect(rowByName(page, NAME_INITIAL)).toHaveCount(0);
  await waitForTreeReady(page);

  const editedRow = rowByName(page, NAME_EDITED);
  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('confirm');
    expect(dialog.message()).toContain(NAME_EDITED);
    await dialog.accept();
  });
  await editedRow.scrollIntoViewIfNeeded();
  await editedRow.hover();
  const deleteLink = editedRow.locator('.tree-node-manipulation-link').nth(2);
  await deleteLink.click({ force: true });

  await expect(rowByName(page, NAME_EDITED)).toHaveCount(0, { timeout: 10_000 });

  await waitForTreeReady(page);
  for (const name of SEEDED) {
    await expect(rowByName(page, name)).toHaveCount(1);
  }
  await screenshot(loggedInPage, 'person-categories-crud-01');
});
