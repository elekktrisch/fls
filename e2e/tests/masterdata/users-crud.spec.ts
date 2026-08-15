
import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import type { Page } from '@playwright/test';

test.describe.configure({ mode: 'serial' });

async function waitBusyClear(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const r = el.getBoundingClientRect();
      return r.width === 0 && r.height === 0;
    });
  }, undefined, { timeout: 15_000 });
}

test('masterdata-users:create-edit-delete', async ({ loggedInPage }) => {
  const page = loggedInPage;

  page.on('dialog', async d => { await d.accept(); });

  const nonce = `${Date.now().toString().slice(-8)}${Math.floor(Math.random() * 1000)}`;
  const username = `e2euser${nonce}`;
  const friendly = `E2E User ${nonce}`;
  const friendlyEdited = `${friendly} (edited)`;
  const email = `e2e-${nonce}@e2e.fls.local`;

  await gotoRoute(page, '/masterdata/users/new');

  await expect(page.locator('input#UserName')).toBeVisible();

  await page.fill('input#UserName', username);
  await page.fill('input#FriendlyName', friendly);
  await page.fill('input#NotificationEmail', email);

  await page.locator('form[name="userForm"] button[type="submit"]').click();
  await page.waitForURL(/\/masterdata\/users(?:\?|$|#)/, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
  await waitBusyClear(page);

  const matchingRow = page.locator('tbody [data-testid="row"]', { hasText: username });
  await expect(matchingRow).toHaveCount(1, { timeout: 15_000 });
  await expect(matchingRow).toContainText(friendly);

  await matchingRow.click();
  await page.waitForURL(/\/masterdata\/users\/[a-f0-9-]+/, { timeout: 10_000 });
  await page.waitForLoadState('domcontentloaded');
  await waitBusyClear(page);
  const friendlyInput = page.locator('input#FriendlyName');
  await expect(friendlyInput).toBeVisible();
  await expect(friendlyInput).toHaveValue(friendly);
  await friendlyInput.fill(friendlyEdited);
  await page.locator('form[name="userForm"] button[type="submit"]').click();
  await page.waitForURL(/\/masterdata\/users(?:\?|$|#)/, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
  await waitBusyClear(page);

  await expect(matchingRow).toHaveCount(1, { timeout: 15_000 });
  await expect(matchingRow).toContainText(friendlyEdited);

  const deleteLink = matchingRow.locator('a.delete-link');
  const deleteCount = await deleteLink.count();
  const deleteVisible = deleteCount > 0 ? await deleteLink.first().isVisible() : false;
  if (deleteCount === 0 || !deleteVisible) {
    test.info().annotations.push({
      type: 'delete-skipped',
      description: 'No visible delete link on the new user row (CanDeleteRecord=false). Stopping after create+edit.',
    });
    return;
  }
  const deletePromise = page.waitForResponse(r =>
    /\/api\/v1\/users\/[a-f0-9-]+$/i.test(r.url()) && r.request().method() === 'POST',
    { timeout: 15_000 });
  await deleteLink.first().click();
  await deletePromise;

  await gotoRoute(page, '/masterdata/users');
  await expect(matchingRow).toHaveCount(0, { timeout: 15_000 });
  await screenshot(loggedInPage, 'users-crud-01');
});
