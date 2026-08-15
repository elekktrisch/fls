import { test, expect, loginViaUi, screenshot, waitForLoggedInState } from '../../fixtures';

const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';

test.describe('auth flow (UI)', () => {
  test('login success: testclubadmin lands on dashboard with session populated', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    try {
      await loginViaUi(page, USERNAME, PASSWORD);
      await waitForLoggedInState(page);

      expect(page.url()).toMatch(/#\/main/);

      await expect(page.locator('nav .fa-user').first()).toBeVisible();

      const loginResult = await page.evaluate(() => sessionStorage.getItem('ngStorage-loginResult'));
      expect(loginResult, 'sessionStorage["ngStorage-loginResult"] should be populated after UI login').toBeTruthy();
      const parsed = JSON.parse(loginResult as string);
      expect(parsed.access_token, 'login response must contain access_token').toBeTruthy();
      await screenshot(page, 'login-success');
    } finally {
      await context.close();
    }
  });

  test('login failure: wrong password surfaces an error and stays on /main', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    try {
      await loginViaUi(page, USERNAME, 'definitely-not-the-right-password');

      const errorAlert = page.locator('[data-testid="login-error"]:visible');
      await expect(errorAlert).toBeVisible({ timeout: 20_000 });
      const errorText = (await errorAlert.textContent())?.trim() ?? '';
      expect(errorText.length, 'error message should be non-empty').toBeGreaterThan(0);

      expect(page.url()).toMatch(/#\/main/);

      const loginResult = await page.evaluate(() => sessionStorage.getItem('ngStorage-loginResult'));
      if (loginResult) {
        const parsed = JSON.parse(loginResult);
        expect(parsed.access_token, 'no token expected after failed login').toBeFalsy();
      }
      await screenshot(page, 'login-fail-wrong-password');
    } finally {
      await context.close();
    }
  });

  test('login failure: unknown username surfaces an error and stays on /main', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    try {
      await loginViaUi(page, 'nobodysuchuser', PASSWORD);

      const errorAlert = page.locator('[data-testid="login-error"]:visible');
      await expect(errorAlert).toBeVisible({ timeout: 20_000 });
      const errorText = (await errorAlert.textContent())?.trim() ?? '';
      expect(errorText.length, 'error message should be non-empty').toBeGreaterThan(0);

      expect(page.url()).toMatch(/#\/main/);
      await screenshot(page, 'login-fail-unknown-user');
    } finally {
      await context.close();
    }
  });

  test('logout: clicking Logout returns to /main and clears the session', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    try {
      await loginViaUi(page, USERNAME, PASSWORD);
      await waitForLoggedInState(page);

      await page.locator('nav .fa-user').first().click();
      await page.locator('nav a[ng-click="logout()"]').first().click();

      await page.waitForURL(/#\/main/, { timeout: 10_000 });

      await page.waitForFunction(() => {
        const lr = sessionStorage.getItem('ngStorage-loginResult');
        if (!lr) return true;
        try { return !JSON.parse(lr).access_token; } catch { return true; }
      }, undefined, { timeout: 10_000 });

      const loginResult = await page.evaluate(() => sessionStorage.getItem('ngStorage-loginResult'));
      const userRecord = await page.evaluate(() => sessionStorage.getItem('ngStorage-user'));
      if (loginResult) {
        const parsed = JSON.parse(loginResult);
        expect(parsed.access_token, 'access_token should be cleared after logout').toBeFalsy();
      }
      expect(userRecord, 'user record should be cleared after logout').toBeFalsy();
      await screenshot(page, 'logout');
    } finally {
      await context.close();
    }
  });

  test.skip('role gating: testclubuser sees no masterdata admin entries', async () => {
  });
});
