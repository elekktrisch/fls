import { randomUUID } from 'node:crypto';

import { type Browser, type BrowserContext, type Page, expect } from '@playwright/test';

import { assignRealmRole, createUserWithAttributes, deleteUser } from './keycloak-admin';
import { fillKcLogin } from './kc-form';
import {
  E2E_EMAIL_PREFIX,
  E2E_EMAIL_SUFFIX,
  E2E_CANNED_PASSWORD,
  type TestUser,
} from './test-user';

const SEED_CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';

export const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
export const ACTIVE_CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';

const SYSADMIN_USER = 'sysadmin@example.com';
const SYSADMIN_PASSWORD = 'sysadmin-dev-2026!';

const SYSADMIN_PAGE_THAT_ISSUES_AN_API_CALL = '/clubs';

const CLUB_ADMINISTRATOR_ROLE = 'CLUB_ADMINISTRATOR';

export interface ClubAdmin {
  clubId: string;
  user: TestUser;
  kcUserId: string;
}

export interface TwoClubFixture {
  clubA: ClubAdmin;
  clubB: ClubAdmin;
  dispose: () => Promise<void>;
}

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the two-club fixture');
  }
  return id;
}

function adminUser(label: string, scope: string, aliveUsernameNonce: string): TestUser {
  return {
    email: `${E2E_EMAIL_PREFIX}${runId()}-${scope}-${label}-${aliveUsernameNonce}${E2E_EMAIL_SUFFIX}`,
    password: E2E_CANNED_PASSWORD,
    firstName: 'E2e',
    lastName: `Admin${label}`,
  };
}

export async function captureSysadminBearer(browser: Browser, baseURL: string): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();

  const bearerFromFirstAuthorizedApiCall = page.waitForRequest((req) => {
    const auth = req.headers()['authorization'];
    return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
  });

  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, SYSADMIN_USER, SYSADMIN_PASSWORD);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });

  await page.goto(SYSADMIN_PAGE_THAT_ISSUES_AN_API_CALL);

  const req = await bearerFromFirstAuthorizedApiCall;
  const bearer = req.headers()['authorization']!;
  await context.close();
  return bearer;
}

function rawClubId(prefixedId: string): string {
  return prefixedId.replace(/^clb-/, '');
}

async function createClubB(browser: Browser, baseURL: string): Promise<string> {
  const bearer = await captureSysadminBearer(browser, baseURL);
  const slug = `e2e-${runId()}-club-b`;
  const ctx = await browser.newContext({ baseURL });
  try {
    const res = await ctx.request.post('/api/v1/clubs', {
      headers: { authorization: bearer, 'content-type': 'application/json' },
      data: {
        name: `E2E Club B ${runId()}`,
        slug,
        clubKey: `E2EB${runId().slice(0, 4)}`,
        publicRegistrationEnabled: false,
        countryId: CH_COUNTRY_ID,
        clubStateId: ACTIVE_CLUB_STATE_ID,
      },
    });
    if (res.status() === 409) {
      const clubBFromAPriorAttempt = await findClubIdBySlug(ctx, bearer, slug);
      if (!clubBFromAPriorAttempt) {
        throw new Error(
          `createClubB 409'd on slug "${slug}" but no club with that slug is listed — ` +
            'slug collides with a non-recoverable row',
        );
      }
      if (clubBFromAPriorAttempt === SEED_CLUB_A_ID) {
        throw new Error(
          `createClubB recovered the seeded club A id for club B (${clubBFromAPriorAttempt})`,
        );
      }
      return clubBFromAPriorAttempt;
    }
    if (!res.ok()) {
      throw new Error(`createClubB failed (${res.status()}): ${await res.text()}`);
    }
    const body = (await res.json()) as { id: string };
    return rawClubId(body.id);
  } finally {
    await ctx.close();
  }
}

async function findClubIdBySlug(
  ctx: BrowserContext,
  bearer: string,
  slug: string,
): Promise<string | undefined> {
  const res = await ctx.request.get('/api/v1/clubs', {
    headers: { authorization: bearer },
  });
  if (!res.ok()) {
    throw new Error(`listClubs failed (${res.status()}): ${await res.text()}`);
  }
  const clubs = (await res.json()) as { id: string; slug?: string | null }[];
  const match = clubs.find((c) => c.slug === slug);
  return match ? rawClubId(match.id) : undefined;
}

async function provisionClubAdmin(
  clubId: string,
  label: string,
  scope: string,
  aliveUsernameNonce: string,
): Promise<ClubAdmin> {
  const user = adminUser(label, scope, aliveUsernameNonce);
  const kcUserId = await createUserWithAttributes(user, { clubId: [clubId] });
  await assignRealmRole(kcUserId, CLUB_ADMINISTRATOR_ROLE);
  return { clubId, user, kcUserId };
}

export async function provisionTwoClubs(
  browser: Browser,
  baseURL: string,
  scope = 'tc',
): Promise<TwoClubFixture> {
  const clubBId = await createClubB(browser, baseURL);

  const aliveUsernameNonce = randomUUID().replace(/-/g, '').slice(0, 8);
  const clubA = await provisionClubAdmin(SEED_CLUB_A_ID, 'club-a-admin', scope, aliveUsernameNonce);
  const clubB = await provisionClubAdmin(clubBId, 'club-b-admin', scope, aliveUsernameNonce);

  return {
    clubA,
    clubB,
    dispose: async () => {
      await deleteUser(clubA.kcUserId, clubA.user.email);
      await deleteUser(clubB.kcUserId, clubB.user.email);
    },
  };
}

export async function loginAsClubAdmin(page: Page, admin: ClubAdmin): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, admin.user.email, admin.user.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}
