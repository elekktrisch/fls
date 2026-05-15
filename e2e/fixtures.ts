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
  // The freshDb fixture may resolve in parallel with loggedInPage —
  // Playwright doesn't sequence independent fixtures. If seed.sh is
  // mid-DROP-DATABASE while /Token fires, the FLS server can return 500
  // (Mono+EF momentarily can't reach FLSTest). Retry a few times with a
  // short backoff so that race resolves transparently. Genuine auth
  // failures (e.g. wrong password = 400, server-down = ECONNREFUSED)
  // still surface after the retry budget is exhausted.
  let tokenRes;
  for (let attempt = 1; attempt <= 6; attempt++) {
    tokenRes = await api.post(`${API_BASE}/Token`, {
      form: { grant_type: 'password', username: USERNAME, password: PASSWORD },
    });
    if (tokenRes.ok()) break;
    if (tokenRes.status() !== 500 && tokenRes.status() < 502) {
      // 4xx (or 502/503/504) is not a race — fail fast.
      break;
    }
    await new Promise((r) => setTimeout(r, 500 * attempt));
  }
  if (!tokenRes!.ok()) {
    throw new Error(`Token request failed: ${tokenRes!.status()} ${await tokenRes!.text()}`);
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

/**
 * Drive the visible login form in the navbar (desktop layout) and submit it.
 * The login-form directive is rendered twice — once in the navbar (visible on
 * >= sm after clicking the Login toggle), and once inline on /main (only
 * shown on < sm). We disambiguate by filtering each input/button by `:visible`
 * directly, NOT by filtering the form: the `<form>` element itself has
 * height 0 (its parent `<fls-login-form>` is `display:inline`), so a
 * `[data-testid="login-form"]:visible` selector matches nothing even when
 * the form is on screen. Filtering the inputs themselves works because they
 * have non-zero box geometry when visible.
 */
export async function loginViaUi(
  page: Page,
  username: string,
  password: string,
): Promise<void> {
  await page.goto('/#/main');
  await page.waitForLoadState('domcontentloaded');

  // Reveal the navbar login form (the inline /main form is `hidden-lg
  // hidden-md hidden-sm` and never appears on the 1280-wide desktop viewport).
  await page.locator('[data-testid="login-toggle"]').click();

  // Filter by `:visible` on each control to skip the hidden mobile copy.
  await page.locator('[data-testid="username-input"]:visible').fill(username);
  await page.locator('[data-testid="password-input"]:visible').fill(password);
  await page.locator('[data-testid="login-submit"]:visible').click();
}

/**
 * Wait for the post-login state to settle: ngStorage has the access_token
 * persisted, the nav-bar is showing the logged-in chrome.
 *
 * Quirks this works around:
 *
 * - `LoginFormController.login().then(...)` calls `$location.path('/dashboard')`,
 *   but `/dashboard` has no route — `MainModule.js` declares only `/main` and
 *   `.otherwise({ redirectTo: '/main' })`. So the URL transitions briefly
 *   through `#/dashboard` and immediately lands on `#/main`. Waiting for
 *   `#/dashboard` is unreliable (we may miss the transient).
 * - ngStorage syncs in-memory `$sessionStorage` to the browser's real
 *   `sessionStorage` on `$rootScope` digest. Right after the login resolves,
 *   the digest that writes the access_token may not have fired, so a naive
 *   `evaluate(() => sessionStorage.getItem(...))` reads the default `'{}'`
 *   intermittently.
 *
 * Polling `sessionStorage` from the page is the only signal that survives
 * both races.
 */
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
      // ngStorage serializes values as JSON strings under "ngStorage-<key>"
      sessionStorage.setItem('ngStorage-loginResult', JSON.stringify(authData.loginResult));
      sessionStorage.setItem('ngStorage-user', JSON.stringify(authData.user));
      sessionStorage.setItem('ngStorage-userRoles', JSON.stringify(authData.userRoles));
    }, cachedAuth);

    const page = await context.newPage();
    // addInitScript only fires on the FIRST navigation. The page starts on
    // about:blank, where sessionStorage is inaccessible (SecurityError:
    // "Access is denied for this document"). Do an initial navigation to
    // `/` so any test that calls page.evaluate(sessionStorage...) before
    // gotoRoute() works. Use `domcontentloaded` (cheap) — HMR keeps
    // networkidle from ever firing.
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await use(page);
    await context.close();
  },

  /**
   * UI-driven login. Unlike `loggedInPage` (which injects sessionStorage via
   * an init script — fast but bypasses every codepath the user actually
   * touches), this fixture exercises the real auth flow: it opens the navbar
   * login form, fills the visible inputs, and waits for the dashboard.
   *
   * Use this when the test cares about the auth flow itself (the auth.spec.ts
   * suite) or when you want to verify that the post-login redirect / nav-bar
   * hydration works end-to-end. Use `loggedInPage` for everything else — it's
   * ~5x faster because it skips the Angular bootstrap → form-render → token →
   * /users/my → /userroles → /clubs/my chain.
   */
  uiLoggedInPage: async ({ browser }, use) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    await loginViaUi(page, USERNAME, PASSWORD);
    await waitForLoggedInState(page);
    await use(page);
    await context.close();
  },

  /**
   * Opt-in PER-TEST fixture that brings the FLSTest database back to a
   * known, deterministic state by running `e2e/scripts/seed.sh`.
   *
   * Why per-test? Mutation specs assume pristine state — e.g. the
   * historical fixture flight is `Valid (30)`, the seeded
   * AccountingRuleFilters are at known IDs, no test-created rows exist.
   * Worker-scoped reseed leaked state between tests (test N's mutations
   * broke test N+1's preconditions).
   *
   * The .bak cache in `seed.sh` keeps the per-test cost down to ~5s
   * after the first run (which builds the cache in ~30s). Tests that
   * don't need a pristine DB simply don't destructure `freshDb`.
   *
   * Why not a transactional rollback?
   * The FLS server runs on EF6 with its own connection pool — a per-test
   * BEGIN TRANSACTION from outside the server would never see EF's writes,
   * and Snapshot isolation isn't available across the public API surface.
   *
   * Usage:
   *   test('mutation flow', async ({ loggedInPage, freshDb }) => { ... });
   */
  freshDb: async ({}, use) => {
    const seedScript = path.resolve(__dirname, 'scripts/seed.sh');
    const result = spawnSync('bash', [seedScript], {
      stdio: 'inherit',
      env: {
        ...process.env,
        // docker-compose -p fls-e2e names the container fls-e2e-mssql-1.
        // seed.sh defaults to fls-mssql (manual `docker run --name fls-mssql`).
        // Default to the compose name so the suite works out of the box; allow
        // override via FLS_MSSQL_CONTAINER for devs running the manual stack.
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
    // After re-seeding, the cached auth token may still be valid (the testclubadmin
    // user is reseeded with the same credentials) but the sessionStorage payload
    // captured before the seed might reference stale IDs. Drop the cache so the
    // next `loggedInPage` re-fetches.
    cachedAuth = null;
    // After DROP+CREATE+RESTORE, EF's connection pool can briefly serve 500s
    // before reconnecting. Give it a beat so the test body's first call
    // doesn't race with the recovery.
    await new Promise((r) => setTimeout(r, 200));
    await use();
  },

  /**
   * Convenience fixture: seed the DB, THEN create the logged-in page.
   * Use this instead of `{ loggedInPage, freshDb }` whenever a test
   * needs both — those two siblings would otherwise resolve in
   * parallel, racing loggedInPage's /Token + page.goto/ against
   * freshDb's DROP+CREATE+RESTORE.
   */
  freshLoggedInPage: async ({ browser, playwright }, use) => {
    // Sequence: 1. seed.sh, 2. /Token (cached after first), 3. page+inject.
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
    // Force re-fetch in case prior creds got invalidated.
    cachedAuth = null;
    // After DROP+CREATE+RESTORE, EF's connection pool can briefly serve 500s
    // before reconnecting. Probe the server until it's healthy. Caps at 5s.
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
  // We used to wait for `networkidle` here, but webpack-dev-server's
  // HMR/live-reload websocket keeps the network non-idle indefinitely
  // (each gotoRoute would burn the 30s test timeout). `domcontentloaded`
  // is enough since the AngularJS app boots after DOMContentLoaded and
  // the busy-indicator + ng-digest waits below cover the actual settle.
  await page.waitForLoadState('domcontentloaded');
  // Give AngularJS time to start rendering and mounting controllers so any
  // about-to-appear busy spinner is in the DOM before we wait for it to clear.
  await page.waitForTimeout(500);
  await waitForBusyIndicatorsToClear(page);
  // Final settle for ng digest cycles.
  await page.waitForTimeout(300);
}

async function waitForBusyIndicatorsToClear(page: Page): Promise<void> {
  // ng-show toggles display on the [data-testid="busy-indicator"] wrapper around the spinner.
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: 15_000 });
}

/**
 * Save a full-page screenshot under e2e/screenshots/<category>/<name>.png.
 *
 * The category is auto-derived from the directory the running spec lives in
 * (e.g. tests/flights/create.spec.ts → category "flights"). Callers don't
 * pass the category — they just pass a short name unique within their
 * category. The gh-pages publisher walks these subfolders to group
 * screenshots by feature area.
 */
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
