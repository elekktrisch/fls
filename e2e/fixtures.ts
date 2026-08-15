import { test as base, expect, Page, APIRequestContext } from '@playwright/test';
import { spawnSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

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
  let tokenRes;
  let lastErr: unknown;
  for (let attempt = 1; attempt <= 6; attempt++) {
    try {
      tokenRes = await api.post(`${API_BASE}/Token`, {
        form: { grant_type: 'password', username: USERNAME, password: PASSWORD },
        timeout: 30_000,
      });
      if (tokenRes.ok()) { lastErr = undefined; break; }
      if (tokenRes.status() !== 500 && tokenRes.status() < 502) {
        break;
      }
    } catch (err) {
      lastErr = err;
      tokenRes = undefined;
    }
    await new Promise((r) => setTimeout(r, 500 * attempt));
  }
  if (!tokenRes) {
    throw lastErr ?? new Error('Token request failed: no response and no error captured');
  }
  if (!tokenRes.ok()) {
    throw new Error(`Token request failed: ${tokenRes.status()} ${await tokenRes.text()}`);
  }
  const loginResult = await tokenRes!.json();
  const headers = { Authorization: `Bearer ${loginResult.access_token}` };

  const userRes = await api.get(`${API_BASE}/api/v1/users/my`, { headers });
  const user = await userRes.json();

  const rolesRes = await api.get(`${API_BASE}/api/v1/userroles`, { headers });
  const userRoles = await rolesRes.json();

  const clubRes = await api.get(`${API_BASE}/api/v1/clubs/my`, { headers });
  user.myClub = await clubRes.json();

  return { loginResult, user, userRoles };
}

export async function loginViaUi(
  page: Page,
  username: string,
  password: string,
): Promise<void> {
  await page.goto('/#/main');
  await page.waitForLoadState('domcontentloaded');

  await page.locator('[data-testid="login-toggle"]').click();

  await page.locator('[data-testid="username-input"]:visible').fill(username);
  await page.locator('[data-testid="password-input"]:visible').fill(password);
  await page.locator('[data-testid="login-submit"]:visible').click();
}

export async function waitForLoggedInState(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const lr = sessionStorage.getItem('ngStorage-loginResult');
    if (!lr) return false;
    try { return !!JSON.parse(lr).access_token; } catch { return false; }
  }, undefined, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
}

type Fixtures = { loggedInPage: Page; uiLoggedInPage: Page; freshDb: void; freshLoggedInPage: Page };

export const test = base.extend<Fixtures>({
  loggedInPage: async ({ browser, playwright }, use) => {
    if (!cachedAuth) {
      const api = await playwright.request.newContext();
      cachedAuth = await fetchAuthData(api);
      await api.dispose();
    }

    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    await context.addInitScript((authData) => {
      sessionStorage.setItem('ngStorage-loginResult', JSON.stringify(authData.loginResult));
      sessionStorage.setItem('ngStorage-user', JSON.stringify(authData.user));
      sessionStorage.setItem('ngStorage-userRoles', JSON.stringify(authData.userRoles));
    }, cachedAuth);

    const page = await context.newPage();
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await use(page);
    await context.close();
  },

  uiLoggedInPage: async ({ browser }, use) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    await loginViaUi(page, USERNAME, PASSWORD);
    await waitForLoggedInState(page);
    await use(page);
    await context.close();
  },

  freshDb: async ({}, use) => {
    const seedScript = path.resolve(__dirname, 'scripts/seed.sh');
    const result = spawnSync('bash', [seedScript], {
      stdio: 'inherit',
      env: {
        ...process.env,
        FLS_MSSQL_CONTAINER: process.env.FLS_MSSQL_CONTAINER ?? 'fls-e2e-mssql-1',
      },
    });
    if (result.status !== 0) {
      throw new Error(
        `seed.sh exited with status ${result.status} (signal=${result.signal ?? 'none'}). ` +
        `Check stdout/stderr above. Common causes: 'fls-mssql' container not running, ` +
        `or the seed files in flsserver/database/FLSTest/ are out of sync with the schema.`,
      );
    }
    cachedAuth = null;
    await new Promise((r) => setTimeout(r, 200));
    await use();
  },

  freshLoggedInPage: async ({ browser, playwright }, use) => {
    const seedScript = path.resolve(__dirname, 'scripts/seed.sh');
    const result = spawnSync('bash', [seedScript], {
      stdio: 'inherit',
      env: {
        ...process.env,
        FLS_MSSQL_CONTAINER: process.env.FLS_MSSQL_CONTAINER ?? 'fls-e2e-mssql-1',
      },
    });
    if (result.status !== 0) {
      throw new Error(
        `seed.sh exited with status ${result.status} (signal=${result.signal ?? 'none'}).`,
      );
    }
    cachedAuth = null;
    {
      const api = await playwright.request.newContext();
      for (let i = 0; i < 25; i++) {
        const probe = await api.get(`${API_BASE}/api/v1/countries`).catch(() => null);
        if (probe && probe.ok()) break;
        await new Promise((r) => setTimeout(r, 200));
      }
      await api.dispose();
    }
    if (!cachedAuth) {
      const api = await playwright.request.newContext();
      cachedAuth = await fetchAuthData(api);
      await api.dispose();
    }
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    await context.addInitScript((authData) => {
      sessionStorage.setItem('ngStorage-loginResult', JSON.stringify(authData.loginResult));
      sessionStorage.setItem('ngStorage-user', JSON.stringify(authData.user));
      sessionStorage.setItem('ngStorage-userRoles', JSON.stringify(authData.userRoles));
    }, cachedAuth);
    const page = await context.newPage();
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await use(page);
    await context.close();
  },
});

export { expect };

export async function gotoRoute(page: Page, hashPath: string): Promise<void> {
  await page.goto('/#' + hashPath);
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);
  await waitForBusyIndicatorsToClear(page);
  await page.waitForTimeout(300);
}

export async function waitForBusyIndicatorsToClear(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: 30_000 });
}

export async function screenshot(page: Page, name: string): Promise<void> {
  const info = base.info();
  const category = path.basename(path.dirname(info.file));
  const dir = path.join(__dirname, 'screenshots', category);
  await fs.promises.mkdir(dir, { recursive: true });
  await page.screenshot({
    path: path.join(dir, `${name}.png`),
    fullPage: true,
  });
}
