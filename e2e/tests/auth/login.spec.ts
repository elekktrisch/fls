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

      // After login, `LoginFormController` sets `$location.path('/dashboard')`,
      // but no route is declared for `/dashboard` — `.otherwise({redirectTo:'/main'})`
      // catches it. So the canonical post-login URL is `#/main`, where
      // `<fls-dashboard ng-if="isLoggedin()">` renders the dashboard component.
      expect(page.url()).toMatch(/#\/main/);

      // The user-menu (`<a><i class="fa-user"></i>...</a>`) only renders when
      // `isLoggedin()` is true — that's the most direct UI signal of a
      // completed login. Faster + more reliable than reading sessionStorage twice.
      await expect(page.locator('nav .fa-user').first()).toBeVisible();

      // Also confirm the access_token landed in ngStorage's sessionStorage entry.
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
    // IMPORTANT: do NOT loop this — 5 failed attempts trigger a 10-minute
    // server-side lockout (IdentityUserManager.MaxFailedAccessAttemptsBeforeLockout).
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    try {
      await loginViaUi(page, USERNAME, 'definitely-not-the-right-password');

      // The visible login form renders the error in a [data-testid="login-error"] alert div.
      const errorAlert = page.locator('[data-testid="login-error"]:visible');
      await expect(errorAlert).toBeVisible({ timeout: 20_000 });
      const errorText = (await errorAlert.textContent())?.trim() ?? '';
      expect(errorText.length, 'error message should be non-empty').toBeGreaterThan(0);

      // No redirect — we should still be on /main.
      expect(page.url()).toMatch(/#\/main/);

      // sessionStorage should NOT have a populated loginResult.
      const loginResult = await page.evaluate(() => sessionStorage.getItem('ngStorage-loginResult'));
      // ngStorage seeds an empty {} by default in AuthService constructor; either null or '{}' is fine,
      // but it must NOT contain an access_token.
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

      // The user-menu dropdown wraps Logout under `<a ng-click="logout()">`.
      // It's inside a Bootstrap dropdown — click the toggle (fa-user icon) first,
      // then click the LOGOUT link. The dropdown markup hangs off the `.fa-user`
      // anchor in the nav bar (only rendered when isLoggedin()).
      await page.locator('nav .fa-user').first().click();
      await page.locator('nav a[ng-click="logout()"]').first().click();

      // AuthService.logout() sends us back to /main.
      await page.waitForURL(/#\/main/, { timeout: 10_000 });

      // Wait for ngStorage to actually persist the logout — same race as login.
      // logout() `delete`s loginResult/user/userRoles from $sessionStorage; the
      // browser's sessionStorage entry should disappear (or reset to '{}' via
      // the $default in AuthService's constructor) once the digest fires.
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

  // Role-gating coverage is reserved for a follow-up batch. The seed work
  // landed in 1a29112 should give testclubuser its UserRole row (FlightOperator),
  // but until that's verified end-to-end, this stays as a skip so the intended
  // surface is visible in the suite output. (Playwright doesn't expose
  // `test.todo` — `test.skip(title, body)` is the closest equivalent and
  // renders as "skipped" in the reporter.)
  test.skip('role gating: testclubuser sees no masterdata admin entries', async () => {
    // TODO(#17): log in as testclubuser, assert masterdata admin menu items
    // (users, flightTypes, memberStates, accountingRuleFilters, deliveries, …)
    // are NOT visible. AuthService.getEnabledFeatures() gates these on
    // isClubAdmin(); FlightOperator should not see them.
  });
});
