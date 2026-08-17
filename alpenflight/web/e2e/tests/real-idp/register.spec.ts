import { type Page, type TestInfo } from '@playwright/test';
import {
  allowConsoleErrors,
  consoleErrorAllowanceForStatusesOnEndpoint,
  test,
  expect,
} from '../_helpers/console-guard';

import { findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import {
  KC_ERROR_SELECTOR,
  fillKcRegistration,
  fillKcRegistrationWithPassword,
} from './_helpers/kc-form';
import {
  waitForExactlyOneMessage,
  extractVerifyLink,
  purgeMailpit,
} from './_helpers/mailpit-client';
import { E2E_CANNED_PASSWORD, E2E_OCCUPIED_EMAIL, freshTestUser } from './_helpers/test-user';

const VERIFY_MAIL_DELIVERY_BUDGET_LEAVING_ROOM_INSIDE_THE_REAL_IDP_TEST_TIMEOUT_MS = 20_000;

const SIGNUP_PATH_THE_LANDING_MIGRATE_CTA_TARGETS = '/signup?intent=migrate';

const HANDSHAKE_CURRENT_ENDPOINT = '/api/v1/migrations/handshake/current';

const HANDSHAKE_ENDPOINT = '/api/v1/migrations/handshake';

const GET_HANDSHAKE_CURRENT_404_IS_BY_DESIGN_FOR_A_FIRST_TIME_REGISTRANT =
  consoleErrorAllowanceForStatusesOnEndpoint([404], HANDSHAKE_CURRENT_ENDPOINT);

const POST_HANDSHAKE_403_IS_A_KNOWN_PRODUCT_DEFECT_NOT_NORMAL_BEHAVIOUR =
  consoleErrorAllowanceForStatusesOnEndpoint([403], HANDSHAKE_ENDPOINT);

const KNOWN_PRODUCT_DEFECT_THE_PAGE_RENDERS_BUT_THE_HANDSHAKE_FAILS = {
  type: 'known-product-defect',
  description:
    '[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT] POST /api/v1/migrations/handshake answers 403 ' +
    'for a club-less registrant. The page renders. The handshake does not complete. ' +
    'The rider sits in docs/modernization/stories/_BOYSCOUT.md and belongs to J-21.',
};

function declareTheKnownHandshakeDefectSoTheReportShowsItIsToleratedNotNormal(
  testInfo: TestInfo,
): void {
  allowConsoleErrors(
    testInfo,
    GET_HANDSHAKE_CURRENT_404_IS_BY_DESIGN_FOR_A_FIRST_TIME_REGISTRANT,
    POST_HANDSHAKE_403_IS_A_KNOWN_PRODUCT_DEFECT_NOT_NORMAL_BEHAVIOUR,
  );
  testInfo.annotations.push(KNOWN_PRODUCT_DEFECT_THE_PAGE_RENDERS_BUT_THE_HANDSHAKE_FAILS);
}

async function startRegistrationThroughTheMigrateIntentCta(page: Page): Promise<void> {
  await page.goto(SIGNUP_PATH_THE_LANDING_MIGRATE_CTA_TARGETS);
  await expect(page.getByTestId('signup-page')).toBeVisible();
  await page.getByTestId('signup-local').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
}

test.describe('register — real-idp', () => {
  const cleanupEmails: string[] = [];

  test.afterEach(async () => {
    const targets = cleanupEmails.splice(0);
    for (const email of targets) {
      const user = await findUserByEmail(email);
      if (!user) continue;
      await deleteUser(user.id, user.email);
    }
    await purgeMailpit();
  });

  test('happy path — register, verify via Mailpit, land on /migrate/start', async ({
    page,
  }, testInfo) => {
    declareTheKnownHandshakeDefectSoTheReportShowsItIsToleratedNotNormal(testInfo);
    const user = freshTestUser();

    await startRegistrationThroughTheMigrateIntentCta(page);
    await fillKcRegistration(page, user);
    cleanupEmails.push(user.email);

    const message = await waitForExactlyOneMessage(user.email, {
      timeoutMs: VERIFY_MAIL_DELIVERY_BUDGET_LEAVING_ROOM_INSIDE_THE_REAL_IDP_TEST_TIMEOUT_MS,
    });
    const verifyHref = extractVerifyLink(message);
    await page.goto(verifyHref);

    await expect(page).toHaveURL(/\/migrate\/start$/);
    await expect(page.getByTestId('migrate-handshake')).toBeVisible();
  });

  test('password-policy reject — short password stays on KC form', async ({ page }) => {
    const user = freshTestUser();
    cleanupEmails.push(user.email);

    await startRegistrationThroughTheMigrateIntentCta(page);
    await fillKcRegistrationWithPassword(page, user, 'short');

    await expect(page).toHaveURL(/\/realms\/alpenflight\/login-actions\/registration/);
    await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
  });

  test('email-in-use reject — registering occupied address triggers KC error', async ({ page }) => {
    await startRegistrationThroughTheMigrateIntentCta(page);
    await fillKcRegistration(page, {
      email: E2E_OCCUPIED_EMAIL,
      password: E2E_CANNED_PASSWORD,
      firstName: 'E2e',
      lastName: 'Duplicate',
    });

    await expect(page).toHaveURL(/\/realms\/alpenflight\/login-actions\/registration/);
    await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
  });
});
