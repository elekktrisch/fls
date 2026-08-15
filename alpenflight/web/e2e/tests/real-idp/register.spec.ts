import { type Page } from '@playwright/test';
import { test, expect } from '../_helpers/console-guard';

import { findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import {
  KC_ERROR_SELECTOR,
  fillKcRegistration,
  fillKcRegistrationWithPassword,
} from './_helpers/kc-form';
import { waitForMessage, extractVerifyLink, purgeMailpit } from './_helpers/mailpit-client';
import { E2E_CANNED_PASSWORD, E2E_OCCUPIED_EMAIL, freshTestUser } from './_helpers/test-user';

const COLD_FIRST_SMTP_SEND_TIMEOUT_MS = 45_000;

async function startRegistration(page: Page): Promise<void> {
  await page.goto('/signup');
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

  test('@quarantine-kc26 happy path — register, verify via Mailpit, land on /migrate/start', async ({
    page,
  }) => {
    const user = freshTestUser();

    await startRegistration(page);
    await fillKcRegistration(page, user);
    cleanupEmails.push(user.email);

    const message = await waitForMessage(user.email, {
      timeoutMs: COLD_FIRST_SMTP_SEND_TIMEOUT_MS,
    });
    const verifyHref = extractVerifyLink(message);
    await page.goto(verifyHref);

    await expect(page).toHaveURL(/\/migrate\/start$/);
    await expect(page.getByTestId('migrate-start')).toBeVisible();
  });

  test('password-policy reject — short password stays on KC form', async ({ page }) => {
    const user = freshTestUser();
    cleanupEmails.push(user.email);

    await startRegistration(page);
    await fillKcRegistrationWithPassword(page, user, 'short');

    await expect(page).toHaveURL(/\/realms\/alpenflight\/login-actions\/registration/);
    await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
  });

  test('email-in-use reject — registering occupied address triggers KC error', async ({ page }) => {
    await startRegistration(page);
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
