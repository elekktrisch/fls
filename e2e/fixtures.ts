import { test as base, expect, Page, APIRequestContext } from '@playwright/test';

const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';
const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

type AuthData = {
  loginResult: Record<string, unknown>;
  user: Record<string, unknown> & { myClub?: unknown };
  userRoles: unknown;
};

let cachedAuth: AuthData | null = null;

async function fetchAuthData(api: APIRequestContext): Promise<AuthData> {
  const tokenRes = await api.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: USERNAME, password: PASSWORD },
  });
  if (!tokenRes.ok()) {
    throw new Error(`Token request failed: ${tokenRes.status()} ${await tokenRes.text()}`);
  }
  const loginResult = await tokenRes.json();
  const headers = { Authorization: `Bearer ${loginResult.access_token}` };

  const userRes = await api.get(`${API_BASE}/api/v1/users/my`, { headers });
  const user = await userRes.json();

  const rolesRes = await api.get(`${API_BASE}/api/v1/userroles`, { headers });
  const userRoles = await rolesRes.json();

  const clubRes = await api.get(`${API_BASE}/api/v1/clubs/my`, { headers });
  user.myClub = await clubRes.json();

  return { loginResult, user, userRoles };
}

type Fixtures = { loggedInPage: Page };

export const test = base.extend<{}, Fixtures>({
  loggedInPage: [async ({ browser, playwright }, use) => {
    if (!cachedAuth) {
      const api = await playwright.request.newContext();
      cachedAuth = await fetchAuthData(api);
      await api.dispose();
    }

    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    await context.addInitScript((authData) => {
      // ngStorage serializes values as JSON strings under "ngStorage-<key>"
      sessionStorage.setItem('ngStorage-loginResult', JSON.stringify(authData.loginResult));
      sessionStorage.setItem('ngStorage-user', JSON.stringify(authData.user));
      sessionStorage.setItem('ngStorage-userRoles', JSON.stringify(authData.userRoles));
    }, cachedAuth);

    const page = await context.newPage();
    await use(page);
    await context.close();
  }, { scope: 'worker' }],
});

export { expect };

export async function gotoRoute(page: Page, hashPath: string): Promise<void> {
  await page.goto('/#' + hashPath);
  await page.waitForLoadState('networkidle');
  // Give AngularJS time to start rendering and mounting controllers so any
  // about-to-appear busy spinner is in the DOM before we wait for it to clear.
  await page.waitForTimeout(500);
  await waitForBusyIndicatorsToClear(page);
  // Final settle for ng digest cycles.
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
}

async function waitForBusyIndicatorsToClear(page: Page): Promise<void> {
  // ng-show toggles display on the .busy-indicator-backdrop around .cssload-loader.
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('.cssload-loader')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: 15_000 });
}

export async function screenshot(page: Page, name: string): Promise<void> {
  await page.screenshot({
    path: `screenshots/${name}.png`,
    fullPage: true,
  });
}
