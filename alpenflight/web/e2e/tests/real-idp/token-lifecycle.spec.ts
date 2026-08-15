import { type Page, type Request } from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import {
  SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS,
  createUser,
  deleteUser,
  pollUserDisabled,
  setUserEnabled,
  withRealmPatch,
} from './_helpers/keycloak-admin';
import { fillKcLogin } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';

const SEED_USER = 'pilot1@example.com';
const SEED_PASSWORD = 'pilot1-dev-2026!';
const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const KC_HOST = 'localhost:8090';

const TWO_LIFESPANS_SO_A_FULL_SILENT_ROTATION_HAS_SETTLED_MS =
  (SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS * 2 + 5) * 1000;
const ONE_LIFESPAN_SO_THE_ACCESS_TOKEN_HAS_EXPIRED_MS =
  (SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS + 5) * 1000;

const alternatingWarmNavTestIdSoEachIterationIsARealUrlChange = (attempt: number): string =>
  attempt % 2 === 0 ? 'af-nav-section-/flights' : 'af-nav-section-/flightreports';

const ignoreClickInterruptedByTheRedirectUnderTest = (): void => undefined;
const ignoreNotRedirectedYetAndReDriveOnTheNextAttempt = (): void => undefined;
const ignoreDeleteFailureBecauseTheRealmSweepIsTheSafetyNet = (): void => undefined;

async function loginAs(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, username, password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
}

async function loginAsEphemeral(page: Page): Promise<{ user: TestUser; userId: string }> {
  const user = freshTestUser();
  const userId = await createUser(user);
  await loginAs(page, user.email, user.password);
  return { user, userId };
}

test.describe('token-lifecycle — realm-mutating', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(120_000);

  test('@quarantine-kc26 silent refresh — SPA stays authenticated past access-token expiry', async ({
    page,
  }) => {
    await withRealmPatch(
      { accessTokenLifespan: SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS },
      async () => {
        await loginAs(page, SEED_USER, SEED_PASSWORD);
        await page.waitForTimeout(TWO_LIFESPANS_SO_A_FULL_SILENT_ROTATION_HAS_SETTLED_MS);

        await page.goto('/flights');
        await page.waitForURL((url) => url.host !== KC_HOST, { timeout: 15_000 });
        await page.waitForLoadState('networkidle');
        expect(new URL(page.url()).host).not.toBe(KC_HOST);
        await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
      },
    );
  });

  test('hard 401 — disabled user is redirected on next API call', async ({ page }, testInfo) => {
    allowConsoleErrors(
      testInfo,
      /\b401\b/,
      /SilentRenewFailed/i,
      /token\(s\) validation failed, resetting/i,
    );
    let userCtx: { user: TestUser; userId: string } | undefined;
    try {
      await withRealmPatch(
        { accessTokenLifespan: SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS },
        async () => {
          userCtx = await loginAsEphemeral(page);
          await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);

          await setUserEnabled(userCtx.userId, false, userCtx.user.email);
          await pollUserDisabled(userCtx.userId);

          await page.waitForTimeout(ONE_LIFESPAN_SO_THE_ACCESS_TOKEN_HAS_EXPIRED_MS);

          const spaHost = new URL(SPA_BASE_URL).host;
          const isRedirected = (): boolean => {
            const url = new URL(page.url());
            return (
              url.host === KC_HOST ||
              (url.host === spaHost && (url.pathname === '/' || url.pathname.startsWith('/auth/')))
            );
          };
          for (let attempt = 0; attempt < 12 && !isRedirected(); attempt++) {
            const navLink = page.getByTestId(
              alternatingWarmNavTestIdSoEachIterationIsARealUrlChange(attempt),
            );
            if (await navLink.count()) {
              await navLink
                .click({ timeout: 2_000 })
                .catch(ignoreClickInterruptedByTheRedirectUnderTest);
            }
            await page
              .waitForURL(() => isRedirected(), { timeout: 5_000 })
              .catch(ignoreNotRedirectedYetAndReDriveOnTheNextAttempt);
          }

          await page.waitForURL(() => isRedirected(), { timeout: 15_000 });
        },
      );
    } finally {
      if (userCtx) {
        await deleteUser(userCtx.userId, userCtx.user.email).catch(
          ignoreDeleteFailureBecauseTheRealmSweepIsTheSafetyNet,
        );
      }
    }
  });
});

test.describe('token-lifecycle — non-mutating', () => {
  test.describe.configure({ mode: 'serial' });

  test('multi-tab logout — tab A logout invalidates tab B on next navigation', async ({
    context,
  }, testInfo) => {
    context.on('page', (p) => watchConsoleErrors(p, testInfo));
    const tabA = await context.newPage();
    const tabB = await context.newPage();

    await loginAs(tabA, SEED_USER, SEED_PASSWORD);
    await tabB.goto('/start');
    await tabB.waitForURL((url) => !url.pathname.startsWith('/realms/') && url.pathname !== '/', {
      timeout: 15_000,
    });
    await expect(tabB.getByTestId('landing-topbar-sign-in')).toHaveCount(0);

    await tabA.goto('/auth/logout');
    await tabA.waitForURL((url) => url.pathname === '/' && !url.searchParams.has('code'), {
      timeout: 15_000,
    });
    await expect(tabA.getByTestId('landing-topbar-sign-in')).toBeVisible();

    await tabB.goto('/clubs');
    await tabB.waitForURL(
      (url) => url.host === KC_HOST || url.pathname === '/' || url.pathname.startsWith('/auth/'),
      { timeout: 15_000 },
    );
    const finalPath = new URL(tabB.url()).pathname;
    expect(finalPath).not.toMatch(/^\/clubs/);
  });

  test('Bearer scoping — only /api/v1/* carries Authorization', async ({ page }) => {
    const observed: Array<{ url: string; hasBearer: boolean }> = [];
    const onRequest = (req: Request): void => {
      const authHeaderSnapshottedBeforeTheRequestCanMutate = req.headers()['authorization'];
      observed.push({
        url: req.url(),
        hasBearer:
          typeof authHeaderSnapshottedBeforeTheRequestCanMutate === 'string' &&
          /^Bearer /i.test(authHeaderSnapshottedBeforeTheRequestCanMutate),
      });
    };
    page.on('request', onRequest);

    await loginAs(page, SEED_USER, SEED_PASSWORD);
    const apiResponse = page.waitForResponse(
      (r) => {
        const u = new URL(r.url());
        return u.host === new URL(SPA_BASE_URL).host && u.pathname.startsWith('/api/v1/');
      },
      { timeout: 15_000 },
    );
    await page.goto('/start');
    await apiResponse;
    page.off('request', onRequest);

    const apiCalls = observed.filter((r) => {
      const u = new URL(r.url);
      return u.host === new URL(SPA_BASE_URL).host && u.pathname.startsWith('/api/v1/');
    });
    const kcCalls = observed.filter((r) => new URL(r.url).host === KC_HOST);

    expect(apiCalls.length, 'no /api/v1/* calls observed — spec bug').toBeGreaterThan(0);
    expect(kcCalls.length, 'no Keycloak calls observed — spec bug').toBeGreaterThan(0);
    for (const r of apiCalls) {
      expect(r.hasBearer, `expected Bearer on ${r.url}`).toBe(true);
    }
    for (const r of kcCalls) {
      expect(r.hasBearer, `unexpected Bearer on Keycloak request ${r.url}`).toBe(false);
    }
  });
});
