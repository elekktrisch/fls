import {
  type Browser,
  type BrowserContext,
  type BrowserContextOptions,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { expect, test, watchConsoleErrors } from '../_helpers/console-guard';

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

const LOSTPASSWORD_PATH = '/lostpassword';
const CONFIRM_PATH = '/confirm';
const LANDING_PATH = '/';
const SIGNUP_PATH = '/signup';

const TEST_ID = {
  lostpasswordPage: 'lostpassword-page',
  lostpasswordStart: 'lostpassword-start',
  confirmPage: 'confirm-page',
  confirmOutcomeVerified: 'confirm-outcome-verified',
  confirmSignIn: 'confirm-sign-in',
  landingTopbarSignIn: 'landing-topbar-sign-in',
  signupPage: 'signup-page',
  signupLocal: 'signup-local',
} as const;

const PASSWORD_THE_RESET_LINK_SETS = 'E2eReset-2026!';

const MOBILE_PORTRAIT_VIEWPORT = { width: 360, height: 640 } as const;
const MINIMUM_TOUCH_TARGET_EDGE_PX = 44;

const COLD_FIRST_SMTP_SEND_TIMEOUT_MS = 45_000;
const REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS = 30_000;
const THE_WHOLE_RESET_CHAIN_INCLUDING_TWO_KEYCLOAK_LOGINS_TIMEOUT_MS = 120_000;

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

async function startRecoveryFromLostPasswordPage(page: Page): Promise<void> {
  await page.goto(LOSTPASSWORD_PATH);
  await expect(page.getByTestId(TEST_ID.lostpasswordPage)).toBeVisible();
  await page.getByTestId(TEST_ID.lostpasswordStart).click();
  await page.waitForURL(KC_REALM_PAGE);
}

async function submitResetAddressOnKeycloak(page: Page, email: string): Promise<void> {
  await page.locator(KC_FORGOT_PASSWORD_LINK).click();
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
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), {
    timeout: REAL_KEYCLOAK_ROUND_TRIP_TIMEOUT_MS,
  });
}

async function registerAndReadTheVerifyLink(page: Page, user: TestUser): Promise<string> {
  await page.goto(SIGNUP_PATH);
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
  test.fixme('[happy] AC-1 — /lostpassword renders without a session and its start action reaches Keycloak', async ({
    browser,
  }, testInfo) => {
    const { context, page } = await openRecordedContext(browser, testInfo);
    try {
      await startRecoveryFromLostPasswordPage(page);
      expect(new URL(page.url()).host).toBe(KC_HOST);
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · the /lostpassword page opens without a session, and its ' +
          'start action moves the browser to the Keycloak realm',
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

  test.fixme('[happy] AC-2 — the mailed reset link sets a new password, and the new password signs the member in', async ({
    browser,
  }, testInfo) => {
    const { context, page } = await openRecordedContext(browser, testInfo);
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
      await setNewPasswordOnKeycloak(page, PASSWORD_THE_RESET_LINK_SETS);
      await waitForTheSpaToOwnTheUrlAgain(page);

      await signInFromLanding(page, user.email, PASSWORD_THE_RESET_LINK_SETS);
      await waitForTheSpaToOwnTheUrlAgain(page);
      await expect(page.getByTestId(TEST_ID.landingTopbarSignIn)).toHaveCount(0);

      completedResetChain = { user, userId, usedResetLink, passwordBeforeTheReset };
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · Keycloak mails the reset link to Mailpit, the link sets ' +
          'a new password, and the new password signs the member in',
        acTag: 'happy',
      });
    }
  });

  test.fixme('[key-error] AC-3 — the password from before the reset no longer authenticates', async ({
    browser,
  }, testInfo) => {
    const { user, passwordBeforeTheReset } = requireCompletedResetChain();
    const { context, page } = await openRecordedContext(browser, testInfo);
    try {
      await signInFromLanding(page, user.email, passwordBeforeTheReset);
      await expect(page).toHaveURL(KC_AUTHENTICATE_PAGE);
      await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · the password from before the reset fails on the ' +
          'Keycloak login form, and the form shows the error',
        acTag: 'key-error',
      });
    }
  });

  test.fixme('[key-error] AC-4 — a second use of the reset link fails and returns the member to /lostpassword', async ({
    browser,
  }, testInfo) => {
    const { usedResetLink } = requireCompletedResetChain();
    const { context, page } = await openRecordedContext(browser, testInfo);
    try {
      await page.goto(usedResetLink);
      await page.locator(KC_THEME_BACK_LINK).click();
      await page.waitForURL((url) => url.pathname === LOSTPASSWORD_PATH);
      await expect(page.getByTestId(TEST_ID.lostpasswordPage)).toBeVisible();
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-19',
        caption:
          'J-19 · password recovery · the second use of the reset link fails, and the ' +
          'Keycloak page returns the member to /lostpassword',
        acTag: 'key-error',
      });
    }
  });
});

test.describe('email confirmation — the verify link ends on /confirm', () => {
  test.setTimeout(THE_WHOLE_RESET_CHAIN_INCLUDING_TWO_KEYCLOAK_LOGINS_TIMEOUT_MS);

  test.fixme('[happy] AC-5 — a verify-email link opened without a session shows the verified state and a sign-in action', async ({
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
      await sessionLessPage.locator(KC_THEME_BACK_LINK).click();
      await expect(sessionLessPage).toHaveURL(new RegExp(`${CONFIRM_PATH}(\\?|$)`));
      await expect(sessionLessPage.getByTestId(TEST_ID.confirmOutcomeVerified)).toBeVisible();
      await expect(sessionLessPage.getByTestId(TEST_ID.confirmSignIn)).toBeVisible();
    } finally {
      await sessionLessContext?.close();
      if (sessionLessPage) {
        await proofVideo(sessionLessPage, testInfo, {
          journey: 'J-19',
          caption:
            'J-19 · email confirmation · a verify-email link opens in a browser without a ' +
            'session, and /confirm shows the verified state with a sign-in action',
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
    test.fixme(`[edge] AC-7 — ${route.path} renders without a session and never enters Keycloak`, async ({
      page,
    }) => {
      await page.goto(route.path);
      await page.waitForLoadState('networkidle');

      const landedOn = new URL(page.url());
      expect(landedOn.host).not.toBe(KC_HOST);
      expect(landedOn.pathname).not.toContain('/realms/');
      await expect(page.getByTestId(route.rendersTestId)).toBeVisible();
    });
  }
});

test.describe('account recovery — both routes fit a 360 x 640 portrait screen', () => {
  for (const route of ACCOUNT_RECOVERY_ROUTES) {
    test.fixme(`[edge] AC-8 — ${route.path} fits the portrait viewport and each action is at least 44 x 44 pixels`, async ({
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
