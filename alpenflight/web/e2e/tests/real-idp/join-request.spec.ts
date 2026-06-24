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

/**
 * J-12a pilot self-serve club join against the REAL chain (live Keycloak auth +
 * real Spring backend + real Postgres + Mailpit). Greenfield: legacy has no
 * join-by-code, so the proof is the real-idp join lifecycle, not a legacy
 * pairing. This is the journey `parity_test` — the per-push real-idp derive
 * auto-scopes to it now that it carries active tests (T-02).
 *
 * The flow drives every acceptance item fully real: signup → `/join` → submit a
 * valid code → `/join/pending` (public club projection) → a CLUB_ADMINISTRATOR
 * approves through the real `POST /join-requests/{id}/approve` (KC clubId
 * attribute + t_user + auto-Person + roles) → SSE → OIDC token-refresh → the
 * now-member lands on `/start`; plus deny+reason, withdraw+resubmit, the 429
 * rate-limit, the 404 unknown-code, and the 409 already-member. The approve/deny
 * decisions ride the real endpoint with a real CLUB_ADMINISTRATOR bearer
 * (clubadmin4, seed-club-1) even though the admin SCREEN is J-12b.
 */

const JOIN_PATH = '/join';
const JOIN_PENDING_PATH = '/join/pending';

/** The fixed seed-club-1 join code (V48 stamps it; the public display is V51). */
const SEED_JOIN_CODE = 'SEEDCLUB';
const SEED_CLUB_NAME = 'Seed Club';
const SEED_CLUB_CITY = 'Zürich';

/** seed-club-1's clean-seed CLUB_ADMINISTRATOR — the approving admin (V29). */
const ADMIN_USER = 'clubadmin4@example.com';
const ADMIN_PASSWORD = 'clubadmin4-dev-2026!';

/** German subjects from JoinRequestEmailListener (de is the realm default). */
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

/**
 * Provision a fresh, email-verified, TENANT-LESS pilot (no `clubId` attribute,
 * no `t_user`) in Keycloak, then log in through the real KC login form and land
 * on `/join`.
 *
 * Why provision-then-login over driving KC's self-registration form: the realm
 * runs `registrationEmailAsUsername=false` + `verifyEmail=true`, so the native
 * register form demands a separate username AND gates the session behind an
 * email-verification action-token mail (broken since the KC-26 upgrade —
 * register.spec.ts's happy path is `@quarantine-kc26` for exactly that). KC's
 * registration UI is KC code, not AlpenFlight's; J-12a owns the JOIN flow. A
 * provisioned verified pilot is a genuinely tenant-less new signup — hitting
 * `/join` unauthenticated drives the real `authGuard` → KC login → the
 * `tenantRequiredGuard` onboarding redirect (no t_user, no live request →
 * `/join`), the load-bearing onboarding behavior, with no mail dependency.
 */
async function provisionAndLoginOnJoin(page: Page, user: TestUser): Promise<void> {
  // No clubId attribute → the principal resolves to NO_TENANT (a genuine
  // onboarding pilot). emailVerified so the session is not gated behind verify.
  await createUserWithAttributes(user, {});
  // Sign in via the landing CTA (the real entry, same as login.spec). Post-login
  // the SPA lands the authed principal on /start; the tenantRequiredGuard sees a
  // tenant-less non-admin with no live request and redirects to /join — the
  // load-bearing onboarding redirect.
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, user.email, user.password);
  await page.waitForURL(new RegExp(`${JOIN_PATH}$`), { timeout: 30_000 });
  await expect(page.getByTestId('join-page')).toBeVisible();
}

/** Enter a join code (+ optional note) and submit on the `/join` screen. */
async function submitJoinCode(page: Page, code: string, note?: string): Promise<void> {
  // `join-code-input` is on the <af-input> host; the fillable element is its
  // inner native <input>. The note textarea carries the testid natively.
  await page.getByTestId('join-code-input').locator('input').fill(code);
  if (note !== undefined) {
    await page.getByTestId('join-note-input').fill(note);
  }
  await page.getByTestId('join-submit').click();
}

/**
 * Drive clubadmin4 (seed-club-1) through the SPA login in a throwaway context
 * and capture the Bearer the OIDC interceptor attaches to its first `/api/v1/`
 * read, so the spec can call the real approve/deny endpoints as a real
 * CLUB_ADMINISTRATOR. clubadmin4's tenant resolves to seed-club-1 via the
 * keycloak_sub lookup (its `clubId` claim is the `club-1` label, not a UUID), so
 * its @TenantId-scoped pending list + decisions target the joining club.
 */
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
    // /start's club-admin dashboard issues a tenant-scoped GET the interceptor
    // stamps; that is the request we read the Bearer off.
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

/**
 * Find the pilot's pending request in the admin's own-club pending list, keyed
 * by the pilot's registration email. The admin owns this tenant-scoped read.
 */
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
      // 1. Signup → /join → submit the valid seed code + a note → /join/pending.
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);
      await submitJoinCode(page, SEED_JOIN_CODE, 'Looking forward to flying with you.');

      // 2. /join/pending renders the public club projection (name + city + logo)
      //    the contract promises, plus the Withdraw affordance.
      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));
      await expect(page.getByTestId('join-pending-page')).toBeVisible();
      await expect(page.getByTestId('join-pending-club-name')).toHaveText(SEED_CLUB_NAME);
      await expect(page.getByTestId('join-pending-city')).toHaveText(SEED_CLUB_CITY);
      await expect(page.getByTestId('join-pending-club-logo')).toBeVisible();
      await expect(page.getByTestId('join-pending-withdraw')).toBeVisible();

      // 3. The admin is emailed the new request (admin-new-request → every
      //    club-1 admin; clubadmin4's address is the run-stable one we assert).
      await waitForMessageWithSubject(ADMIN_USER, SUBJECT_ADMIN_NEW_REQUEST, { timeoutMs: 20_000 });

      // 4. A real CLUB_ADMINISTRATOR approves through the real endpoint.
      const adminBearer = await captureAdminBearer(browser, baseURL);
      const pending = await findPendingFor(ctx.request, adminBearer, user.email);
      expect(pending.clubId).toBe('019e30c3-2c00-7001-8000-000000000001');
      await approve(ctx.request, adminBearer, pending.id);

      // 5. The pilot is emailed the approval (unique recipient → singleton).
      await waitForMessage(user.email, { timeoutMs: 20_000 });

      // 6. SSE drives the pending page → OIDC token refresh (new clubId claim) →
      //    the now-member lands on /start as a PILOT, in-club. No cold goto: the
      //    SSE handler navigates in-app, which dodges the OIDC-reboot stall.
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

      // SSE → loadMine → the denied view flips on; the reason is shown + a
      // "try a different code" CTA back to /join.
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

      // Withdraw → back to /join with no live request.
      await page.getByTestId('join-pending-withdraw').click();
      await expect(page).toHaveURL(new RegExp(`${JOIN_PATH}$`));
      await expect(page.getByTestId('join-page')).toBeVisible();

      // A withdraw starts NO cooldown, so an immediate re-submit is allowed and
      // files a fresh pending request.
      await submitJoinCode(page, SEED_JOIN_CODE, 'Re-applying after a withdraw.');
      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));
      await expect(page.getByTestId('join-pending-club-name')).toHaveText(SEED_CLUB_NAME);

      // Leave the realm clean: withdraw the re-submitted request too.
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
      // The browser logs the deliberate 404 as a resource-load console.error.
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
      // The guard counts EVERY attempt (unknown-code probes included), so 6
      // probes in the 15-min window trip the 429. The browser logs each 404 +
      // the final 429 as resource-load console.errors.
      allowConsoleErrors(testInfo, /Failed to load resource.*join-requests/i, /40[49]/, /429/);
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);

      // 5 attempts are allowed (each a 404 unknown-code probe); the 6th → 429.
      // Codes are 8 chars drawn ONLY from the story alphabet
      // (ABCDEFGHJKLMNPQRSTUVWXYZ23456789 — no 0/1/O/I); a digit outside it is
      // stripped by the input sanitizer and the too-short code never submits.
      for (let i = 0; i < 5; i++) {
        await submitJoinCode(page, `ZZNKPCD${2 + i}`);
        await expect(page.getByTestId('join-error')).toBeVisible();
      }
      await submitJoinCode(page, 'ZZNKPCD7');
      await expect(page.getByTestId('join-countdown')).toBeVisible({ timeout: 10_000 });
      // The countdown disables submit until Retry-After elapses. `join-submit`
      // is on the <af-button> host; the disabled attribute lands on its inner
      // native <button>.
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
      // The deliberate 409 surfaces as a resource-load console.error.
      allowConsoleErrors(testInfo, /Failed to load resource.*join-requests.*409/i, /409/);
      cleanupEmails.push(user.email);
      await provisionAndLoginOnJoin(page, user);
      await submitJoinCode(page, SEED_JOIN_CODE);
      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));

      // Approve so the pilot now has a t_user (one-sub-one-club).
      const adminBearer = await captureAdminBearer(browser, baseURL);
      const pending = await findPendingFor(ctx.request, adminBearer, user.email);
      await approve(ctx.request, adminBearer, pending.id);
      await page.waitForURL(/\/start$/, { timeout: 30_000 });

      // Now a member, re-submitting any code is a 409 — surfaced as the
      // already-member inline message on /join.
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
