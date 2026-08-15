import {
  type APIRequestContext,
  type Browser,
  type Page,
  type TestInfo,
  expect,
} from '@playwright/test';

import { enterViaNav } from '../../_helpers/nav';

import { ensureSharedMigrationBundle } from './fan-out-parity-fixture';
import { fillKcLogin } from './kc-form';
import { findUsersByUsernameSearch, makeMigratedAdminLoginable } from './keycloak-admin';
import { E2E_CANNED_PASSWORD } from './test-user';

export const PRINCIPAL_USER = 'clubadmin4@example.com';
export const PRINCIPAL_PASSWORD = 'clubadmin4-dev-2026!';

export const SEED_CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';

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

export async function loginAsReservationAdmin(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, PRINCIPAL_USER, PRINCIPAL_PASSWORD);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}

export async function captureReservationAdminBearer(
  browser: Browser,
  baseURL: string,
): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerFromFirstAuthorizedApiCall = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await loginAsReservationAdmin(page);
    await page.goto('/reservations');
    const req = await bearerFromFirstAuthorizedApiCall;
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

export interface ReservationMasterdata {
  managedAircraftId: string;
  managedImmat: string;
  foreignAircraftId: string;
  foreignImmat: string;
  pilotPersonId: string;
  locationId: string;
}

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

  const pilotWithClubMembership = await postJson(api, bearer, '/api/v1/persons', {
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
    pilotPersonId: String(pilotWithClubMembership['id']),
    locationId: String(location['id']),
  };
}

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

export function useRealBundle(): boolean {
  return (process.env['J5_BUNDLE_SOURCE'] ?? 'synth').toLowerCase() === 'real';
}

const MIGRATED_ADMIN_USERNAME_INFIX = 'migrated-admin+';

const RESERVATIONS_PATH = '/api/v1/aircraft-reservations';

export const MIGRATED_RESERVATION_REMARK = 'Cross-tenant timed reservation (fixture)';

export const MIGRATED_RESERVATION_TYPE_NAME = 'Schulung';

export interface MigratedClubAdmin {
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

export interface ResolvedMigratedAdmin {
  admin: MigratedClubAdmin;
  bearer: string;
}

function clubIdFromUsername(username: string): string | null {
  const m = /^migrated-admin\+([0-9a-f-]{36})@migrated\.alpenflight\.local$/i.exec(username);
  return m ? m[1]! : null;
}

const resolvedAdminMemoByOwnershipKey = new Map<string, Promise<ResolvedMigratedAdmin>>();

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

const BEARER_REFRESH_SKEW_MS = 30_000;

const COLD_RESOLUTION_HOOK_BUDGET_MS = 180_000;

export async function resolveMigratedTestClubAdmin(
  browser: Browser,
  baseURL: string,
  testInfo?: TestInfo,
): Promise<ResolvedMigratedAdmin> {
  testInfo?.setTimeout(COLD_RESOLUTION_HOOK_BUDGET_MS);
  const ownershipKey = MIGRATED_RESERVATION_REMARK;
  const cached = resolvedAdminMemoByOwnershipKey.get(ownershipKey);
  if (cached) {
    const resolved = await cached;
    if (bearerExpiresAtMs(resolved.bearer) - BEARER_REFRESH_SKEW_MS > Date.now()) {
      return resolved;
    }
    const bearer = await captureMigratedTestClubBearer(browser, baseURL, resolved.admin);
    const refreshed: ResolvedMigratedAdmin = { admin: resolved.admin, bearer };
    resolvedAdminMemoByOwnershipKey.set(ownershipKey, Promise.resolve(refreshed));
    return refreshed;
  }

  const pending = enumerateMigratedTestClubAdmin(browser, baseURL);
  resolvedAdminMemoByOwnershipKey.set(ownershipKey, pending);
  try {
    return await pending;
  } catch (err) {
    resolvedAdminMemoByOwnershipKey.delete(ownershipKey);
    throw err;
  }
}

async function enumerateMigratedTestClubAdmin(
  browser: Browser,
  baseURL: string,
): Promise<ResolvedMigratedAdmin> {
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

async function warmNavToReservations(page: Page): Promise<void> {
  await page.goto('/start?lang=en');
  await enterViaNav(page, '/reservations');
}

export async function captureMigratedTestClubBearer(
  browser: Browser,
  baseURL: string,
  admin: MigratedClubAdmin,
): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerFromFirstAuthorizedApiCall = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await loginAsMigratedTestClubAdmin(page, admin);
    await warmNavToReservations(page);
    const req = await bearerFromFirstAuthorizedApiCall;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}
