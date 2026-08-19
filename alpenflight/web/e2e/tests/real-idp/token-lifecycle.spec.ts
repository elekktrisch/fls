import { type Page, type Request } from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';
import {
  countRefreshTokenGrantsKeycloakAccepted,
  countRefreshTokenGrantsKeycloakRejected,
} from '../_helpers/keycloak-grant-counters';

import {
  ACCESS_TOKEN_LIFESPAN_ABOVE_SPA_RENEW_WINDOW_SECONDS,
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
const SPA_HOST = new URL(SPA_BASE_URL).host;
const KC_HOST = 'localhost:8090';

const PAST_THE_EXPIRY_OF_THE_ACCESS_TOKEN_THE_LOGIN_MINTED_MS =
  (ACCESS_TOKEN_LIFESPAN_ABOVE_SPA_RENEW_WINDOW_SECONDS + 10) * 1000;
const DWELL_LONG_ENOUGH_TO_EXPOSE_A_RE_AUTHORIZE_LOOP_MS = 8_000;
const WITHIN_ONE_LIFESPAN_THE_SPA_MUST_HAVE_ATTEMPTED_A_ROTATION_MS =
  ACCESS_TOKEN_LIFESPAN_ABOVE_SPA_RENEW_WINDOW_SECONDS * 1000;

const FLIGHTS_NAV_TEST_ID = 'af-nav-section-/flights';
const KC_AUTHORIZE_ENDPOINT_PATH = '/protocol/openid-connect/auth';

const isSignedOutDestination = (url: URL): boolean =>
  url.host === KC_HOST ||
  (url.host === SPA_HOST && (url.pathname === '/' || url.pathname.startsWith('/auth/')));

const isSettledPrivateRoute = (url: URL): boolean =>
  url.host === SPA_HOST && url.pathname !== '/' && !url.pathname.startsWith('/auth/');

function countAuthorizeRequests(page: Page): () => number {
  let observed = 0;
  page.on('request', (request: Request) => {
    if (request.url().includes(KC_AUTHORIZE_ENDPOINT_PATH)) {
      observed += 1;
    }
  });
  return () => observed;
}

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
  test.setTimeout(180_000);

  test('silent refresh — SPA stays authenticated past access-token expiry', async ({ page }) => {
    const refreshTokenGrantsKeycloakAccepted = countRefreshTokenGrantsKeycloakAccepted(page);
    await withRealmPatch(
      { accessTokenLifespan: ACCESS_TOKEN_LIFESPAN_ABOVE_SPA_RENEW_WINDOW_SECONDS },
      async () => {
        await loginAs(page, SEED_USER, SEED_PASSWORD);

        await page.getByTestId(FLIGHTS_NAV_TEST_ID).click();
        await page.waitForURL(/\/flights$/, { timeout: 15_000 });

        await page.waitForTimeout(PAST_THE_EXPIRY_OF_THE_ACCESS_TOKEN_THE_LOGIN_MINTED_MS);

        expect(
          refreshTokenGrantsKeycloakAccepted(),
          'Keycloak accepted no refresh_token grant — the SPA never renewed. A rejected rotation ' +
            'plus a silent re-authorize on the live SSO cookie reaches every assertion below, so ' +
            'a pass would prove a re-login and not a silent refresh',
        ).toBeGreaterThan(0);
        expect(new URL(page.url()).host).not.toBe(KC_HOST);
        await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
        await expect(page, 'a silent renew must not move the user off the page').toHaveURL(
          /\/flights$/,
        );
      },
    );
  });

  test('disabled user — Keycloak rejects the next rotation and the SPA drops the session', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(
      testInfo,
      /\b40[013]\b/,
      /silent ?renew ?failed/i,
      /OidcService code request/,
      /token\(s\) validation failed, resetting/i,
    );
    const authorizeRequests = countAuthorizeRequests(page);
    const rejectedRotations = countRefreshTokenGrantsKeycloakRejected(page);
    let userCtx: { user: TestUser; userId: string } | undefined;
    try {
      await withRealmPatch(
        { accessTokenLifespan: ACCESS_TOKEN_LIFESPAN_ABOVE_SPA_RENEW_WINDOW_SECONDS },
        async () => {
          userCtx = await loginAsEphemeral(page);
          await page.waitForURL((url) => isSettledPrivateRoute(url), { timeout: 30_000 });
          const privateRouteTheDisabledUserMustLose = new URL(page.url()).pathname;
          await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);

          const authorizeRequestsWhenTheLoginSettled = authorizeRequests();
          await page.waitForTimeout(DWELL_LONG_ENOUGH_TO_EXPOSE_A_RE_AUTHORIZE_LOOP_MS);
          expect(
            authorizeRequests(),
            'the SPA authorized again while it was still signed in — it is in a login loop, so any later redirect would prove the loop and not the disable',
          ).toBe(authorizeRequestsWhenTheLoginSettled);
          expect(
            new URL(page.url()).pathname,
            'the SPA left the private route before the user was disabled — the session was never stable, so a later redirect would prove nothing',
          ).toBe(privateRouteTheDisabledUserMustLose);

          await setUserEnabled(userCtx.userId, false, userCtx.user.email);
          await pollUserDisabled(userCtx.userId);

          await expect
            .poll(rejectedRotations, {
              timeout: WITHIN_ONE_LIFESPAN_THE_SPA_MUST_HAVE_ATTEMPTED_A_ROTATION_MS,
              message:
                'Keycloak never rejected a refresh_token grant for the disabled user — without that rejection the redirect below cannot be the disable',
            })
            .toBeGreaterThan(0);

          await page.waitForURL((url) => isSignedOutDestination(url), { timeout: 30_000 });
          expect(
            authorizeRequests(),
            'the SPA reached the signed-out destination without a new authorize request',
          ).toBeGreaterThan(authorizeRequestsWhenTheLoginSettled);
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
