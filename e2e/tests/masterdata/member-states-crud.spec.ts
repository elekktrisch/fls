
import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { API_BASE, getBearerToken } from '../../test-data';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/memberStates';

async function findRowByName(page: Page, name: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: name });
}

async function waitForListReady(page: Page): Promise<void> {
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

test('masterdata:member-state CRUD via pencil-link list', async ({ loggedInPage }, testInfo) => {
  const page = loggedInPage;
  const id = testId(testInfo);
  const createName  = `MemberState-${id.short}-A`;
  const renamedName = `MemberState-${id.short}-B`;

  const token = await getBearerToken(loggedInPage);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const listRes = await page.request.get(`${API_BASE}/api/v1/memberstates`, { headers });
  if (listRes.ok()) {
    const states = await listRes.json() as Array<{ MemberStateId: string; MemberStateName: string }>;
    for (const s of states) {
      if (s.MemberStateName !== createName && s.MemberStateName !== renamedName) continue;
      await page.request.post(`${API_BASE}/api/v1/memberstates/${s.MemberStateId}`, {
        headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' },
      });
    }
  }

  await gotoRoute(page, LIST_PATH);
  await page.locator('.fls-new-button button').click();
  await page.waitForURL(/\/masterdata\/memberStates\/new$/, { timeout: 10_000 });
  await page.locator('input#MemberStateName').waitFor({ state: 'visible' });
  await page.locator('input#MemberStateName').fill(createName);
  await page.locator('form[name="memberStateForm"] button[type="submit"]').click();

  await waitForListReady(page);
  await expect(await findRowByName(page, createName)).toHaveCount(1, { timeout: 10_000 });

  const createdRow = await findRowByName(page, createName);
  await createdRow.locator('[data-testid="row-edit"]').click();
  await page.waitForURL(/\/masterdata\/memberStates\/[0-9a-f-]+$/i, { timeout: 10_000 });
  const nameInput = page.locator('input#MemberStateName');
  await nameInput.waitFor({ state: 'visible' });
  await expect(nameInput).toHaveValue(createName);
  await nameInput.fill(renamedName);
  await page.locator('form[name="memberStateForm"] button[type="submit"]').click();

  await waitForListReady(page);
  await expect(await findRowByName(page, renamedName)).toHaveCount(1);
  await expect(await findRowByName(page, createName)).toHaveCount(0);

  page.once('dialog', dialog => dialog.accept());
  const renamedRow = await findRowByName(page, renamedName);
  const deletePromise = page.waitForResponse(r =>
    /\/api\/v1\/memberstates\/[a-f0-9-]+$/i.test(r.url()) && r.request().method() === 'POST',
    { timeout: 10_000 });
  await renamedRow.locator('a:has(.fa-trash-o)').click();
  await deletePromise;

  await gotoRoute(page, '/masterdata/memberStates');
  await waitForListReady(page);
  await expect(await findRowByName(page, renamedName)).toHaveCount(0, { timeout: 10_000 });
  await screenshot(loggedInPage, 'member-states-crud-01');
});
