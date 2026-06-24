import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { fillKcLogin } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';
import { createUserWithAttributes, findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import { purgeMailpit } from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';

/**
 * Admin join-request approval (`/join-requests`) against the REAL chain (live
 * Keycloak auth + real Spring backend + real Postgres + Mailpit). Greenfield:
 * J-12b is the admin SCREEN over J-12a's already-shipped JoinRequest backend
 * (list / approve / deny + the `join-request.status-changed` SSE), so the proof
 * is the real-idp admin-approval lifecycle, not a legacy pairing.
 *
 * STUB STATE (T-01): this commits the screen SHAPE — the route, the `data-testid`
 * contract the list / approve-modal / deny-modal / empty-state / nav-badge will
 * expose, and the flow order. The lifecycle cases are `test.fixme` so the file
 * PARSES + COLLECTS without redding the per-push gate (the screen does not exist
 * yet); T-10 drops the fixmes and thickens the assertions once T-03..T-06 land
 * the screen. The `parity_test:` derive treats a fixme-only spec as not-yet-
 * thickened → the gallery renders the J-12b section as pending until T-10.
 *
 * The selectors below are the binding contract for the screen tasks — T-03..T-06
 * MUST expose exactly these testids so T-10's thicken needs no selector churn.
 */

/** seed-club-1's clean-seed CLUB_ADMINISTRATOR — the approving admin (V29). */
const ADMIN_USER = 'clubadmin4@example.com';
const ADMIN_PASSWORD = 'clubadmin4-dev-2026!';

/** seed-club-1's clean-seed low-privilege PILOT — the non-admin reach guard (V8). */
const PILOT_USER = 'pilot1@example.com';
const PILOT_PASSWORD = 'pilot1-dev-2026!';

/** The fixed seed-club-1 join code a fresh pilot submits to create a pending row. */
const SEED_JOIN_CODE = 'SEEDCLUB';

const JOIN_REQUESTS_PATH = '/join-requests';

/** The screen-shape contract — the testids T-03..T-06 expose, T-10 asserts on. */
const TESTIDS = {
  page: 'join-requests-page',
  list: 'join-requests-list',
  row: 'join-request-row',
  rowFriendlyName: 'join-request-friendly-name',
  rowEmail: 'join-request-email',
  rowSubmittedAt: 'join-request-submitted-at',
  rowNote: 'join-request-note',
  rowApprove: 'join-request-approve',
  rowDeny: 'join-request-deny',
  emptyState: 'join-requests-empty',
  emptyStateClubLink: 'join-requests-empty-club-link',
  approveModal: 'approve-modal',
  approveRoleCheckbox: 'approve-role-checkbox',
  approvePersonPicker: 'approve-person-picker',
  approveRequestInfo: 'approve-request-info',
  approveSubmit: 'approve-submit',
  approveError: 'approve-error',
  denyModal: 'deny-modal',
  denyReason: 'deny-reason',
  denyReasonCounter: 'deny-reason-counter',
  denySubmit: 'deny-submit',
  successToast: 'join-request-success-toast',
  navBadge: 'nav-join-requests-badge',
} as const;

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/** Sign a seeded principal in through the real KC redirect-login (the operator entry). */
async function loginAs(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, username, password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
}

/**
 * Provision a fresh, email-verified, TENANT-LESS pilot and file a pending join
 * request against seed-club-1, so the admin's `/join-requests` list has an
 * own-club row to triage. The pilot's KC user is cleaned up in afterEach.
 */
async function filePendingRequest(
  browser: Browser,
  baseURL: string,
  user: TestUser,
): Promise<void> {
  await createUserWithAttributes(user, {});
  const ctx = await browser.newContext({ baseURL });
  const page = await ctx.newPage();
  try {
    await loginAs(page, user.email, user.password);
    await page.waitForURL(/\/join$/, { timeout: 30_000 });
    await page.getByTestId('join-code-input').locator('input').fill(SEED_JOIN_CODE);
    await page.getByTestId('join-submit').click();
    await page.waitForURL(/\/join\/pending$/, { timeout: 30_000 });
  } finally {
    await ctx.close();
  }
}

test.describe('Admin join-request approval — real chain (real-idp)', () => {
  const cleanupEmails: string[] = [];

  test.afterEach(async () => {
    const targets = cleanupEmails.splice(0);
    for (const email of targets) {
      const kcUser = await findUserByEmail(email);
      if (!kcUser) continue;
      await deleteUser(kcUser.id, kcUser.email);
    }
    await purgeMailpit();
  });

  test.fixme('[happy] admin lists own-club pending → approve via the modal → row drops + success toast + badge decrements', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const pilot = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      cleanupEmails.push(pilot.email);
      await filePendingRequest(browser, baseURL, pilot);

      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto(JOIN_REQUESTS_PATH);
      await expect(page.getByTestId(TESTIDS.page)).toBeVisible();

      // The own-club pending row carries the pilot's friendlyName + email +
      // submitted-at + note + Approve/Deny affordances; the nav badge reflects
      // the count.
      const row = page.getByTestId(TESTIDS.row).filter({ hasText: pilot.email });
      await expect(row).toBeVisible();
      await expect(row.getByTestId(TESTIDS.rowEmail)).toHaveText(pilot.email);
      await expect(row.getByTestId(TESTIDS.rowFriendlyName)).toBeVisible();
      await expect(row.getByTestId(TESTIDS.rowSubmittedAt)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.navBadge)).toBeVisible();

      // Approve via the modal: role checkboxes (gated by RoleAssignmentPolicy),
      // an optional Person picker, read-only request info.
      await row.getByTestId(TESTIDS.rowApprove).click();
      await expect(page.getByTestId(TESTIDS.approveModal)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approveRequestInfo)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approvePersonPicker)).toBeVisible();
      await page.getByTestId(TESTIDS.approveRoleCheckbox).first().check();
      await page.getByTestId(TESTIDS.approveSubmit).click();

      // The row drops, a success toast shows, and the badge decrements live.
      await expect(row).toHaveCount(0, { timeout: 30_000 });
      await expect(page.getByTestId(TESTIDS.successToast)).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-12b',
        caption:
          'J-12b · admin approve · a CLUB_ADMINISTRATOR opens /join-requests, sees the own-club ' +
          'pending request, approves it through the modal (roles + optional Person), and the row ' +
          'drops with a success toast while the nav pending-count badge decrements live',
        acTag: 'happy',
      });
    }
  });

  test.fixme('[edge] deny via the modal (reason ≤500 + char counter) → row drops + the badge decrements', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const pilot = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const reason = 'No spare slots this season — try again in the autumn intake.';
    try {
      cleanupEmails.push(pilot.email);
      await filePendingRequest(browser, baseURL, pilot);

      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto(JOIN_REQUESTS_PATH);
      const row = page.getByTestId(TESTIDS.row).filter({ hasText: pilot.email });
      await expect(row).toBeVisible();

      await row.getByTestId(TESTIDS.rowDeny).click();
      await expect(page.getByTestId(TESTIDS.denyModal)).toBeVisible();
      await page.getByTestId(TESTIDS.denyReason).fill(reason);
      await expect(page.getByTestId(TESTIDS.denyReasonCounter)).toBeVisible();
      await page.getByTestId(TESTIDS.denySubmit).click();

      await expect(row).toHaveCount(0, { timeout: 30_000 });
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[edge] empty state — no pending requests shows the empty state + a Club-edit link', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto(JOIN_REQUESTS_PATH);
      await expect(page.getByTestId(TESTIDS.page)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.emptyState)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.emptyStateClubLink)).toBeVisible();
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[key-error] 409 surfaced on the screen — a picked Person from another tenant is rejected', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const pilot = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      cleanupEmails.push(pilot.email);
      await filePendingRequest(browser, baseURL, pilot);

      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto(JOIN_REQUESTS_PATH);
      const row = page.getByTestId(TESTIDS.row).filter({ hasText: pilot.email });
      await row.getByTestId(TESTIDS.rowApprove).click();
      await expect(page.getByTestId(TESTIDS.approveModal)).toBeVisible();
      // T-10 wires the cross-tenant Person pick → 409 surfaced inline.
      await expect(page.getByTestId(TESTIDS.approveError)).toBeVisible();
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[edge] non-admin cannot reach /join-requests (403 / redirect)', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // A real low-privilege PILOT (not a mock admin-everything principal) must
      // not land on the admin screen — the route guard redirects away.
      await loginAs(page, PILOT_USER, PILOT_PASSWORD);
      await page.goto(JOIN_REQUESTS_PATH);
      await expect(page).not.toHaveURL(new RegExp(`${JOIN_REQUESTS_PATH}$`));
      await expect(page.getByTestId(TESTIDS.page)).toHaveCount(0);
    } finally {
      await ctx.close();
    }
  });
});
