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

const SEED_CLUB_NAME = 'Seed Club';

const WELCOME_ATTACHED_SUBJECT = 'Welcome to AlpenFlight';

const KC_PASSWORD_RESET_ACTION_TOKEN_LINK = 'login-actions/action-token';

const NO_CLUB_ID_ATTRIBUTE: Record<string, string[]> = {};

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function provisionUnattachedUser(user: TestUser): Promise<string> {
  return createUserWithAttributes(user, NO_CLUB_ID_ATTRIBUTE);
}

interface InviteeUser extends TestUser {
  friendlyName: string;
}

function freshInvitee(): InviteeUser {
  const user = freshTestUser();
  const runUniqueTail = user.email.split('@')[0]!.split('-').pop()!;
  return { ...user, friendlyName: `Gina Federated ${runUniqueTail}` };
}

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

    cleanupEmails.push(invitee.email);
    await provisionUnattachedUser(invitee);

    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, twoClubs.clubA);
      await inviteThroughUi(page, invitee, username);

      await expect(page.getByText(invitee.friendlyName, { exact: true })).toBeVisible();

      const kcUser = await findUserByEmail(invitee.email);
      expect(kcUser, 'the invitee KC user still exists after the bind').toBeDefined();
      const full = await getUserById(kcUser!.id);
      expect(full.attributes?.['clubId']).toEqual([twoClubs.clubA.clubId]);

      const mail = await waitForMessage(invitee.email, { timeoutMs: 20_000 });
      expect(mail.Subject).toBe(WELCOME_ATTACHED_SUBJECT);
      const body = mail.HTML ?? mail.Text ?? '';
      expect(body).toContain('an admin at');
      expect(body).toContain(SEED_CLUB_NAME);
      expect(body).toContain('has added you to the club');
      expect(body).not.toContain(KC_PASSWORD_RESET_ACTION_TOKEN_LINK);
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
