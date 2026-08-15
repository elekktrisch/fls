import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import type { Page } from '@playwright/test';

const NG_TABLE_FILTER_DEBOUNCE_MS = 800;

async function openFirstUserEditPage(page: Page): Promise<void> {
  await gotoRoute(page, '/masterdata/users');
  const firstRow = page.locator('tbody [data-testid="row"]').first();
  await firstRow.waitFor({ state: 'visible' });
  const urlBefore = page.url();
  await firstRow.click();
  await page.waitForFunction((prev) => location.href !== prev, urlBefore);
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);
}

test('persons-add-modal: create person via $modal and assert in list', async ({
  loggedInPage,
}) => {
  await openFirstUserEditPage(loggedInPage);

  const newPersonButton = loggedInPage
    .getByRole('button', { name: /^\s*(NEW|NEU)\s*$/i })
    .first();
  await newPersonButton.waitFor({ state: 'visible' });
  await newPersonButton.click();

  const addPersonModal = loggedInPage.getByRole('dialog');
  await expect(addPersonModal).toBeVisible({ timeout: 10_000 });

  const stamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const firstname = `E2EFirst${stamp}`;
  const lastname = `E2ELast${stamp}`;
  const email = `e2e-${stamp}@example.test`;

  await addPersonModal.locator('#Firstname').fill(firstname);
  await addPersonModal.locator('#Lastname').fill(lastname);
  await addPersonModal.locator('#Email').fill(email);

  const okButton = addPersonModal.locator('button[type="submit"]');
  await expect(okButton).toBeEnabled();
  await okButton.click();

  await expect(addPersonModal).toBeHidden({ timeout: 10_000 });
  await loggedInPage.waitForLoadState('domcontentloaded');

  const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
  const token = await loggedInPage.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    return raw ? (JSON.parse(raw).access_token as string) : null;
  });
  expect(token, 'expected access_token in sessionStorage').toBeTruthy();

  const apiRes = await loggedInPage.request.post(
    `${API_BASE}/api/v1/persons/page/0/50`,
    {
      headers: { Authorization: `Bearer ${token!}`, 'Content-Type': 'application/json' },
      data: { filter: { Lastname: lastname }, sorting: {} },
    },
  );
  expect(apiRes.ok(), `persons paged query -> ${apiRes.status()}`).toBeTruthy();
  const apiBody = await apiRes.json();
  const apiMatches = (apiBody?.Items ?? []).filter(
    (p: { Firstname?: string; Lastname?: string }) =>
      p.Lastname === lastname && p.Firstname === firstname,
  );
  expect(apiMatches.length, 'expected exactly one persisted person via API').toBe(1);

  await gotoRoute(loggedInPage, '/masterdata/persons');
  const lastnameFilter = loggedInPage.locator('input[name="Lastname"]').first();
  await lastnameFilter.waitFor({ state: 'visible' });
  await lastnameFilter.fill(lastname);
  await loggedInPage.waitForTimeout(NG_TABLE_FILTER_DEBOUNCE_MS);
  await loggedInPage.waitForFunction(() => {
    const spinners = Array.from(
      document.querySelectorAll('[data-testid="busy-indicator"]'),
    ) as HTMLElement[];
    return spinners.every((el) => {
      const r = el.getBoundingClientRect();
      return r.width === 0 && r.height === 0;
    });
  }, undefined, { timeout: 15_000 });

  const matchingRow = loggedInPage
    .locator('tbody [data-testid="row"]')
    .filter({ hasText: lastname });
  await expect(matchingRow, 'new person row visible in /masterdata/persons').toHaveCount(1, {
    timeout: 10_000,
  });
  await expect(matchingRow).toContainText(firstname);
  await screenshot(loggedInPage, 'persons-add-modal-01');
});
