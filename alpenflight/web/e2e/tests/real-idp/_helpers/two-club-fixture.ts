import { type Browser, type Page, expect } from '@playwright/test';

import { assignRealmRole, createUserWithAttributes, deleteUser } from './keycloak-admin';
import { fillKcLogin } from './kc-form';
import {
  E2E_EMAIL_PREFIX,
  E2E_EMAIL_SUFFIX,
  E2E_CANNED_PASSWORD,
  type TestUser,
} from './test-user';

/**
 * Two-club tenant-isolation fixture for the J-0 real-idp Locations proof.
 *
 * Provisions, against the REAL stack (live Keycloak + live Spring backend +
 * real Postgres), two clubs each with its own CLUB_ADMINISTRATOR:
 *
 *   - **Club A** is the Flyway-seeded `seed-club-1` row (always present after
 *     a clean migrate — see `V5__clubs_walking_skeleton.sql`). No creation
 *     needed; we only mint a fresh KC admin bound to it.
 *   - **Club B** is created at runtime via `POST /api/v1/clubs`. That surface
 *     requires SYSTEM_ADMINISTRATOR (`ClubsController.createClub`), and no
 *     realm client grants the resource-owner-password flow, so we obtain a
 *     sysadmin bearer the only real way: a one-time browser login as the
 *     seeded `sysadmin` user, capturing the Bearer the SPA attaches to its
 *     own `/api/v1/*` calls (the `secureRoutes` interceptor). That bearer
 *     then drives the club-create request.
 *
 * Each club's CLUB_ADMINISTRATOR is a fresh `e2e-…@example.com` KC user with
 * the `clubId` user-attribute set to its club's **real UUID**. The realm's
 * `clubId` mapper projects that attribute as a `clubId` claim, and the
 * backend's `ClubTenantIdentifierResolver` parses a UUID-shaped claim as the
 * tenant directly — so no `t_user` seed row is required for tenant
 * resolution, and the `ClubsController` SpEL gate
 * (`#id.value().toString() == principal.claims['clubId']`) is satisfied too.
 *
 * Cleanup: the KC admin users are `e2e-…@example.com`, swept by
 * `global-teardown.ts`; this fixture also deletes them explicitly via
 * `dispose()`. The created club B row is left behind (clubs accumulate
 * harmlessly; a fresh per-run slug avoids the unique-slug 409).
 */

/** Flyway-seeded `seed-club-1` (V5__clubs_walking_skeleton.sql). */
const SEED_CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';

/** Canonical reference seeds (V2__identity_and_reference.sql / V5). */
const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const ACTIVE_CLUB_STATE_ID = '019e2e15-2c00-7bb8-8000-000000000bb8';

/** Seeded sysadmin (realm-export.json) — the only principal that may POST clubs. */
const SYSADMIN_USER = 'sysadmin@example.com';
const SYSADMIN_PASSWORD = 'sysadmin-dev-2026!';

const CLUB_ADMINISTRATOR_ROLE = 'CLUB_ADMINISTRATOR';

export interface ClubAdmin {
  /** Raw club UUID (no `clb-` prefix) — matches the `clubId` claim. */
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

function adminUser(label: string): TestUser {
  return {
    email: `${E2E_EMAIL_PREFIX}${runId()}-${label}${E2E_EMAIL_SUFFIX}`,
    password: E2E_CANNED_PASSWORD,
    firstName: 'E2e',
    lastName: `Admin${label}`,
  };
}

/**
 * Drive the seeded `sysadmin` through the SPA login and capture the Bearer
 * the OIDC interceptor attaches to its first `/api/v1/*` call. Navigates to
 * `/clubs` (sysadmin's catalog list) to guarantee such a call fires.
 */
async function captureSysadminBearer(browser: Browser, baseURL: string): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();

  const bearerPromise = page.waitForRequest((req) => {
    const auth = req.headers()['authorization'];
    return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
  });

  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, SYSADMIN_USER, SYSADMIN_PASSWORD);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });

  // sysadmin's authed home; the clubs list issues GET /api/v1/clubs.
  await page.goto('/clubs');

  const req = await bearerPromise;
  const bearer = req.headers()['authorization']!;
  await context.close();
  return bearer;
}

/** Create club B via the real `POST /api/v1/clubs` surface as sysadmin. */
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
    if (!res.ok()) {
      throw new Error(`createClubB failed (${res.status()}): ${await res.text()}`);
    }
    const body = (await res.json()) as { id: string };
    // ClubResponse.id is the prefixed external form `clb-<uuid>`; the
    // tenant claim + SpEL gate compare against the raw UUID.
    return body.id.replace(/^clb-/, '');
  } finally {
    await ctx.close();
  }
}

async function provisionClubAdmin(clubId: string, label: string): Promise<ClubAdmin> {
  const user = adminUser(label);
  const kcUserId = await createUserWithAttributes(user, { clubId: [clubId] });
  await assignRealmRole(kcUserId, CLUB_ADMINISTRATOR_ROLE);
  return { clubId, user, kcUserId };
}

/**
 * Provision the two-club fixture. Club A reuses the Flyway seed; club B is
 * created live. Returns each club's CLUB_ADMINISTRATOR login handle plus a
 * `dispose()` that removes the KC admin users.
 */
export async function provisionTwoClubs(
  browser: Browser,
  baseURL: string,
): Promise<TwoClubFixture> {
  const clubBId = await createClubB(browser, baseURL);

  const clubA = await provisionClubAdmin(SEED_CLUB_A_ID, 'club-a-admin');
  const clubB = await provisionClubAdmin(clubBId, 'club-b-admin');

  return {
    clubA,
    clubB,
    dispose: async () => {
      await deleteUser(clubA.kcUserId, clubA.user.email);
      await deleteUser(clubB.kcUserId, clubB.user.email);
    },
  };
}

/**
 * Log a CLUB_ADMINISTRATOR in through the SPA + Keycloak login form, landing
 * on the authed root. The page's storageState is the per-club session.
 */
export async function loginAsClubAdmin(page: Page, admin: ClubAdmin): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, admin.user.email, admin.user.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}
