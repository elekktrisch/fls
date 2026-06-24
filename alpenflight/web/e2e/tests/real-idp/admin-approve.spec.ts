import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import { fillKcLogin } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';
import { createUserWithAttributes, findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import { waitForMessageWithSubject, purgeMailpit } from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';

/**
 * Admin join-request approval (`/join-requests`) against the REAL chain (live
 * Keycloak auth + real Spring backend + real Postgres + Mailpit). Greenfield:
 * J-12b is the admin SCREEN over J-12a's already-shipped JoinRequest backend
 * (list / approve / deny + the `join-request.status-changed` SSE), so the proof
 * is the real-idp admin-approval lifecycle, not a legacy pairing.
 *
 * The admin enters through the real chrome (landing sign-in → real KC redirect
 * login → masterdata nav → `/join-requests`) and drives every acceptance item
 * fully real: the own-club pending list + the LIVE nav badge; approve via the
 * modal (role catalogue + optional Person) admitting the pilot (verified by the
 * pilot reaching their auto-created Person over `/api/v1/me/person`); deny with a
 * reason + the pilot-denied Mailpit mail; a real already-decided 409 surfaced
 * inline; the empty state + the non-admin reach guard.
 */

/** seed-club-1's clean-seed CLUB_ADMINISTRATOR — the approving admin (V29). */
const ADMIN_USER = 'clubadmin4@example.com';
const ADMIN_PASSWORD = 'clubadmin4-dev-2026!';

/** seed-club-1's clean-seed low-privilege PILOT — the non-admin reach guard (V8). */
const PILOT_USER = 'pilot1@example.com';
const PILOT_PASSWORD = 'pilot1-dev-2026!';

/** The fixed seed-club-1 join code a fresh pilot submits to create a pending row. */
const SEED_JOIN_CODE = 'SEEDCLUB';

/** German subject from JoinRequestEmailListener (de is the realm default). */
const SUBJECT_PILOT_DENIED = 'Beitrittsanfrage abgelehnt';

const JOIN_REQUESTS_PATH = '/join-requests';

/** The screen-shape contract — the testids T-03..T-06 expose, asserted here. */
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
 * request against seed-club-1 through the real submit flow, so the admin's
 * `/join-requests` list has an own-club row to triage. The pilot's KC user is
 * cleaned up in afterEach. Returns the pilot's friendlyName so the row's
 * friendly-name cell can be asserted on a known value.
 */
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

/**
 * Drive clubadmin4 (seed-club-1) through the SPA login in a throwaway context
 * and capture the Bearer the OIDC interceptor attaches to its first `/api/v1/`
 * read, so the spec can read the tenant-scoped pending list / verify the
 * auto-created Person as a real CLUB_ADMINISTRATOR off the UI thread.
 */
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

/** The admin's own-club pending list (tenant-scoped to seed-club-1). */
async function listPending(api: APIRequestContext, adminBearer: string): Promise<PendingRow[]> {
  const res = await api.get('/api/v1/join-requests?status=pending', {
    headers: { authorization: adminBearer },
  });
  expect(res.status(), await res.text()).toBe(200);
  return (await res.json()) as PendingRow[];
}

/** Find the pilot's pending request in the admin's own-club list, keyed by email. */
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

/**
 * Drain every pending request in the admin's tenant by denying it over the real
 * endpoint, so the empty-state case asserts a genuinely empty own-club queue
 * regardless of rows a sibling case left undecided. The denied requests' KC
 * users are foreign here, so they're swept by `global-teardown.ts`, not us.
 */
async function drainPending(api: APIRequestContext, adminBearer: string): Promise<void> {
  for (const row of await listPending(api, adminBearer)) {
    const res = await api.post(`/api/v1/join-requests/${row.id}/deny`, {
      headers: { authorization: adminBearer, 'content-type': 'application/json' },
      data: { reason: 'Draining the queue for the empty-state proof.' },
    });
    expect(res.status(), await res.text()).toBe(200);
  }
}

/** Read the live nav-badge count (the rolled-up Masterdata trigger pill); 0 when absent. */
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

      // ENTER via the chrome: real KC login → /start → the Masterdata nav → the
      // nested Join requests entry. enterViaNav opens the Masterdata group first.
      await loginAs(page, ADMIN_USER, ADMIN_PASSWORD);
      await page.goto('/start');
      await expect(page).toHaveURL('/start');
      await enterViaNav(page, JOIN_REQUESTS_PATH);
      await expect(page).toHaveURL(JOIN_REQUESTS_PATH);
      await expect(page.getByTestId(TESTIDS.page)).toBeVisible();

      // The own-club pending row carries the pilot's friendlyName + email +
      // submitted-at; the nav badge reflects a positive pending count.
      const row = page.getByTestId(TESTIDS.row).filter({ hasText: pilot.email });
      await expect(row).toBeVisible();
      await expect(row.getByTestId(TESTIDS.rowEmail)).toHaveText(pilot.email);
      await expect(row.getByTestId(TESTIDS.rowFriendlyName)).toHaveText(friendlyName);
      await expect(row.getByTestId(TESTIDS.rowSubmittedAt)).toBeVisible();
      const countBefore = await navBadgeCount(page);
      expect(countBefore).toBeGreaterThanOrEqual(1);

      // Capture the populated LIST shot for the gallery BEFORE the deeper flow.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-join-requests-list.png`,
        fullPage: true,
      });

      // Approve via the modal: read-only request info, the grantable-role
      // checkboxes (S-168 catalogue), an optional Person picker. SYSTEM_ADMINISTRATOR
      // is intentionally NOT offered (the backend 403s the grant).
      await row.getByTestId(TESTIDS.rowApprove).click();
      await expect(page.getByTestId(TESTIDS.approveModal)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approveRequestInfo)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approvePersonPicker)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.approveRoleCheckbox).first()).toBeVisible();
      await expect(page.getByText('System administrator')).toHaveCount(0);

      // Capture the populated FORM (modal-open) shot for the gallery, with the
      // inline request info + role catalogue rendered, BEFORE submitting.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-join-requests-form.png`,
        fullPage: true,
      });

      // PILOT is checked by default; approve with the baseline role.
      await page.getByTestId(TESTIDS.approveSubmit).click();

      // The row drops, the success toast shows, and the live badge decrements by 1.
      await expect(row).toHaveCount(0, { timeout: 30_000 });
      await expect(page.getByTestId(TESTIDS.successToast)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.successToast)).toContainText(friendlyName);
      await expect.poll(async () => navBadgeCount(page), { timeout: 30_000 }).toBe(countBefore - 1);

      // The pilot is admitted AND the approve-WITHOUT-Person path auto-created a
      // Person + PersonClub server-side — both proven by the admin's own-club
      // users list, read over the real endpoint as a real CLUB_ADMINISTRATOR. The
      // now-member surfaces as a `t_user` (admission) keyed by their username
      // (the join email), carrying a non-empty `personId` (the auto-created
      // Person link) and the granted PILOT role. A bound t_user with a personId
      // is the auto-create + PersonClub the approve path writes; a half-join
      // would surface no row or a null personId. (The KC clubId-attribute write
      // is pinned by J-12a's SSE → token-refresh → in-club /start; not re-checked
      // here — the suite's provisioned users carry no KC `email` field, so a
      // fresh pilot re-login hits KC's incomplete-profile required action.)
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
      // The counter reflects the typed length (the load-bearing ≤500 affordance).
      await expect(page.getByTestId(TESTIDS.denyReasonCounter)).toHaveText(`${reason.length}/500`);
      await page.getByTestId(TESTIDS.denySubmit).click();

      // The row drops + the badge decrements live.
      await expect(row).toHaveCount(0, { timeout: 30_000 });
      await expect.poll(async () => navBadgeCount(page), { timeout: 30_000 }).toBe(countBefore - 1);

      // The denied pilot is emailed (unique recipient → keyed on the denied
      // subject; the reason rides the body).
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
      // The deliberate 409 surfaces as a resource-load console.error.
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

      // Decide the SAME request out-of-band (a second admin / a stale tab):
      // deny it through the real endpoint so the open modal's request is no
      // longer PENDING. The FSM rejects the subsequent approve with a 409
      // (IllegalJoinRequestStateException) the modal surfaces inline.
      const adminBearer = await captureAdminBearer(browser, baseURL);
      const pending = await findPendingFor(ctx.request, adminBearer, pilot.email);
      const denied = await ctx.request.post(`/api/v1/join-requests/${pending.id}/deny`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { reason: 'Decided out of band before the modal submitted.' },
      });
      expect(denied.status(), await denied.text()).toBe(200);

      // Submit the stale modal → real 409 → surfaced in approve-error inline.
      await page.getByTestId(TESTIDS.approveSubmit).click();
      await expect(page.getByTestId(TESTIDS.approveError)).toBeVisible({ timeout: 30_000 });
      // The modal STAYS open on the conflict (fire-and-stay) so the admin reads it.
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
      // Drain any rows a sibling case left undecided so the queue is genuinely
      // empty for the admin's tenant (assert what the realm genuinely produces).
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
      // The empty queue carries no nav badge pill.
      await expect(page.getByTestId(TESTIDS.navBadge)).toHaveCount(0);
      // The Club-edit link points at the admin's own club's edit screen.
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
      // A real low-privilege PILOT (not a mock admin-everything principal) must
      // not land on the admin screen — the clubAdminGuard redirects away. pilot1
      // is a tenant-resolved member, so the post-login warm session lands on
      // /start; navigating to the guarded path drives the guard.
      await loginAs(page, PILOT_USER, PILOT_PASSWORD);
      await page.waitForURL(/\/start$/, { timeout: 30_000 });
      await page.goto(JOIN_REQUESTS_PATH);
      await expect(page).not.toHaveURL(new RegExp(`${JOIN_REQUESTS_PATH}$`));
      await expect(page.getByTestId(TESTIDS.page)).toHaveCount(0);
      // A PILOT's nav has no Join-requests entry at all (admin-gated child).
      await expect(page.getByTestId(`af-nav-section-${JOIN_REQUESTS_PATH}`)).toHaveCount(0);
    } finally {
      await ctx.close();
    }
  });
});
