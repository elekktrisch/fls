import { test, expect, loginViaUi, screenshot, waitForLoggedInState } from '../../fixtures';

const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';
const WRONG_PASSWORD_SPENDS_ONE_OF_FIVE_ATTEMPTS_BEFORE_A_10_MIN_LOCKOUT =
  'definitely-not-the-right-password';

test.describe('auth flow (UI)', () => {
  test('login success: testclubadmin lands on dashboard with session populated', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    try {
      await loginViaUi(page, USERNAME, PASSWORD);
      await waitForLoggedInState(page);

      expect(page.url()).toMatch(/#\/main/);

      const userMenuRenderedOnlyWhenLoggedIn = page.locator('nav .fa-user').first();
      await expect(userMenuRenderedOnlyWhenLoggedIn).toBeVisible();

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
      await loginViaUi(
        page,
        USERNAME,
        WRONG_PASSWORD_SPENDS_ONE_OF_FIVE_ATTEMPTS_BEFORE_A_10_MIN_LOCKOUT,
      );

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

      const userMenuDropdownToggle = page.locator('nav .fa-user').first();
      await userMenuDropdownToggle.click();
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

  test.skip(
    'role gating: testclubuser sees no masterdata admin entries — unwritten until the seed gives testclubuser its FlightOperator UserRole row',
    (): void => undefined,
  );
});
