import { type APIRequestContext, type Browser, type Page, expect } from '@playwright/test';

import { enterViaNav } from '../../_helpers/nav';

import { ensureSharedMigrationBundle } from './fan-out-parity-fixture';
import { fillKcLogin } from './kc-form';
import { findUsersByUsernameSearch, makeMigratedAdminLoginable } from './keycloak-admin';
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
// reservation as the MIGRATED club's admin, NOT as clubadmin4 / seed-club-1.
//
// The T-07 legacy fixture (`flsserver/database/FLSTest/3 insert/_test-fixture.sql`
// §10c) stamps the seeded reservation on the legacy TestClub
// (`0FA7B76F-47BA-4138-8F96-671400FD7C83`). T-18 ASSUMED `CLUB` migrates keeping
// that legacy UUID — WRONG (J-5 T-27). The migration RECONCILES each CLUB onto a
// provisioning-MINTED `t_club` with a FRESH UUID (`EntityStreamIngestor`: "CLUB
// reconciles onto the provisioning-minted t_club … rewrite the row's own legacy
// id to the provisioned id"), and the reservation's `operating_club_id` is
// FK-rewritten to that same new UUID via the seed legacy-id map. So the migrated
// reservation lands on the PROVISIONED club UUID, and the provisioned admin
// username is `migrated-admin+<NEW-UUID>@…` — never `…+0fa7b76f-…`. Hardcoding
// the legacy UUID can NEVER find the admin.
//
// Fix (the J-0c ownership-detection pattern, applied to reservations): the J-0c
// fan-out spec ingests the SAME real bundle earlier in this Playwright
// invocation, provisioning ONE Keycloak admin
// (`migrated-admin+<clubId>@migrated.alpenflight.local`) per migrated club. We
// ENUMERATE every provisioned migrated admin, make each loginable + capture its
// `@TenantId`-scoped Bearer, page its reservations, and keep the ONE whose
// tenant carries the unique fixture remark — i.e. the tenant the migrated legacy
// TestClub reconciled onto. That admin reads the migrated row; ZERO matches is a
// hard failure (round-trip regressed), never a silent skip.
// ===========================================================================

/** The provisioned migrated-admin username namespace (KC `search` infix). */
const MIGRATED_ADMIN_USERNAME_INFIX = 'migrated-admin+';

/** The reservations paged-read endpoint. */
const RESERVATIONS_PATH = '/api/v1/aircraft-reservations';

/** The unique remark the T-07 legacy fixture (§10c) stamps on the seeded reservation. */
export const MIGRATED_RESERVATION_REMARK = 'Cross-tenant timed reservation (fixture)';

/** The migrated reservation's type name (§10b — `Schulung`). */
export const MIGRATED_RESERVATION_TYPE_NAME = 'Schulung';

/** A loginable migrated club admin (the migration-provisioned Keycloak identity). */
export interface MigratedClubAdmin {
  /** The PROVISIONED club UUID (NOT the legacy one — see the section header). */
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

/** A resolved migrated admin plus its captured `@TenantId`-scoped Bearer. */
export interface ResolvedMigratedAdmin {
  admin: MigratedClubAdmin;
  bearer: string;
}

/** Parse the provisioned clubId out of a `migrated-admin+<clubId>@…` username. */
function clubIdFromUsername(username: string): string | null {
  const m = /^migrated-admin\+([0-9a-f-]{36})@migrated\.alpenflight\.local$/i.exec(username);
  return m ? m[1]! : null;
}

/**
 * Worker-scoped memo of resolved migrated admins, keyed by the ownership remark
 * that identifies the tenant (J-27 T-08).
 *
 * The cold resolution (`enumerateMigratedTestClubAdmin`) does a full SPA Keycloak
 * login per migrated club candidate, serially, to find the tenant that carries a
 * given fixture row — ~6 OIDC logins against the real fanout. Four migrated-data
 * specs (`reservations`, `accounting-rules`, `delivery-creation-test`, `planning`)
 * all need the SAME migrated TestClub admin (the one carrying
 * `MIGRATED_RESERVATION_REMARK`), so re-enumerating per spec's `beforeAll` blew the
 * 45s hook budget. real-idp is `workers:1`, so module state is shared across all
 * specs in the run: memoize the resolution in this worker-scoped map and every
 * later spec awaits the cached result — ONE enumeration per distinct ownership
 * key. Keyed (not a single singleton) so a future spec resolving a DIFFERENT
 * migrated admin by another remark gets its own enumeration, never collapsing two
 * distinct admins onto one. Mirrors `ensureSharedMigrationBundle`'s singleton.
 */
const resolvedAdminMemo = new Map<string, Promise<ResolvedMigratedAdmin>>();

/**
 * Decode a `Bearer <jwt>`'s `exp` (seconds since epoch) into a ms deadline, or
 * `0` when it can't be parsed — treating an unparseable token as already expired
 * so the memo re-logs rather than handing back a token of unknown validity.
 */
function bearerExpiresAtMs(bearer: string): number {
  const jwt = bearer.replace(/^Bearer\s+/i, '');
  const payload = jwt.split('.')[1];
  if (!payload) return 0;
  try {
    const json = Buffer.from(payload, 'base64url').toString('utf8');
    const exp = (JSON.parse(json) as { exp?: number }).exp;
    return typeof exp === 'number' ? exp * 1000 : 0;
  } catch {
    return 0;
  }
}

/** Re-login skew: refresh a cached bearer this long before its real expiry. */
const BEARER_REFRESH_SKEW_MS = 30_000;

/**
 * Resolve the migration-provisioned, loginable admin of the club the migrated
 * legacy reservation reconciled onto — discovered by OWNERSHIP, not by a
 * hardcoded UUID (J-5 T-27; the migrated CLUB gets a fresh provisioned UUID, so
 * the legacy `0fa7b76f-…` admin never exists). Memoized at worker scope so the
 * cold per-club-login enumeration runs ONCE per distinct ownership key, not per
 * consuming spec's `beforeAll` (J-27 T-08). The cached bearer is refreshed
 * (re-login, NO re-enumeration) when it nears expiry, so a long serial run never
 * hands back a dead token.
 *
 * The fanout's J-0c fan-out spec ingests the SAME real bundle earlier in this
 * Playwright invocation, so the admins already exist by the time this runs.
 */
export async function resolveMigratedTestClubAdmin(
  browser: Browser,
  baseURL: string,
): Promise<ResolvedMigratedAdmin> {
  // Keyed by the ownership remark — the criterion that distinguishes WHICH
  // migrated tenant this is. All four current consumers want the reservation
  // owner, so they share one slot; a future caller keyed on a different remark
  // resolves independently.
  const key = MIGRATED_RESERVATION_REMARK;
  const cached = resolvedAdminMemo.get(key);
  if (cached) {
    const resolved = await cached;
    if (bearerExpiresAtMs(resolved.bearer) - BEARER_REFRESH_SKEW_MS > Date.now()) {
      return resolved;
    }
    // The token aged out across the serial run — re-login the SAME (already
    // known) admin for a fresh tenant Bearer; no club re-enumeration.
    const bearer = await captureMigratedTestClubBearer(browser, baseURL, resolved.admin);
    const refreshed: ResolvedMigratedAdmin = { admin: resolved.admin, bearer };
    resolvedAdminMemo.set(key, Promise.resolve(refreshed));
    return refreshed;
  }

  const pending = enumerateMigratedTestClubAdmin(browser, baseURL);
  resolvedAdminMemo.set(key, pending);
  try {
    return await pending;
  } catch (err) {
    // Clear on failure so a later spec re-attempts rather than re-throwing a
    // stale rejected promise.
    resolvedAdminMemo.delete(key);
    throw err;
  }
}

/**
 * Cold resolution: enumerate every provisioned `migrated-admin+<clubId>@…`, make
 * each loginable, capture its tenant Bearer, page its reservations, and return
 * the one whose tenant carries the unique fixture remark
 * (`MIGRATED_RESERVATION_REMARK`). Fails loud if none does — that is a migration
 * round-trip regression, never a silently-skipped assertion. Called once per
 * worker per ownership key via `resolveMigratedTestClubAdmin`'s memo.
 */
async function enumerateMigratedTestClubAdmin(
  browser: Browser,
  baseURL: string,
): Promise<ResolvedMigratedAdmin> {
  // Order-INDEPENDENCE (J-8 T-17): provisioning the migrated admins is the J-0c
  // shared-bundle ingest, and Playwright's spec-sort does NOT honor CLI file
  // order under `fullyParallel` (+ `workers:1`) — `accounting-rules-parity`
  // sorts BEFORE `fan-out-migration-parity`, so this resolver used to run before
  // the bundle was ingested and threw at `beforeAll`. `ensureSharedMigrationBundle`
  // is a worker-scoped, idempotent, single-ingest memo of the SAME bundle the
  // fan-out spec ingests: the first migrated-data spec to reach it triggers the
  // one ingest, every other (incl. fan-out's own `beforeAll`) shares the cached
  // result. So whichever migrated block the spec-sort runs first, the admins
  // exist by the time we enumerate — no alphabetical luck, no sibling-race. The
  // single-use bundle uploadId is therefore POSTed exactly once.
  await ensureSharedMigrationBundle(browser, baseURL);

  const candidates = await findUsersByUsernameSearch(MIGRATED_ADMIN_USERNAME_INFIX);
  const migratedAdmins = candidates.filter((u) => clubIdFromUsername(u.username) !== null);
  if (migratedAdmins.length === 0) {
    throw new Error(
      `no migration-provisioned admin (${MIGRATED_ADMIN_USERNAME_INFIX}…) exists after the ` +
        `shared-bundle ingest — the J-0c CLUB provisioning regressed (the ingest produced no ` +
        `migrated club admins).`,
    );
  }

  const triedClubIds: string[] = [];
  for (const kcUser of migratedAdmins) {
    const clubId = clubIdFromUsername(kcUser.username)!;
    triedClubIds.push(clubId);
    const admin: MigratedClubAdmin = {
      clubId,
      username: kcUser.username,
      password: E2E_CANNED_PASSWORD,
      kcUserId: kcUser.id,
    };
    await makeMigratedAdminLoginable(kcUser.id, kcUser.username, E2E_CANNED_PASSWORD);
    const bearer = await captureMigratedTestClubBearer(browser, baseURL, admin);
    if (await tenantCarriesMigratedReservation(browser, baseURL, bearer)) {
      return { admin, bearer };
    }
  }

  throw new Error(
    `none of the ${migratedAdmins.length} provisioned migrated club(s) ` +
      `[${triedClubIds.join(', ')}] carries the migrated legacy reservation ` +
      `(remark "${MIGRATED_RESERVATION_REMARK}") — the T-07 legacy→export→migrate ` +
      `round-trip regressed (the reservation or its operating_club_id FK did not migrate).`,
  );
}

/**
 * `true` when the tenant resolved off `bearer` carries the unique migrated
 * reservation (its fixture remark). Uses the SAME paged-read API the spec
 * asserts on, so ownership is judged by the product's tenant-scoped read.
 */
async function tenantCarriesMigratedReservation(
  browser: Browser,
  baseURL: string,
  bearer: string,
): Promise<boolean> {
  const context = await browser.newContext({ baseURL });
  try {
    const res = await context.request.post(`${RESERVATIONS_PATH}/page/0/50`, {
      headers: { authorization: bearer, 'content-type': 'application/json' },
      data: { sorting: { start: 'asc' } },
    });
    if (!res.ok()) return false;
    const body = (await res.json()) as { items: { remarks?: string | null }[] };
    return body.items.some((r) => r.remarks === MIGRATED_RESERVATION_REMARK);
  } finally {
    await context.close();
  }
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
    // Reach /reservations via WARM in-app nav (land on the app shell, then a
    // router click), NOT a cold page.goto onto the data route: a full document
    // load there can land mid OIDC reboot/renew and ERR_ABORTED, stalling the
    // bearer capture this fixture's beforeAll callers depend on
    // ([[project_real_idp_goto_reboot_renew_stall]]). The read the nav click
    // triggers is what the interceptor stamps with the Bearer.
    await page.goto('/start?lang=en');
    await enterViaNav(page, '/reservations');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}
