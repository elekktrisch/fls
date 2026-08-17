import {
  type Browser,
  type BrowserContext,
  type BrowserContextOptions,
  type Page,
  type Request,
  type Response,
  type TestInfo,
} from '@playwright/test';
import { allowConsoleErrors, expect, test, watchConsoleErrors } from '../_helpers/console-guard';

import { createUser, deleteUser, findUserByEmail } from './_helpers/keycloak-admin';
import { KC_ERROR_SELECTOR, fillKcLogin, fillKcRegistration } from './_helpers/kc-form';
import {
  extractVerifyLink,
  purgeMailpit,
  waitForExactlyOneMessage,
} from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';
import { freshTestUser, type TestUser } from './_helpers/test-user';

const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const SPA_HOST = new URL(SPA_BASE_URL).host;
const KC_HOST = 'localhost:8090';

const KC_REALM_PAGE = /\/realms\/alpenflight\//;
const KC_AUTHENTICATE_PAGE = /\/realms\/alpenflight\/login-actions\/authenticate/;

const KC_FORGOT_PASSWORD_LINK = 'a[href*="reset-credentials"]';
const KC_RESET_ADDRESS_FORM = '#kc-reset-password-form';
const KC_RESET_ADDRESS_FIELD = '#username';
const KC_UPDATE_PASSWORD_FORM = '#kc-passwd-update-form';
const KC_NEW_PASSWORD_FIELD = '#password-new';
const KC_NEW_PASSWORD_CONFIRM_FIELD = '#password-confirm';
const KC_THEME_BACK_LINK = 'a.af-back-to-landing';
const KC_CONFIRM_VALIDITY_INTERSTITIAL_PROCEED_LINK =
  '#kc-info-message a[href*="login-actions/action-token"]';

const KC_TOKEN_ENDPOINT_PATH = '/protocol/openid-connect/token';
const AUTHORIZATION_CODE_GRANT_BODY = 'grant_type=authorization_code';

const LOSTPASSWORD_PATH = '/lostpassword';
const CONFIRM_PATH = '/confirm';
const CONFIRM_PATH_AND_QUERY_THE_VERIFIED_BACK_LINK_TARGETS = '/confirm?outcome=verified';
const LANDING_PATH = '/';
const LOGOUT_PATH = '/auth/logout';
const SIGNUP_PATH_THE_LANDING_MIGRATE_CTA_TARGETS = '/signup?intent=migrate';

const TEST_ID = {
  lostpasswordPage: 'lostpassword-page',
  lostpasswordStart: 'lostpassword-start',
  confirmPage: 'confirm-page',
  confirmOutcomeVerified: 'confirm-outcome-verified',
  confirmSignIn: 'confirm-sign-in',
  landingTopbarSignIn: 'landing-topbar-sign-in',
  landingTopbarLostPassword: 'landing-topbar-lost-password',
  signupPage: 'signup-page',
  signupLocal: 'signup-local',
} as const;

const PASSWORD_THE_RESET_LINK_SETS = 'E2eReset-2026!';

const AF_LOSTPASSWORD_SHOT_THE_GALLERY_PAIRS_AGAINST_THE_LEGACY_FORM =
  'alpenflight-lostpassword-form.png';
const AF_CONFIRM_SHOT_THE_GALLERY_PAIRS_AGAINST_THE_LEGACY_CONFIRM_FORM =
  'alpenflight-confirm-verified.png';

const MOBILE_PORTRAIT_VIEWPORT = { width: 360, height: 640 } as const;
const MINIMUM_TOUCH_TARGET_EDGE_PX = 44;

const COLD_FIRST_SMTP_SEND_TIMEOUT_MS = 45_000;
const REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS = 30_000;
const THE_WHOLE_RESET_CHAIN_INCLUDING_TWO_KEYCLOAK_LOGINS_TIMEOUT_MS = 120_000;

const A_BRAND_NEW_KEYCLOAK_USER_HAS_NO_ALPENFLIGHT_CLUB_SO_ITS_OWN_MEMBER_READS_ANSWER_403_OR_404 =
  /\b40[34]\b/;

const KEYCLOAK_SERVES_THE_SPENT_LINK_ERROR_PAGE_WITH_THE_STATUS_THIS_CASE_ASSERTS =
  /Failed to load resource.*\b400\b/;
const HTTP_STATUS_KEYCLOAK_SERVES_THE_SPENT_ACTION_LINK_WITH = 400;

interface AccountRecoveryRoute {
  readonly path: string;
  readonly rendersTestId: string;
  readonly callsToActionTestIds: readonly string[];
}

const ACCOUNT_RECOVERY_ROUTES: readonly AccountRecoveryRoute[] = [
  {
    path: LOSTPASSWORD_PATH,
    rendersTestId: TEST_ID.lostpasswordPage,
    callsToActionTestIds: [TEST_ID.lostpasswordStart],
  },
  {
    path: CONFIRM_PATH,
    rendersTestId: TEST_ID.confirmPage,
    callsToActionTestIds: [TEST_ID.confirmSignIn],
  },
];

async function openRecordedContext(
  browser: Browser,
  testInfo: TestInfo,
  contextOptions: BrowserContextOptions = {},
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL: testInfo.project.use.baseURL ?? SPA_BASE_URL,
    recordVideo: { dir: testInfo.outputDir },
    ...contextOptions,
  });
  const page = await context.newPage();
  watchConsoleErrors(page, testInfo);
  return { context, page };
}

function countAuthorizationCodeGrantsKeycloakAccepted(page: Page): () => number {
  let observed = 0;
  page.on('response', (response: Response) => {
    if (
      response.url().includes(KC_TOKEN_ENDPOINT_PATH) &&
      (response.request().postData() ?? '').includes(AUTHORIZATION_CODE_GRANT_BODY) &&
      response.status() === 200
    ) {
      observed += 1;
    }
  });
  return () => observed;
}

function countRequestsTheSpaSentToKeycloak(page: Page): () => number {
  let observed = 0;
  page.on('request', (request: Request) => {
    if (new URL(request.url()).host === KC_HOST) observed += 1;
  });
  return () => observed;
}

async function reachLostPasswordThroughTheLandingChrome(page: Page): Promise<void> {
  await page.goto(LANDING_PATH);
  await expect(page.getByTestId(TEST_ID.landingTopbarSignIn)).toBeVisible();
  await expect(
    page.getByTestId(TEST_ID.landingTopbarLostPassword),
    'the landing chrome carries no link to /lostpassword, so the recovery page is reachable ' +
      'only by typing the URL, and the member never finds it',
  ).toBeVisible();

  await page.getByTestId(TEST_ID.landingTopbarLostPassword).click();
  await page.waitForURL((url) => url.host === SPA_HOST && url.pathname === LOSTPASSWORD_PATH, {
    timeout: REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS,
  });
}

async function startRecoveryFromLostPasswordPage(page: Page): Promise<void> {
  await page.goto(LOSTPASSWORD_PATH);
  await expect(page.getByTestId(TEST_ID.lostpasswordPage)).toBeVisible();
  await page.getByTestId(TEST_ID.lostpasswordStart).click();
  await page.waitForURL(KC_REALM_PAGE);
}

async function submitResetAddressOnKeycloak(page: Page, email: string): Promise<void> {
  await page.locator(KC_FORGOT_PASSWORD_LINK).click();
  await expect(page.locator(KC_RESET_ADDRESS_FORM)).toBeVisible();
  await page.locator(KC_RESET_ADDRESS_FIELD).fill(email);
  await page.locator(`${KC_RESET_ADDRESS_FORM} button[type="submit"]`).click();
}

async function setNewPasswordOnKeycloak(page: Page, password: string): Promise<void> {
  await page.locator(KC_NEW_PASSWORD_FIELD).fill(password);
  await page.locator(KC_NEW_PASSWORD_CONFIRM_FIELD).fill(password);
  await page.locator(`${KC_UPDATE_PASSWORD_FORM} button[type="submit"]`).click();
}

async function signInFromLanding(page: Page, email: string, password: string): Promise<void> {
  await page.goto(LANDING_PATH);
  await page.getByTestId(TEST_ID.landingTopbarSignIn).click();
  await page.waitForURL(KC_REALM_PAGE);
  await fillKcLogin(page, email, password);
}

async function waitForTheSpaToOwnTheUrlAgain(page: Page): Promise<void> {
  await page.waitForURL((url) => url.host === SPA_HOST, {
    timeout: REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS,
  });
}

async function endTheSessionTheResetCreatedSoTheNextSignInMustPresentAPassword(
  page: Page,
): Promise<void> {
  await page.goto(LOGOUT_PATH);
  await expect(page.getByTestId(TEST_ID.landingTopbarSignIn)).toBeVisible({
    timeout: REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS,
  });
}

async function followTheThemeBackLinkKeycloakShowsToTheAppUnderTest(
  page: Page,
  expectedPathAndQuery: string,
): Promise<void> {
  const backLink = page.locator(KC_THEME_BACK_LINK);
  await expect(backLink).toBeVisible();
  const href = (await backLink.getAttribute('href')) ?? '';
  const target = new URL(href);
  expect(
    target.host,
    `the Keycloak theme back link points at '${href}', which is not the AlpenFlight instance ` +
      `under test at '${SPA_HOST}' — the Keycloak image was built with a different ` +
      `ALPENFLIGHT_WEB_BASE_URL, so the member would land on another application`,
  ).toBe(SPA_HOST);
  expect(`${target.pathname}${target.search}`).toBe(expectedPathAndQuery);
  await backLink.click();
  await page.waitForURL((url) => url.host === SPA_HOST && url.pathname === target.pathname, {
    timeout: REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS,
  });
}

async function registerAndReadTheVerifyLink(page: Page, user: TestUser): Promise<string> {
  await page.goto(SIGNUP_PATH_THE_LANDING_MIGRATE_CTA_TARGETS);
  await expect(page.getByTestId(TEST_ID.signupPage)).toBeVisible();
  await page.getByTestId(TEST_ID.signupLocal).click();
  await page.waitForURL(KC_REALM_PAGE);
  await fillKcRegistration(page, user);
  const message = await waitForExactlyOneMessage(user.email, {
    timeoutMs: COLD_FIRST_SMTP_SEND_TIMEOUT_MS,
  });
  return extractVerifyLink(message);
}

async function deleteKeycloakUserByEmail(email: string): Promise<void> {
  const user = await findUserByEmail(email);
  if (!user) return;
  await deleteUser(user.id, user.email);
}

test.describe('account recovery — /lostpassword hands the member to Keycloak', () => {
  test('[happy] AC-1 — the landing page links to /lostpassword, which renders without a session and reaches Keycloak', async ({
    browser,
  }, testInfo) => {
    const { context, page } = await openRecordedContext(browser, testInfo);
    try {
      await reachLostPasswordThroughTheLandingChrome(page);
      expect(new URL(page.url()).pathname).toBe(LOSTPASSWORD_PATH);
      await expect(page.getByTestId(TEST_ID.lostpasswordPage)).toBeVisible();
      await expect(page.getByTestId(TEST_ID.lostpasswordStart)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/${AF_LOSTPASSWORD_SHOT_THE_GALLERY_PAIRS_AGAINST_THE_LEGACY_FORM}`,
        fullPage: true,
      });

      await page.getByTestId(TEST_ID.lostpasswordStart).click();
      await page.waitForURL(KC_REALM_PAGE);
      expect(new URL(page.url()).host).toBe(KC_HOST);
      await expect(
        page.locator(KC_FORGOT_PASSWORD_LINK),
        'the Keycloak page the handoff reaches carries no forgot-password link, so the member ' +
          'cannot continue the recovery there',
      ).toBeVisible();
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · the landing page shows a recovery link, the link opens ' +
          'the /lostpassword page without a session, and its start action moves the browser to ' +
          'the Keycloak realm, which shows the forgot-password link',
        acTag: 'happy',
      });
    }
  });
});

test.describe('account recovery — the reset chain over a real Keycloak and a real mail server', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(THE_WHOLE_RESET_CHAIN_INCLUDING_TWO_KEYCLOAK_LOGINS_TIMEOUT_MS);

  let completedResetChain:
    | { user: TestUser; userId: string; usedResetLink: string; passwordBeforeTheReset: string }
    | undefined;

  function requireCompletedResetChain(): {
    user: TestUser;
    userId: string;
    usedResetLink: string;
    passwordBeforeTheReset: string;
  } {
    if (!completedResetChain) {
      throw new Error(
        'the AC-2 reset case must complete before this case — this describe is serial, so a ' +
          'red AC-2 leaves the ephemeral user and the reset link unset',
      );
    }
    return completedResetChain;
  }

  test.afterAll(async () => {
    if (completedResetChain) {
      await deleteUser(completedResetChain.userId, completedResetChain.user.email);
      completedResetChain = undefined;
    }
    await purgeMailpit();
  });

  test('[happy] AC-2 — the mailed reset link sets a new password, and the new password signs the member in', async ({
    browser,
  }, testInfo) => {
    allowConsoleErrors(
      testInfo,
      A_BRAND_NEW_KEYCLOAK_USER_HAS_NO_ALPENFLIGHT_CLUB_SO_ITS_OWN_MEMBER_READS_ANSWER_403_OR_404,
    );
    const { context, page } = await openRecordedContext(browser, testInfo);
    const authorizationCodeGrantsKeycloakAccepted =
      countAuthorizationCodeGrantsKeycloakAccepted(page);
    const user = freshTestUser();
    const passwordBeforeTheReset = user.password;
    const userId = await createUser(user);
    try {
      await purgeMailpit();
      await startRecoveryFromLostPasswordPage(page);
      await submitResetAddressOnKeycloak(page, user.email);

      const message = await waitForExactlyOneMessage(user.email, {
        timeoutMs: COLD_FIRST_SMTP_SEND_TIMEOUT_MS,
      });
      const usedResetLink = extractVerifyLink(message);

      await page.goto(usedResetLink);
      await expect(
        page.locator(KC_UPDATE_PASSWORD_FORM),
        'the mailed link did not open the Keycloak update-password form, so nothing below ' +
          'could set a new password',
      ).toBeVisible();
      await setNewPasswordOnKeycloak(page, PASSWORD_THE_RESET_LINK_SETS);
      await waitForTheSpaToOwnTheUrlAgain(page);

      await endTheSessionTheResetCreatedSoTheNextSignInMustPresentAPassword(page);
      const grantsBeforeTheSignInWithTheNewPassword = authorizationCodeGrantsKeycloakAccepted();

      await signInFromLanding(page, user.email, PASSWORD_THE_RESET_LINK_SETS);
      await waitForTheSpaToOwnTheUrlAgain(page);
      await expect
        .poll(authorizationCodeGrantsKeycloakAccepted, {
          timeout: REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS,
          message:
            'Keycloak accepted no new authorization-code grant after the sign-in with the new ' +
            'password — the browser left the login page without a session, so the assertions ' +
            'below would pass on a signed-out page',
        })
        .toBeGreaterThan(grantsBeforeTheSignInWithTheNewPassword);
      await expect(page.getByTestId(TEST_ID.landingTopbarSignIn)).toHaveCount(0);

      completedResetChain = { user, userId, usedResetLink, passwordBeforeTheReset };
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · Keycloak mails the reset link to Mailpit, the link opens ' +
          'the update-password form, the new password replaces the old one, and the new ' +
          'password signs the member in again',
        acTag: 'happy',
      });
    }
  });

  test('[key-error] AC-3 — the password from before the reset no longer authenticates', async ({
    browser,
  }, testInfo) => {
    const { user, passwordBeforeTheReset } = requireCompletedResetChain();
    const { context, page } = await openRecordedContext(browser, testInfo);
    const authorizationCodeGrantsKeycloakAccepted =
      countAuthorizationCodeGrantsKeycloakAccepted(page);
    try {
      await signInFromLanding(page, user.email, passwordBeforeTheReset);
      await expect(page).toHaveURL(KC_AUTHENTICATE_PAGE);
      await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
      expect(
        authorizationCodeGrantsKeycloakAccepted(),
        'Keycloak accepted an authorization-code grant for the password from before the reset',
      ).toBe(0);
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · the password from before the reset fails on the ' +
          'Keycloak login form, the form shows the error, and Keycloak issues no token',
        acTag: 'key-error',
      });
    }
  });

  test('[key-error] AC-4 — a second use of the reset link fails and returns the member to /lostpassword', async ({
    browser,
  }, testInfo) => {
    allowConsoleErrors(
      testInfo,
      KEYCLOAK_SERVES_THE_SPENT_LINK_ERROR_PAGE_WITH_THE_STATUS_THIS_CASE_ASSERTS,
    );
    const { usedResetLink } = requireCompletedResetChain();
    const { context, page } = await openRecordedContext(browser, testInfo);
    const authorizationCodeGrantsKeycloakAccepted =
      countAuthorizationCodeGrantsKeycloakAccepted(page);
    try {
      const answerToTheSpentResetLink = await page.goto(usedResetLink);
      expect(
        answerToTheSpentResetLink?.status(),
        'Keycloak served the spent reset link with a success status, so the link still works',
      ).toBe(HTTP_STATUS_KEYCLOAK_SERVES_THE_SPENT_ACTION_LINK_WITH);
      await expect(
        page.locator(KC_UPDATE_PASSWORD_FORM),
        'the spent reset link opened the update-password form a second time',
      ).toHaveCount(0);
      expect(
        authorizationCodeGrantsKeycloakAccepted(),
        'Keycloak accepted an authorization-code grant from the spent reset link',
      ).toBe(0);

      await followTheThemeBackLinkKeycloakShowsToTheAppUnderTest(page, LOSTPASSWORD_PATH);
      await expect(page.getByTestId(TEST_ID.lostpasswordPage)).toBeVisible();
      await expect(page.getByTestId(TEST_ID.lostpasswordStart)).toBeVisible();
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · the second use of the reset link sets no password and ' +
          'gets no token, and the Keycloak page returns the member to /lostpassword, where the ' +
          'recovery can start again',
        acTag: 'key-error',
      });
    }
  });
});

test.describe('email confirmation — the verify link ends on /confirm', () => {
  test.setTimeout(THE_WHOLE_RESET_CHAIN_INCLUDING_TWO_KEYCLOAK_LOGINS_TIMEOUT_MS);

  test('[happy] AC-5 — a verify-email link opened without a session shows the verified state and a sign-in action', async ({
    page,
    browser,
  }, testInfo) => {
    const user = freshTestUser();
    let sessionLessContext: BrowserContext | undefined;
    let sessionLessPage: Page | undefined;
    try {
      await purgeMailpit();
      const verifyLink = await registerAndReadTheVerifyLink(page, user);

      const sessionLess = await openRecordedContext(browser, testInfo);
      sessionLessContext = sessionLess.context;
      sessionLessPage = sessionLess.page;

      await sessionLessPage.goto(verifyLink);
      await expect(
        sessionLessPage.locator(KC_CONFIRM_VALIDITY_INTERSTITIAL_PROCEED_LINK),
        'Keycloak 26 shows a confirm-validity page before it acts on an emailed link opened ' +
          'without a session — that page is missing, so the flow below is not the session-less one',
      ).toBeVisible();
      await sessionLessPage.locator(KC_CONFIRM_VALIDITY_INTERSTITIAL_PROCEED_LINK).click();

      await followTheThemeBackLinkKeycloakShowsToTheAppUnderTest(
        sessionLessPage,
        CONFIRM_PATH_AND_QUERY_THE_VERIFIED_BACK_LINK_TARGETS,
      );
      await expect(sessionLessPage.getByTestId(TEST_ID.confirmPage)).toBeVisible();
      await sessionLessPage.screenshot({
        path: `${testInfo.outputDir}/${AF_CONFIRM_SHOT_THE_GALLERY_PAIRS_AGAINST_THE_LEGACY_CONFIRM_FORM}`,
        fullPage: true,
      });

      await expect(sessionLessPage.getByTestId(TEST_ID.confirmOutcomeVerified)).toBeVisible();
      await expect(sessionLessPage.getByTestId(TEST_ID.confirmSignIn)).toBeVisible();

      const userKeycloakHoldsAfterTheAction = await findUserByEmail(user.email);
      expect(
        userKeycloakHoldsAfterTheAction?.emailVerified,
        'Keycloak still holds the address as unverified, so the page reports an outcome the ' +
          'realm never reached',
      ).toBe(true);
    } finally {
      await sessionLessContext?.close();
      if (sessionLessPage) {
        await proofVideo(sessionLessPage, testInfo, {
          journey: 'J-19',
          caption:
            'J-19 · email confirmation · a verify-email link opens in a browser without a ' +
            'session, the member confirms the link, and /confirm shows the verified state with ' +
            'a sign-in action',
          acTag: 'happy',
        });
      }
      await purgeMailpit();
      await deleteKeycloakUserByEmail(user.email);
    }
  });
});

test.describe('account recovery — both routes stay public', () => {
  for (const route of ACCOUNT_RECOVERY_ROUTES) {
    test(`[edge] AC-7 — ${route.path} renders without a session and never enters Keycloak`, async ({
      page,
    }) => {
      const requestsToKeycloak = countRequestsTheSpaSentToKeycloak(page);

      await page.goto(route.path);
      await page.waitForLoadState('networkidle');

      const landedOn = new URL(page.url());
      expect(landedOn.host).toBe(SPA_HOST);
      expect(landedOn.pathname).not.toContain('/realms/');
      await expect(page.getByTestId(route.rendersTestId)).toBeVisible();
      expect(
        requestsToKeycloak(),
        `${route.path} reached Keycloak without the member asking for it`,
      ).toBe(0);
    });
  }
});

test.describe('account recovery — both routes fit a 360 x 640 portrait screen', () => {
  for (const route of ACCOUNT_RECOVERY_ROUTES) {
    test(`[edge] AC-8 — ${route.path} fits the portrait viewport and each action is at least 44 x 44 pixels`, async ({
      browser,
    }, testInfo) => {
      const { context, page } = await openRecordedContext(browser, testInfo, {
        viewport: MOBILE_PORTRAIT_VIEWPORT,
      });
      try {
        await page.goto(route.path);
        await expect(page.getByTestId(route.rendersTestId)).toBeVisible();

        const fitsWithoutHorizontalScroll = await page.evaluate(
          () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
        );
        expect(fitsWithoutHorizontalScroll).toBe(true);

        for (const callToActionTestId of route.callsToActionTestIds) {
          const box = await page.getByTestId(callToActionTestId).boundingBox();
          expect(box, `${callToActionTestId} has no bounding box on ${route.path}`).not.toBeNull();
          expect(box?.width ?? 0).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
          expect(box?.height ?? 0).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
        }
      } finally {
        await context.close();
        await proofVideo(page, testInfo, {
          journey: 'J-19',
          caption:
            `J-19 · mobile · the ${route.path} page fits a 360 x 640 portrait screen, and ` +
            'each action is at least 44 x 44 pixels',
          acTag: 'edge',
        });
      }
    });
  }
});
