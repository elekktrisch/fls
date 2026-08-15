import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { CLUB_SETTINGS_NAV_TESTID, enterViaNav, openMasterdataGroup } from '../_helpers/nav';
import { fillKcLogin } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';
import { createUserWithAttributes, findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import { waitForMessageWithSubject, purgeMailpit } from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';


const ADMIN_USER = 'clubadmin4@example.com';
const ADMIN_PASSWORD = 'clubadmin4-dev-2026!';

const PILOT_USER = 'pilot1@example.com';
const PILOT_PASSWORD = 'pilot1-dev-2026!';

const SEED_JOIN_CODE = 'SEEDCLUB';

const SUBJECT_PILOT_DENIED = 'Beitrittsanfrage abgelehnt';

const JOIN_REQUESTS_PATH = '/join-requests';

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

interface PendingRow {
  readonly id: string;
  readonly email: string;
  readonly clubId: string;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function loginAs(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, username, password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
}

async function filePendingRequest(
  browser: Browser,
  baseURL: string,
  user: TestUser,
): Promise<string> {
  const friendlyName = `${user.firstName} ${user.lastName}`;
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
  return friendlyName;
}

async function captureAdminBearer(browser: Browser, baseURL: string): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerPromise = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

async function listPending(api: APIRequestContext, adminBearer: string): Promise<PendingRow[]> {
  const res = await api.get('/api/v1/join-requests?status=pending', {
    headers: { authorization: adminBearer },
  });
  expect(res.status(), await res.text()).toBe(200);
  return (await res.json()) as PendingRow[];
}

async function findPendingFor(
  api: APIRequestContext,
  adminBearer: string,
  pilotEmail: string,
): Promise<PendingRow> {
  const rows = await listPending(api, adminBearer);
  const mine = rows.find((r) => r.email === pilotEmail);
  expect(mine, `no pending request for ${pilotEmail} in the admin's club`).toBeDefined();
  return mine!;
}

async function drainPending(api: APIRequestContext, adminBearer: string): Promise<void> {
  for (const row of await listPending(api, adminBearer)) {
    const res = await api.post(`/api/v1/join-requests/${row.id}/deny`, {
      headers: { authorization: adminBearer, 'content-type': 'application/json' },
      data: { reason: 'Draining the queue for the empty-state proof.' },
    });
    expect(res.status(), await res.text()).toBe(200);
  }
}

async function navBadgeCount(page: Page): Promise<number> {
  const badge = page.getByTestId(TESTIDS.navBadge).first();
  if ((await badge.count()) === 0) {
    return 0;
  }
  const text = (await badge.textContent())?.trim() ?? '0';
  return Number.parseInt(text, 10) || 0;
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

  test('[happy] admin lists own-club pending → approve via the modal → row drops + success toast + badge decrements + pilot admitted', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const pilot = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      cleanupEmails.push(pilot.email);
      const friendlyName = await filePendingRequest(browser, baseURL, pilot);

      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto('/start');
      await expect(page).toHaveURL('/start');
      await enterViaNav(page, JOIN_REQUESTS_PATH);
      await expect(page).toHaveURL(JOIN_REQUESTS_PATH);
      await expect(page.getByTestId(TESTIDS.page)).toBeVisible();

      const row = page.getByTestId(TESTIDS.row).filter({ hasText: pilot.email });
      await expect(row).toBeVisible();
      await expect(row.getByTestId(TESTIDS.rowEmail)).toHaveText(pilot.email);
      await expect(row.getByTestId(TESTIDS.rowFriendlyName)).toHaveText(friendlyName);
      await expect(row.getByTestId(TESTIDS.rowSubmittedAt)).toBeVisible();
      const countBefore = await navBadgeCount(page);
      expect(countBefore).toBeGreaterThanOrEqual(1);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-join-requests-list.png`,
        fullPage: true,
      });

      await row.getByTestId(TESTIDS.rowApprove).click();
      await expect(page.getByTestId(TESTIDS.approveModal)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approveRequestInfo)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approvePersonPicker)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approveRoleCheckbox).first()).toBeVisible();
      await expect(page.getByText('System administrator')).toHaveCount(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-join-requests-form.png`,
        fullPage: true,
      });

      await page.getByTestId(TESTIDS.approveSubmit).click();

      await expect(row).toHaveCount(0, { timeout: 30_000 });
      await expect(page.getByTestId(TESTIDS.successToast)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.successToast)).toContainText(friendlyName);
      await expect.poll(async () => navBadgeCount(page), { timeout: 30_000 }).toBe(countBefore - 1);

      const adminBearer = await captureAdminBearer(browser, baseURL);
      const usersRes = await ctx.request.get('/api/v1/users', {
        headers: { authorization: adminBearer },
      });
      expect(usersRes.status(), await usersRes.text()).toBe(200);
      const users = (await usersRes.json()) as {
        username: string;
        personId?: string;
        roles: string[];
      }[];
      const member = users.find((u) => u.username === pilot.email);
      expect(member, `the admitted pilot has a t_user in the admin's club`).toBeDefined();
      expect(member!.personId, 'the approve auto-created + linked a Person').toBeTruthy();
      expect(member!.roles).toContain('PILOT');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-12b',
        caption:
          'J-12b · admin approve · a CLUB_ADMINISTRATOR opens /join-requests via the chrome, sees the ' +
          'own-club pending request, approves it through the modal (roles + optional Person), the row ' +
          'drops with a success toast while the nav pending-count badge decrements live, and the pilot ' +
          'is admitted as a t_user with an auto-created, linked Person in the admin’s users list',
        acTag: 'happy',
      });
    }
  });

  test('[edge] deny via the modal (reason ≤500 + char counter) → row drops + badge decrements + pilot-denied mail', async ({
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
      await page.goto('/start');
      await expect(page).toHaveURL('/start');
      await enterViaNav(page, JOIN_REQUESTS_PATH);
      await expect(page).toHaveURL(JOIN_REQUESTS_PATH);

      const row = page.getByTestId(TESTIDS.row).filter({ hasText: pilot.email });
      await expect(row).toBeVisible();
      const countBefore = await navBadgeCount(page);
      expect(countBefore).toBeGreaterThanOrEqual(1);

      await row.getByTestId(TESTIDS.rowDeny).click();
      await expect(page.getByTestId(TESTIDS.denyModal)).toBeVisible();
      await page.getByTestId(TESTIDS.denyReason).fill(reason);
      await expect(page.getByTestId(TESTIDS.denyReasonCounter)).toHaveText(`${reason.length}/500`);
      await page.getByTestId(TESTIDS.denySubmit).click();

      await expect(row).toHaveCount(0, { timeout: 30_000 });
      await expect.poll(async () => navBadgeCount(page), { timeout: 30_000 }).toBe(countBefore - 1);

      const mail = await waitForMessageWithSubject(pilot.email, SUBJECT_PILOT_DENIED, {
        timeoutMs: 20_000,
      });
      const body = mail.HTML ?? mail.Text ?? '';
      expect(body).toContain(reason);
    } finally {
      await ctx.close();
    }
  });

  test('[key-error] 409 surfaced inline — an already-decided request approved from a stale modal', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const pilot = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      allowConsoleErrors(testInfo, /Failed to load resource.*join-requests.*409/i, /409/);
      cleanupEmails.push(pilot.email);
      await filePendingRequest(browser, baseURL, pilot);

      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto('/start');
      await expect(page).toHaveURL('/start');
      await enterViaNav(page, JOIN_REQUESTS_PATH);
      await expect(page).toHaveURL(JOIN_REQUESTS_PATH);

      const row = page.getByTestId(TESTIDS.row).filter({ hasText: pilot.email });
      await expect(row).toBeVisible();
      await row.getByTestId(TESTIDS.rowApprove).click();
      await expect(page.getByTestId(TESTIDS.approveModal)).toBeVisible();

      const adminBearer = await captureAdminBearer(browser, baseURL);
      const pending = await findPendingFor(ctx.request, adminBearer, pilot.email);
      const denied = await ctx.request.post(`/api/v1/join-requests/${pending.id}/deny`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { reason: 'Decided out of band before the modal submitted.' },
      });
      expect(denied.status(), await denied.text()).toBe(200);

      await page.getByTestId(TESTIDS.approveSubmit).click();
      await expect(page.getByTestId(TESTIDS.approveError)).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId(TESTIDS.approveModal)).toBeVisible();
    } finally {
      await ctx.close();
    }
  });

  test('[edge] empty state — no pending requests shows the empty state + a Club-edit join-code link', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      const adminBearer = await captureAdminBearer(browser, baseURL);
      await drainPending(ctx.request, adminBearer);

      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto('/start');
      await expect(page).toHaveURL('/start');
      await enterViaNav(page, JOIN_REQUESTS_PATH);
      await expect(page).toHaveURL(JOIN_REQUESTS_PATH);
      await expect(page.getByTestId(TESTIDS.page)).toBeVisible();

      await expect(page.getByTestId(TESTIDS.emptyState)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.emptyStateClubLink)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.navBadge)).toHaveCount(0);
      await expect(page.getByTestId(TESTIDS.emptyStateClubLink)).toHaveAttribute(
        'href',
        new RegExp(`/clubs/.+/edit$`),
      );
    } finally {
      await ctx.close();
    }
  });

  test('[edge] non-admin cannot reach /join-requests (403 / redirect)', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, PILOT_USER, PILOT_PASSWORD);
      await page.waitForURL(/\/start$/, { timeout: 30_000 });
      await page.goto(JOIN_REQUESTS_PATH);
      await expect(page).not.toHaveURL(new RegExp(`${JOIN_REQUESTS_PATH}$`));
      await expect(page.getByTestId(TESTIDS.page)).toHaveCount(0);
      await openMasterdataGroup(page);
      await expect(page.getByTestId(`af-nav-section-${JOIN_REQUESTS_PATH}`)).toHaveCount(0);
      await expect(page.getByTestId(CLUB_SETTINGS_NAV_TESTID)).toHaveCount(0);
      await expect(page.getByTestId('af-nav-section-/persons')).toBeVisible();
    } finally {
      await ctx.close();
    }
  });
});
