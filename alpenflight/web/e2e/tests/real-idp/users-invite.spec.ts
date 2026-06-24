import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import { freshTestUser, type TestUser } from './_helpers/test-user';
import {
  createUserWithAttributes,
  findUserByEmail,
  getUserById,
  deleteUser,
} from './_helpers/keycloak-admin';
import { waitForMessage, purgeMailpit } from './_helpers/mailpit-client';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * S-181 admin-invite robustness — the bind-existing branch against the REAL
 * chain (live Keycloak + real Spring backend + real Postgres + Mailpit). The
 * real-idp counterpart of S-168's mock `tests/users/users-invite.spec.ts`: that
 * file mocks `/api/v1/*`, so it cannot prove the bind reaches real Keycloak and
 * sends a real welcome-attached mail — the seam this case exists to prove must
 * run fully real (T-07 shipped `UsersService.invite`'s 3-branch hardening +
 * `UsersInviteRobustnessIT`).
 *
 * The case: a user signs up FIRST with no club (a federated/local KC user with
 * NO `clubId` attribute — the suite's genuine unattached-existing user; Google
 * credentials never enter Playwright per `alpenflight/auth/README.md`, so a
 * provisioned verified tenant-less KC user is the same unattached identity the
 * Google-signup path lands, exactly as `join-request.spec.ts` provisions its
 * tenant-less pilot). A CLUB_ADMINISTRATOR then invites that same email through
 * the real `/users/new` UI. The invite BINDS rather than collides: the `t_user`
 * row appears in the users list, the KC `clubId` attribute is now set on the
 * existing user, NO password-reset mail is sent (the user already has a
 * credential), and a welcome-attached mail lands in Mailpit.
 */

const SEED_CLUB_NAME = 'Seed Club';

/** The hardcoded welcome-attached subject (UsersService.WELCOME_ATTACHED_SUBJECT). */
const WELCOME_ATTACHED_SUBJECT = 'Welcome to AlpenFlight';

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
 * Provision a verified KC user with NO `clubId` attribute — the unattached
 * existing identity the bind-existing branch must recognise. `createUserWithAttributes`
 * with `{}` is the suite's tenant-less signup (see `join-request.spec.ts`'s
 * `provisionAndLoginOnJoin`); a Google-federated first signup lands the same
 * shape (verified, no clubId), and Playwright never drives Google directly.
 */
async function provisionUnattachedUser(user: TestUser): Promise<string> {
  return createUserWithAttributes(user, {});
}

interface InviteeUser extends TestUser {
  friendlyName: string;
}

function freshInvitee(): InviteeUser {
  const user = freshTestUser();
  return { ...user, friendlyName: 'Gina Federated' };
}

/** Invite an email through the real `/users/new` admin UI and save. */
async function inviteThroughUi(page: Page, invitee: InviteeUser, username: string): Promise<void> {
  await page.goto('/start');
  await enterViaNav(page, '/users');
  await expect(page).toHaveURL('/users');
  await page.getByTestId('users-new-button').click();
  await expect(page).toHaveURL('/users/new');

  await page.getByTestId('username-input').locator('input').fill(username);
  await page.getByTestId('friendlyName-input').locator('input').fill(invitee.friendlyName);
  await page.getByTestId('notificationEmail-input').locator('input').fill(invitee.email);
  await page.getByTestId('role-PILOT').check();

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/users' &&
      r.status() === 201,
  );
  await page.getByTestId('user-save-button').click();
  await created;
  await expect(page).toHaveURL('/users');
}

test.describe('Admin invite robustness — bind unattached existing KC user (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let twoClubs: TwoClubFixture;
  const cleanupEmails: string[] = [];

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // clubA is the Flyway-seeded seed-club-1 ("Seed Club") — the inviting club
    // whose name renders in the welcome-attached mail body.
    twoClubs = await provisionTwoClubs(browser, baseURL, 'inv');
  });

  test.afterAll(async () => {
    await twoClubs?.dispose();
  });

  test.afterEach(async () => {
    const targets = cleanupEmails.splice(0);
    for (const email of targets) {
      const kcUser = await findUserByEmail(email);
      if (!kcUser) continue;
      await deleteUser(kcUser.id, kcUser.email);
    }
    await purgeMailpit();
  });

  test('[happy] inviting an unattached existing KC user binds them — t_user appears, clubId set, welcome-attached mail, no password-reset', async ({
    browser,
  }, testInfo) => {
    const invitee = freshInvitee();
    const username = `e2e.bind.${Date.now().toString(36)}`;

    // The unattached existing user signs up FIRST (no clubId attribute).
    cleanupEmails.push(invitee.email);
    await provisionUnattachedUser(invitee);

    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // A CLUB_ADMINISTRATOR of "Seed Club" invites that same email via the UI.
      await loginAsClubAdmin(page, twoClubs.clubA);
      await inviteThroughUi(page, invitee, username);

      // 1. The t_user row landed — the invitee surfaces in the users list.
      await expect(page.getByText(invitee.friendlyName)).toBeVisible();

      // 2. The bind set the KC clubId attribute on the EXISTING user (read via
      //    the single-user GET, which returns attributes). The value is the
      //    inviting tenant — seed-club-1's UUID.
      const kcUser = await findUserByEmail(invitee.email);
      expect(kcUser, 'the invitee KC user still exists after the bind').toBeDefined();
      const full = await getUserById(kcUser!.id);
      expect(full.attributes?.['clubId']).toEqual([twoClubs.clubA.clubId]);

      // 3. A welcome-attached mail landed — the EN template (no KC locale →
      //    normalizeLocale → 'en') names the inviting club. `waitForMessage` is
      //    the singleton assertion: it FAILS LOUD if a SECOND mail (a stray
      //    password-reset) also reached the invitee, so it doubles as the
      //    "no password-reset email" guard.
      const mail = await waitForMessage(invitee.email, { timeoutMs: 20_000 });
      expect(mail.Subject).toBe(WELCOME_ATTACHED_SUBJECT);
      const body = mail.HTML ?? mail.Text ?? '';
      expect(body).toContain('an admin at');
      expect(body).toContain(SEED_CLUB_NAME);
      expect(body).toContain('has added you to the club');
      // The password-reset path renders a Keycloak action-token link; the bind
      // path never does. Its absence is the structural "no reset mail" proof.
      expect(body).not.toContain('login-actions/action-token');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-12b',
        caption:
          'J-12b · admin invite robustness · inviting an email that already has an unattached Keycloak ' +
          'account binds them to the club (clubId attribute set + welcome-attached mail, no password reset)',
        acTag: 'happy',
      });
    }
  });
});
