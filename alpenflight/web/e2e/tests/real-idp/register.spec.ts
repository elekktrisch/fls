import { test, expect, type Page } from '@playwright/test';

import { findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import {
  KC_ERROR_SELECTOR,
  fillKcRegistration,
  fillKcRegistrationWithPassword,
} from './_helpers/kc-form';
import { waitForMessage, extractVerifyLink, purgeMailpit } from './_helpers/mailpit-client';
import { E2E_CANNED_PASSWORD, E2E_OCCUPIED_EMAIL, freshTestUser } from './_helpers/test-user';

/**
 * S-174 — register flows against live Keycloak + Mailpit.
 *
 * Coverage:
 *   - Happy path: /signup → KC reg form → submit → verify-mail link →
 *     /migrate/start.
 *   - Password-policy reject: short password → KC inline error.
 *   - Email-in-use reject: register `e2e-occupied@example.com` (provisioned
 *     by setup.ts) → KC "already exists" inline error.
 *
 * Per-test afterEach deletes the registered user; globalTeardown sweeps
 * any leaks. Mailpit inbox is purged in afterEach as a courtesy.
 */

async function startRegistration(page: Page): Promise<void> {
  // SPA → KC: /signup CTA invokes oidcSecurity.authorize({ prompt: 'create' }).
  await page.goto('/signup');
  await expect(page.getByTestId('signup-page')).toBeVisible();
  await page.getByTestId('signup-local').click();
  // Wait for KC realm host before form fields exist.
  await page.waitForURL(/\/realms\/alpenflight\//);
}

test.describe('register — real-idp', () => {
  const cleanupEmails: string[] = [];

  test.afterEach(async () => {
    // Snapshot + clear so a later test's afterEach doesn't redo deletes.
    const targets = cleanupEmails.splice(0);
    for (const email of targets) {
      const user = await findUserByEmail(email);
      if (!user) continue;
      await deleteUser(user.id, user.email);
    }
    await purgeMailpit();
  });

  test('happy path — register, verify via Mailpit, land on /migrate/start', async ({ page }) => {
    const user = freshTestUser();

    await startRegistration(page);
    await fillKcRegistration(page, user);
    // Push onto cleanup only after submit — if KC fails inline before
    // persistence there's nothing to delete (cheap optimization for the
    // happy-path which always persists).
    cleanupEmails.push(user.email);

    // KC drops the user on its post-registration "verify-email required"
    // page. The exact URL varies (`/login-actions/required-action?...`);
    // we don't assert on it. The verify-link we click is the contract.
    //
    // Generous timeout: this is KC's FIRST SMTP send of the run — a cold
    // FreeMarker template compile + the SMTP handshake to the `mailpit`
    // service (cross-project container DNS on `alpenflight_shared`) add
    // first-hit latency the steady-state 15s default does not budget for on
    // a loaded CI runner. The verify-mail path runs ONLY in the nightly
    // (ci.yml's per-push proof spec never registers), so this cold path is
    // unbuffered by any warm-up. 45s stays well inside the per-test budget.
    const message = await waitForMessage(user.email, { timeoutMs: 45_000 });
    const verifyHref = extractVerifyLink(message);
    await page.goto(verifyHref);

    // SPA picks the user up after KC redirects through the OIDC callback
    // and routes to `/migrate/start` (S-134 contract).
    await expect(page).toHaveURL(/\/migrate\/start$/);
    await expect(page.getByTestId('migrate-start')).toBeVisible();
  });

  test('password-policy reject — short password stays on KC form', async ({ page }) => {
    const user = freshTestUser();
    // KC validates the policy before persistence, so cleanup-on-fail is
    // a no-op; we push anyway in case a future KC build flips that
    // ordering. Cheaper than a leaked test user.
    cleanupEmails.push(user.email);

    await startRegistration(page);
    await fillKcRegistrationWithPassword(page, user, 'short');

    // KC re-renders the same registration page with an inline error.
    await expect(page).toHaveURL(/\/realms\/alpenflight\/login-actions\/registration/);
    await expect(page.locator(KC_ERROR_SELECTOR).first()).toBeVisible();
  });

  test('email-in-use reject — registering occupied address triggers KC error', async ({ page }) => {
    // e2e-occupied is provisioned idempotently by setup.ts and never
    // torn down — DO NOT add it to cleanupEmails.
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
