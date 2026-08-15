import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { fillKcLogin } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';
import { createUserWithAttributes, findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import { waitForMessage, waitForMessageWithSubject, purgeMailpit } from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';


const JOIN_PATH = '/join';
const JOIN_PENDING_PATH = '/join/pending';

const SEED_JOIN_CODE = 'SEEDCLUB';
const SEED_CLUB_NAME = 'Seed Club';
const SEED_CLUB_CITY = 'Zürich';

const ADMIN_USER = 'clubadmin4@example.com';
const ADMIN_PASSWORD = 'clubadmin4-dev-2026!';

const SUBJECT_ADMIN_NEW_REQUEST = 'Neue Beitrittsanfrage';
const SUBJECT_PILOT_APPROVED = 'Beitrittsanfrage genehmigt';

interface PendingRequest {
  readonly id: string;
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

async function provisionAndLoginOnJoin(page: Page, user: TestUser): Promise<void> {
  await createUserWithAttributes(user, {});
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, user.email, user.password);
  await page.waitForURL(new RegExp(`${JOIN_PATH}$`), { timeout: 30_000 });
  await expect(page.getByTestId('join-page')).toBeVisible();
}

async function submitJoinCode(page: Page, code: string, note?: string): Promise<void> {
  await page.getByTestId('join-code-input').locator('input').fill(code);
  if (note !== undefined) {
    await page.getByTestId('join-note-input').fill(note);
  }
  await page.getByTestId('join-submit').click();
}

async function captureAdminBearer(browser: Browser, baseURL: string): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerPromise = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await page.goto('/');
    await page.getByTestId('landing-topbar-sign-in').click();
    await page.waitForURL(/\/realms\/alpenflight\//);
    await fillKcLogin(page, ADMIN_USER, ADMIN_PASSWORD);
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

async function findPendingFor(
  api: APIRequestContext,
  adminBearer: string,
  pilotEmail: string,
): Promise<PendingRequest> {
  const res = await api.get('/api/v1/join-requests?status=pending', {
    headers: { authorization: adminBearer },
  });
  expect(res.status(), await res.text()).toBe(200);
  const rows = (await res.json()) as Array<{ id: string; clubId: string; email: string }>;
  const mine = rows.find((r) => r.email === pilotEmail);
  expect(mine, `no pending request for ${pilotEmail} in the admin's club`).toBeDefined();
  return { id: mine!.id, clubId: mine!.clubId };
}

async function approve(api: APIRequestContext, adminBearer: string, id: string): Promise<void> {
  const res = await api.post(`/api/v1/join-requests/${id}/approve`, {
    headers: { authorization: adminBearer, 'content-type': 'application/json' },
    data: { roles: ['PILOT'] },
  });
  expect(res.status(), await res.text()).toBe(200);
}

async function deny(
  api: APIRequestContext,
  adminBearer: string,
  id: string,
  reason: string,
): Promise<void> {
  const res = await api.post(`/api/v1/join-requests/${id}/deny`, {
    headers: { authorization: adminBearer, 'content-type': 'application/json' },
    data: { reason },
  });
  expect(res.status(), await res.text()).toBe(200);
}

test.describe('Pilot self-serve club join — real chain (real-idp)', () => {
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

  test('[happy] join lifecycle — signup → /join → submit → /join/pending → approve → SSE → /start in-club', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const user = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);
      await submitJoinCode(page, SEED_JOIN_CODE, 'Looking forward to flying with you.');

      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));
      await expect(page.getByTestId('join-pending-page')).toBeVisible();
      await expect(page.getByTestId('join-pending-club-name')).toHaveText(SEED_CLUB_NAME);
      await expect(page.getByTestId('join-pending-city')).toHaveText(SEED_CLUB_CITY);
      await expect(page.getByTestId('join-pending-club-logo')).toBeVisible();
      await expect(page.getByTestId('join-pending-withdraw')).toBeVisible();

      await waitForMessageWithSubject(ADMIN_USER, SUBJECT_ADMIN_NEW_REQUEST, { timeoutMs: 20_000 });

      const adminBearer = await captureAdminBearer(browser, baseURL);
      const pending = await findPendingFor(ctx.request, adminBearer, user.email);
      expect(pending.clubId).toBe('019e30c3-2c00-7001-8000-000000000001');
      await approve(ctx.request, adminBearer, pending.id);

      await waitForMessage(user.email, { timeoutMs: 20_000 });

      await page.waitForURL(/\/start$/, { timeout: 30_000 });
      await expect(page.getByTestId('start-variant-pilot')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-12a',
        caption:
          'J-12a · pilot join · a new signup enters the club join code, the admin approves through the ' +
          'real endpoint, and SSE + token-refresh land the now-member on /start',
        acTag: 'happy',
      });
    }
  });

  test('[edge] deny + reason — admin denies → pilot sees the reason on /join/pending → /join', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const user = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const reason = 'No spare slots this season — try again in the autumn intake.';
    try {
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);
      await submitJoinCode(page, SEED_JOIN_CODE);
      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));

      const adminBearer = await captureAdminBearer(browser, baseURL);
      const pending = await findPendingFor(ctx.request, adminBearer, user.email);
      await deny(ctx.request, adminBearer, pending.id, reason);

      await expect(page.getByTestId('join-pending-deny-reason')).toHaveText(reason, {
        timeout: 30_000,
      });
      await page.getByTestId('join-pending-retry').click();
      await expect(page).toHaveURL(new RegExp(`${JOIN_PATH}$`));
      await expect(page.getByTestId('join-page')).toBeVisible();
    } finally {
      await ctx.close();
    }
  });

  test('[edge] withdraw → /join → re-submit allowed (a withdraw starts no cooldown)', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const user = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);
      await submitJoinCode(page, SEED_JOIN_CODE);
      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));

      await page.getByTestId('join-pending-withdraw').click();
      await expect(page).toHaveURL(new RegExp(`${JOIN_PATH}$`));
      await expect(page.getByTestId('join-page')).toBeVisible();

      await submitJoinCode(page, SEED_JOIN_CODE, 'Re-applying after a withdraw.');
      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));
      await expect(page.getByTestId('join-pending-club-name')).toHaveText(SEED_CLUB_NAME);

      await page.getByTestId('join-pending-withdraw').click();
      await expect(page).toHaveURL(new RegExp(`${JOIN_PATH}$`));
    } finally {
      await ctx.close();
    }
  });

  test('[key-error] 404 unknown code — inline error, stays on /join', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const user = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      allowConsoleErrors(testInfo, /Failed to load resource.*join-requests.*404/i, /404/);
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);

      await submitJoinCode(page, 'ZZZZ9999');
      await expect(page.getByTestId('join-error')).toBeVisible();
      await expect(page).toHaveURL(new RegExp(`${JOIN_PATH}$`));
    } finally {
      await ctx.close();
    }
  });

  test('[key-error] 429 rate-limit — 6 submit attempts in the window trip the Retry-After countdown', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const user = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      allowConsoleErrors(testInfo, /Failed to load resource.*join-requests/i, /40[49]/, /429/);
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);

      for (let i = 0; i < 5; i++) {
        await submitJoinCode(page, `ZZNKPCD${2 + i}`);
        await expect(page.getByTestId('join-error')).toBeVisible();
      }
      await submitJoinCode(page, 'ZZNKPCD7');
      await expect(page.getByTestId('join-countdown')).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId('join-submit').locator('button')).toBeDisabled();
    } finally {
      await ctx.close();
    }
  });

  test('[key-error] 409 already-member — an approved member re-submitting is rejected', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const user = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      allowConsoleErrors(testInfo, /Failed to load resource.*join-requests.*409/i, /409/);
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);
      await submitJoinCode(page, SEED_JOIN_CODE);
      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));

      const adminBearer = await captureAdminBearer(browser, baseURL);
      const pending = await findPendingFor(ctx.request, adminBearer, user.email);
      await approve(ctx.request, adminBearer, pending.id);
      await page.waitForURL(/\/start$/, { timeout: 30_000 });

      await page.goto('/join');
      await expect(page.getByTestId('join-page')).toBeVisible();
      await submitJoinCode(page, SEED_JOIN_CODE);
      await expect(page.getByTestId('join-error')).toBeVisible();
      await expect(page).toHaveURL(new RegExp(`${JOIN_PATH}$`));
    } finally {
      await ctx.close();
    }
  });
});
