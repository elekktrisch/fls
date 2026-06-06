import { type APIRequestContext, type Browser, type Page, expect } from '@playwright/test';

import { fillKcLogin } from './kc-form';
import { findUserByUsername, makeMigratedAdminLoginable } from './keycloak-admin';
import { E2E_CANNED_PASSWORD } from './test-user';

/**
 * J-5 reservation real-chain fixture — the clean-seed masterdata seeder + the
 * pre-seeded migration/club principal helpers for
 * `reservations-migration-parity.spec.ts`.
 *
 * The clean-seed real chain is the LOAD-BEARING J-5 proof (the first journey
 * whose proof is a REJECTION — overlap→409). It self-seeds the masterdata a
 * reservation references (an aircraft the operating club manages, a SECOND
 * aircraft owned by a DIFFERENT club for the cross-tenant-open case, a pilot
 * person with a club membership so it is pickable, and a location) through the
 * REAL create APIs as a pre-seeded CLUB_ADMINISTRATOR of seed-club-1, then the
 * spec exercises the reservation chain (create→list→scheduler, overlap→409,
 * duration→422, all-day, cross-tenant-open, edit/delete-frees) fully live.
 *
 * PRINCIPAL — `clubadmin4` (V29 dev-user seed + realm-export): a verified-email
 * CLUB_ADMINISTRATOR with its OWN `t_user` bound to seed-club-1
 * (`019e30c3-2c00-7001-8000-000000000001`). PreTenantUserLookup resolves its
 * tenant deterministically on auth (no JIT race), and it can mutate seed-club-1
 * masterdata + reservations. Disjoint from the J-0c/J-1/J-2 principals
 * (`clubadmin1/2/3`) so this spec co-locates cleanly in one Playwright
 * invocation.
 *
 * RESERVATION-TYPE — clean-seed default (J-5 T-17): the reservation type has NO
 * create API — `t_aircraft_reservation_type` is tenant-scoped and populated only
 * by migration (or the future masterdata screen). To let the FULL clean-seed UI
 * create flow run (form → type-picker), `V31__dev_reservation_type_seed.sql`
 * seeds ONE active default type ("Allgemein") for seed-club-1, so the form-
 * required `reservationTypeId` dropdown is non-empty on a clean realm. The
 * happy-path create test drives that real type through the UI `<af-select>`
 * (`fetchReservationTypeId` below reads the seeded id off the live
 * `/aircraft-reservation-types` listitems endpoint). The remaining mutation
 * cases (overlap-409, duration-422, all-day, cross-tenant, edit/delete-frees)
 * still drive the REST API directly (the type is OPTIONAL on the backend
 * request, so those error/edge probes need no type). The migrated-data half (a
 * real legacy reservation, with its migrated type) proves the type renders end
 * to end from migrated data too.
 */

/** Seeded `clubadmin4` (V29) bound to seed-club-1 — the J-5 clean-seed principal. */
export const PRINCIPAL_USER = 'clubadmin4@example.com';
export const PRINCIPAL_PASSWORD = 'clubadmin4-dev-2026!';

/** seed-club-1 (V5 walking skeleton) — clubadmin4's tenant (raw UUID). */
export const SEED_CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';

/** Canonical reference seeds (V2/V3 — the IDs the create requests reference). */
const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const GLIDER_AIRCRAFT_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';
const GRASS_RUNWAY_LOCATION_TYPE_ID = '019e2e15-2c00-72c9-8000-0000000032c9';

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the reservation fixture');
  }
  return id;
}

/**
 * Log the pre-seeded principal in through the SPA + real Keycloak and land on
 * the authed root. The SPA storageState is the session.
 */
export async function loginAsReservationAdmin(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, PRINCIPAL_USER, PRINCIPAL_PASSWORD);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}

/**
 * Drive the principal through the SPA login in a throwaway context and capture
 * the Bearer the OIDC interceptor attaches to its first `/reservations` read,
 * so the spec can issue direct REST mutations as the same identity.
 */
export async function captureReservationAdminBearer(
  browser: Browser,
  baseURL: string,
): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerPromise = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await loginAsReservationAdmin(page);
    // The reservations list issues POST /api/v1/aircraft-reservations/page/…,
    // which the interceptor stamps with the Bearer.
    await page.goto('/reservations');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

async function postJson(
  api: APIRequestContext,
  bearer: string,
  path: string,
  data: unknown,
): Promise<Record<string, unknown>> {
  const res = await api.post(path, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: data as object,
  });
  if (res.status() !== 201 && res.status() !== 200) {
    throw new Error(`POST ${path} failed (${res.status()}): ${await res.text()}`);
  }
  return (await res.json()) as Record<string, unknown>;
}

function shortTag(): string {
  return `${runId().slice(0, 3)}${Date.now().toString(36).slice(-4)}`.toUpperCase();
}

/** The masterdata a clean-seed reservation references (all SPA-prefixed ids). */
export interface ReservationMasterdata {
  /** `ac-<uuid>` aircraft seed-club-1 MANAGES (the in-tenant aircraft) + immat. */
  managedAircraftId: string;
  managedImmat: string;
  /**
   * `ac-<uuid>` aircraft a DIFFERENT club manages — the legacy-open cross-tenant
   * case reserves THIS one (the picker offers it; the reservation is stamped with
   * the operating club). + immat.
   */
  foreignAircraftId: string;
  foreignImmat: string;
  /** `pn-<uuid>` pilot person (with a seed-club-1 membership → pickable). */
  pilotPersonId: string;
  /** `loc-<uuid>` location. */
  locationId: string;
}

/**
 * Seed (as `clubadmin4`, through the REAL create APIs — no mocking) the
 * masterdata the clean-seed reservation chain references. The cross-tenant
 * aircraft is created by a DIFFERENT club's admin so it is genuinely
 * foreign-managed; the reservation picker still offers it (no tenant gate on the
 * aircraft picker — legacy-open parity). All ids are run-tagged so a retry's
 * re-seed never collides on a unique index.
 *
 * @param foreignBearer a Bearer for a club admin of a DIFFERENT club than
 *   seed-club-1, used to create the cross-tenant aircraft under its own
 *   managing club (so the cross-tenant-open AC is real, not same-tenant).
 */
export async function seedReservationMasterdata(
  api: APIRequestContext,
  bearer: string,
  foreignBearer: string,
): Promise<ReservationMasterdata> {
  const tag = shortTag();

  const location = await postJson(api, bearer, '/api/v1/locations', {
    locationName: `J5 Airfield ${tag}`,
    locationShortName: 'J5AF',
    countryId: CH_COUNTRY_ID,
    locationTypeId: GRASS_RUNWAY_LOCATION_TYPE_ID,
    isInboundRouteRequired: false,
    isOutboundRouteRequired: false,
    isFastEntryRecord: false,
  });

  // A pilot person needs a membership in the caller's tenant to be pickable
  // (JpaPersonRepository pivots FROM PersonClub — J-2 T-20). Seed one in the
  // same create transaction so it appears in /persons.
  const pilot = await postJson(api, bearer, '/api/v1/persons', {
    firstname: 'J5',
    lastname: `Pilot ${tag}`,
    preferMailToBusinessMail: false,
    receiveOwnedAircraftStatisticReports: false,
    enableAddress: false,
    initialClubMembership: {
      isMotorPilot: false,
      isTowPilot: false,
      isGliderInstructor: false,
      isGliderPilot: true,
      isGliderTrainee: false,
      isPassenger: false,
      isWinchOperator: false,
      isMotorInstructor: false,
      receiveFlightReports: false,
      receiveAircraftReservationNotifications: false,
      receivePlanningDayRoleReminder: false,
      isActive: true,
    },
  });

  const managedImmat = `HB-R${tag.slice(-3)}`;
  const managed = await postJson(api, bearer, '/api/v1/aircraft', {
    aircraftTypeId: GLIDER_AIRCRAFT_TYPE_ID,
    immatriculation: managedImmat,
    manufacturerName: 'Schleicher',
    aircraftModel: 'ASK 21',
    nrOfSeats: 2,
    isTowingOrWinchRequired: false,
    isTowingStartAllowed: false,
    isWinchStartAllowed: false,
    isTowingAircraft: false,
  });

  // The cross-tenant aircraft — created by the FOREIGN club's admin so it is
  // genuinely managed by a different club. The reservation picker still offers
  // it (cross-tenant catalog, no charter gate — J-5 legacy-open AC).
  const foreignImmat = `HB-X${tag.slice(-3)}`;
  const foreign = await postJson(api, foreignBearer, '/api/v1/aircraft', {
    aircraftTypeId: GLIDER_AIRCRAFT_TYPE_ID,
    immatriculation: foreignImmat,
    manufacturerName: 'Schempp-Hirth',
    aircraftModel: 'Discus',
    nrOfSeats: 1,
    isTowingOrWinchRequired: false,
    isTowingStartAllowed: false,
    isWinchStartAllowed: false,
    isTowingAircraft: false,
  });

  return {
    managedAircraftId: String(managed['id']),
    managedImmat,
    foreignAircraftId: String(foreign['id']),
    foreignImmat,
    pilotPersonId: String(pilot['id']),
    locationId: String(location['id']),
  };
}

/**
 * Read the clean-seed default reservation-type id off the live
 * `/aircraft-reservation-types` listitems endpoint (the same read the form's
 * type dropdown issues). `V31__dev_reservation_type_seed.sql` seeds exactly one
 * active "Allgemein" type for seed-club-1, so the clean-seed UI create flow can
 * select a REAL type from the picker. Fails loud if the seed is absent rather
 * than silently falling back to a typeless create (the T-17 done-bar is that the
 * type-picker flow runs).
 */
export async function fetchReservationTypeId(
  api: APIRequestContext,
  bearer: string,
): Promise<string> {
  const res = await api.get('/api/v1/aircraft-reservation-types', {
    headers: { authorization: bearer },
  });
  if (!res.ok()) {
    throw new Error(`list reservation types failed (${res.status()}): ${await res.text()}`);
  }
  const types = (await res.json()) as { id: string; name: string }[];
  const seeded = types.find((t) => t.name === 'Allgemein') ?? types[0];
  if (!seeded?.id) {
    throw new Error(
      'no reservation type on the clean-seed club — V31__dev_reservation_type_seed.sql ' +
        'must seed a default type for seed-club-1 (T-17)',
    );
  }
  return seeded.id;
}

/** `true` when the migrated-bundle real-export path is active (the fanout). */
export function useRealBundle(): boolean {
  return (process.env['J5_BUNDLE_SOURCE'] ?? 'synth').toLowerCase() === 'real';
}

// ===========================================================================
// MIGRATED-TENANT read (the migrated-data block) — read the migrated legacy
// reservation as the MIGRATED legacy TestClub's admin, NOT as clubadmin4 /
// seed-club-1.
//
// The T-07 legacy fixture (`flsserver/database/FLSTest/3 insert/_test-fixture.sql`
// §10c) stamps the seeded reservation on the legacy TestClub
// (`0FA7B76F-47BA-4138-8F96-671400FD7C83`). `CLUB` migrates FULL_PORT
// (non-fanout) so it keeps its legacy UUID — the migrated reservation lands on
// THAT tenant, NOT on seed-club-1. The paged read is `@TenantId`-scoped on
// `operating_club_id`, so clubadmin4 (seed-club-1) never sees it: the old
// `totalRows >= 1` assertion passed on unrelated seed-club-1 residue, not the
// migrated row. To prove the legacy→export→migrate→render round-trip we MUST
// read through the migrated TestClub's tenant.
//
// Mirrors the J-0c/J-1 migrated-read pattern: the migration provisions a
// Keycloak admin `migrated-admin+<clubId>@migrated.alpenflight.local` per
// migrated club (`DeploymentProvisioningService#migratedAdminUsername` —
// clubId is the lowercase `UUID.toString()`). We resolve THAT admin for the
// FULL_PORT TestClub UUID, make it loginable (test-only password + cleared
// required-actions — same as `fan-out-parity-fixture.loginableAdmin`), and log
// it in so the `@TenantId`-scoped reservation read sees the migrated row.
// ===========================================================================

/**
 * The migrated legacy TestClub's tenant UUID. `CLUB` migrates FULL_PORT
 * (non-fanout), so the legacy TestClub keeps its legacy UUID
 * (`0FA7B76F-47BA-4138-8F96-671400FD7C83` in the MSSQL fixture); the migration
 * stamps the provisioned tenant + the migrated-admin username with the lowercase
 * `UUID.toString()` form (see `DeploymentProvisioningService`).
 */
export const MIGRATED_TEST_CLUB_ID = '0fa7b76f-47ba-4138-8f96-671400fd7c83';

/** The unique remark the T-07 legacy fixture (§10c) stamps on the seeded reservation. */
export const MIGRATED_RESERVATION_REMARK = 'Cross-tenant timed reservation (fixture)';

/** The migrated reservation's type name (§10b — `Schulung`). */
export const MIGRATED_RESERVATION_TYPE_NAME = 'Schulung';

/** A loginable migrated club admin (the migration-provisioned Keycloak identity). */
export interface MigratedClubAdmin {
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

function migratedAdminUsername(clubId: string): string {
  return `migrated-admin+${clubId}@migrated.alpenflight.local`;
}

/**
 * Resolve the migration-provisioned, loginable admin of the migrated legacy
 * TestClub (the tenant the migrated reservation is stamped on). The fanout's
 * J-0c fan-out spec ingests the SAME real bundle earlier in this Playwright
 * invocation, which provisions this admin; here we set a known password + clear
 * its required-actions (test-only, namespace-guarded) so the spec can log it in.
 * Fails loud if the migration did not provision it — that is a provisioning /
 * FULL_PORT regression, never a silently-skipped assertion.
 */
export async function resolveMigratedTestClubAdmin(): Promise<MigratedClubAdmin> {
  const username = migratedAdminUsername(MIGRATED_TEST_CLUB_ID);
  const kcUser = await findUserByUsername(username);
  if (!kcUser) {
    throw new Error(
      `migration did not provision a Keycloak admin for the migrated TestClub ` +
        `${MIGRATED_TEST_CLUB_ID} (expected username ${username}) — the FULL_PORT CLUB ` +
        `migration / provisioning regressed, or the real bundle was not ingested.`,
    );
  }
  await makeMigratedAdminLoginable(kcUser.id, username, E2E_CANNED_PASSWORD);
  return {
    clubId: MIGRATED_TEST_CLUB_ID,
    username,
    password: E2E_CANNED_PASSWORD,
    kcUserId: kcUser.id,
  };
}

/**
 * Log the migrated TestClub admin in through the SPA + real Keycloak so its
 * `@TenantId`-scoped reservation reads see the migrated row. Mirrors
 * `loginAsReservationAdmin` but for the migration-provisioned identity.
 */
export async function loginAsMigratedTestClubAdmin(
  page: Page,
  admin: MigratedClubAdmin,
): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, admin.username, admin.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}

/**
 * Drive the migrated TestClub admin through the SPA login in a throwaway context
 * and capture the tenant-scoped Bearer the OIDC interceptor attaches to its
 * first `/reservations` read, so the spec can issue the direct paged-read as the
 * migrated tenant.
 */
export async function captureMigratedTestClubBearer(
  browser: Browser,
  baseURL: string,
  admin: MigratedClubAdmin,
): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerPromise = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await loginAsMigratedTestClubAdmin(page, admin);
    await page.goto('/reservations');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}
