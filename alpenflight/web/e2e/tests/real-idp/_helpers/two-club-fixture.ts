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

/**
 * Mint a CLUB_ADMINISTRATOR identity for one of the two clubs.
 *
 * The username MUST be disjoint per provisioning call across the whole
 * `playwright test` invocation, because `ux_user_username_lower_alive` is a
 * partial-unique over alive (non-soft-deleted) usernames in the backend: a
 * second live provisioning of the same username (a sibling spec calling
 * `provisionTwoClubs`, or a Playwright retry while the prior attempt's user is
 * still alive) collides. `runId()` is stable for the whole invocation and the
 * fixed `club-a-admin`/`club-b-admin` labels are identical across specs, so the
 * run-id + label alone are NOT enough. We fold in:
 *   - `scope` — a caller-supplied spec token (e.g. `loc`, `acft`) so the two
 *     specs that both drive this fixture read disjointly in the realm and in
 *     teardown logs; and
 *   - `nonce` — a fresh per-call random tail (mirrors `freshTestUser`'s uuid8
 *     scheme) so a retry re-randomises rather than re-inserting a still-alive
 *     username. Both halves stay under the `e2e-…@example.com` cleanup predicate.
 */
function adminUser(label: string, scope: string, nonce: string): TestUser {
  return {
    email: `${E2E_EMAIL_PREFIX}${runId()}-${scope}-${label}-${nonce}${E2E_EMAIL_SUFFIX}`,
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

/** Strip the `clb-` external-form prefix to the raw tenant UUID. */
function rawClubId(prefixedId: string): string {
  // ClubResponse.id is the prefixed external form `clb-<uuid>`; the
  // tenant claim + SpEL gate compare against the raw UUID.
  return prefixedId.replace(/^clb-/, '');
}

/**
 * Create club B via the real `POST /api/v1/clubs` surface as sysadmin.
 *
 * Idempotent across Playwright RETRIES: the slug is deterministic per run
 * (`E2E_RUN_ID` is stable within a run, so retry attempts reuse it), and
 * there is no per-retry teardown of the created club row. A naive create
 * therefore 409s on the slug-unique index on the second attempt. We treat
 * that 409 as "club B already provisioned by a prior attempt" and recover
 * its id by listing clubs (sysadmin's read surface) and matching the slug —
 * reusing the SAME distinct club, so the two-distinct-clubs tenant-isolation
 * premise holds (club B is still the runtime-created club, never the
 * Flyway-seeded club A).
 */
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
      // A prior (failed/retried) attempt already created club B under this
      // run's slug. Recover its id rather than failing — and assert it is a
      // DISTINCT club from the seeded club A.
      const existingId = await findClubIdBySlug(ctx, bearer, slug);
      if (!existingId) {
        throw new Error(
          `createClubB 409'd on slug "${slug}" but no club with that slug is listed — ` +
            'slug collides with a non-recoverable row',
        );
      }
      if (existingId === SEED_CLUB_A_ID) {
        throw new Error(`createClubB recovered the seeded club A id for club B (${existingId})`);
      }
      return existingId;
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

/**
 * Find an existing club's raw UUID by its slug via sysadmin's `GET
 * /api/v1/clubs` catalog. Returns `undefined` when no club carries the slug.
 */
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
  nonce: string,
): Promise<ClubAdmin> {
  const user = adminUser(label, scope, nonce);
  const kcUserId = await createUserWithAttributes(user, { clubId: [clubId] });
  await assignRealmRole(kcUserId, CLUB_ADMINISTRATOR_ROLE);
  return { clubId, user, kcUserId };
}

// NOTE: the dynamic `provisionExtraClubAdmin` / `disposeClubAdmin` second-admin
// path (added in J-2 T-22 for the motor /airmovements test) was REMOVED in T-24.
// A second interactive club-A admin that relies on JIT-on-first-login to
// materialise its `t_user` loses the `ux_user_username_lower_alive` race (the
// race-recovery re-reads by SUB, not username, and returns empty), so it stayed
// tenant-less. The motor test now logs in the PRE-SEEDED STABLE `clubadmin4`
// (realm-export.json + V29 `t_user`) via `loginAsSeededMotorClubadmin` below,
// resolving its tenant deterministically with zero JIT race.

/**
 * Provision the two-club fixture. Club A reuses the Flyway seed; club B is
 * created live. Returns each club's CLUB_ADMINISTRATOR login handle plus a
 * `dispose()` that removes the KC admin users.
 *
 * `scope` is a short spec token (default `tc`) that disambiguates the admin
 * usernames so two specs both driving this fixture in the SAME `playwright
 * test` invocation (J-0 Locations + J-1 Aircraft) provision DISJOINT admins —
 * `ux_user_username_lower_alive` rejects a second alive copy of a username.
 * A per-call random nonce (folded into the username by `adminUser`) additionally
 * keeps a Playwright retry from re-inserting a still-alive username.
 *
 * Note club B itself is shared across callers (its slug is run-stable and
 * `createClubB` recovers the existing row on the 409) — that is fine: the two
 * specs only need a DISTINCT-from-A club B, not a private one, and each gets its
 * OWN admin identity bound to it.
 */
export async function provisionTwoClubs(
  browser: Browser,
  baseURL: string,
  scope = 'tc',
): Promise<TwoClubFixture> {
  const clubBId = await createClubB(browser, baseURL);

  const nonce = randomUUID().replace(/-/g, '').slice(0, 8);
  const clubA = await provisionClubAdmin(SEED_CLUB_A_ID, 'club-a-admin', scope, nonce);
  const clubB = await provisionClubAdmin(clubBId, 'club-b-admin', scope, nonce);

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

/**
 * The PRE-SEEDED STABLE motor principal (`clubadmin4`) — a verified-email realm
 * user (realm-export.json) bound to seed-club-1 (clubId=club-1) with a seeded
 * `t_user` row (V29__dev_user_seed_clubadmin4.sql, keycloak_sub
 * c1ab4d40-0000-4000-8000-000000000004).
 *
 * Used by the clean-seed MOTOR `/airmovements` e2e as a SECOND club-A principal
 * that resolves its tenant DETERMINISTICALLY (PreTenantUserLookup hits the
 * seeded `t_user`) with ZERO JIT race — sidestepping the
 * `ux_user_username_lower_alive` contention that left the prior dynamic
 * `provisionExtraClubAdmin` motor admin tenant-less (J-2 T-22/T-24). It is bound
 * to the SAME club as `provisionTwoClubs(...).clubA` (both seed-club-1), so the
 * masterdata seeded once in the spec's `beforeAll` is visible to it. Mirrors the
 * proven-green migration principal `clubadmin3` (V28).
 */
export const SEEDED_MOTOR_CLUBADMIN = {
  username: 'clubadmin4',
  password: 'clubadmin4-dev-2026!',
} as const;

/**
 * Log the pre-seeded {@link SEEDED_MOTOR_CLUBADMIN} (`clubadmin4`) in through the
 * SPA + Keycloak login form by USERNAME (mirrors `loginAsMigratedAdmin`'s
 * username login of the seeded `clubadmin3`). No provisioning, no JIT — the
 * seeded `t_user` resolves the tenant on first request.
 *
 * SESSION ISOLATION (J-2 T-25). `provisionTwoClubs` (run in the spec's
 * `beforeAll`) drives an SPA login as the seeded tenant-less `sysadmin` to mint
 * the club-create bearer (`captureSysadminBearer`); that login leaves a LIVE
 * Keycloak SSO session (the `KEYCLOAK_IDENTITY` cookie on the KC origin) which
 * the motor test's context then carries. Without clearing it, `goto('/')` +
 * click sign-in lands on KC, KC auto-resumes the still-valid sysadmin SSO
 * session (no login form), and the SPA boots holding SYSADMIN's token — so the
 * subsequent tenant-scoped `GET /api/v1/flights` fires as the tenant-less
 * sysadmin (backend logged `user-lookup miss sub=f1558768…`) and never 2xx's,
 * tripping the fail-fast guard. clubadmin4 itself resolves fine (the PRODUCT is
 * correct) — this is harness-only SSO bleed.
 *
 * Fix: clear the context's cookies (the KC SSO cookie among them — same
 * belt-and-braces as `login.spec.ts`'s logout→cold-re-login) BEFORE starting the
 * login, so KC cannot auto-resume sysadmin and a full FRESH credential login as
 * clubadmin4 happens. We assert the KC username field is actually present before
 * filling so any future SSO-bypass regression fails fast with a clear cause
 * rather than `fillKcLogin` blind-filling a short-circuited page. Other login
 * helpers (`loginAsClubAdmin`, `loginAsMigratedAdmin`) are UNCHANGED: each runs
 * in its OWN fresh `browser.newContext()` and is the FIRST login in that context,
 * so they never inherit the sysadmin SSO session and need no clear.
 */
export async function loginAsSeededMotorClubadmin(page: Page): Promise<void> {
  // Drop any leaked KC SSO cookie so this context cannot silently auto-resume
  // the sysadmin session minted by `captureSysadminBearer` in `beforeAll`.
  await page.context().clearCookies();

  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  // KC must present the login form — proves SSO did not bypass it (so we
  // authenticate as clubadmin4, not the leaked sysadmin session).
  await expect(page.locator('#username')).toBeVisible();

  // WAIT FOR TENANT RESOLUTION TO SETTLE before the caller navigates to a
  // tenant-guarded page (J-2 T-28). `clubadmin4` is a realm-export user whose
  // tenant is resolved by the SERVER (`PreTenantUserLookup` → the V29 seeded
  // `t_user`); the authoritative `currentClubId` is patched onto SessionStore
  // by `loadMe()` from the `GET /api/v1/me` response, NOT synchronously off the
  // login claim. The OIDC `applyClaimsToSession` fires `login()` + `loadMe()` on
  // the landing redirect; the run-26904261451 trace proved `/api/v1/me` returns
  // 200 here. Until that response lands, `tenantRequiredGuard` sees
  // `currentClubId() === null` and bounces a `/airmovements` navigation back to
  // `/start` (and a concurrent silent-renew can leave `authGuard` in its
  // `isLoadingSession() → return false` defer branch, cancelling the nav
  // outright) — so the prior in-app click to `/airmovements` never stuck and the
  // motor create never reached the form (POST never fired, 60s timeout). Arm the
  // `/me` waiter BEFORE submitting the login form (the landing redirect fires
  // `/me` immediately on auth — registering after the redirect would race the
  // response), then block on the round-trip so the session is tenant-resolved
  // and settled before the caller drives the nav.
  const meResolved = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/v1/me' && r.status() === 200,
    { timeout: 30_000 },
  );
  await fillKcLogin(page, SEEDED_MOTOR_CLUBADMIN.username, SEEDED_MOTOR_CLUBADMIN.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
  await meResolved;
}
