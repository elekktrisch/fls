import { execFile } from 'node:child_process';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

import { type APIRequestContext, type Browser, type Page, expect } from '@playwright/test';

import { fillKcLogin } from './kc-form';
import { findUserByUsername, makeMigratedAdminLoginable } from './keycloak-admin';
import { E2E_CANNED_PASSWORD } from './test-user';

const execFileAsync = promisify(execFile);


const PRINCIPAL_USER = 'clubadmin3@example.com';
const PRINCIPAL_PASSWORD = 'clubadmin3-dev-2026!';

const SERVER_DIR = resolve(__dirname, '../../../../../server');
const GRADLEW = resolve(SERVER_DIR, 'gradlew');

export const MIGRATED_GLIDER_IMMAT = 'HB-3000';
export const MIGRATED_TOW_IMMAT = 'HB-TOW1';

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the flight fixture');
  }
  return id;
}

function useRealBundle(): boolean {
  return (process.env['J2_BUNDLE_SOURCE'] ?? 'synth').toLowerCase() === 'real';
}

function requiredEnvPath(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} not set — J2_BUNDLE_SOURCE=real requires the proof workflow to point at the ` +
        `legacy-exported artifact (the export + handshake steps must run before this spec).`,
    );
  }
  return value;
}

export interface MigratedClubAdmin {
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

export interface FlightParityFixture {
  gliderImmatriculation: string;
  freshnessToken: string;
  owner: MigratedClubAdmin;
}

interface HandshakeResponse {
  uploadId: string;
  publicKeyPem: string;
}

interface IngestResponse {
  deploymentId: string;
  clubIds: string[];
  primaryClubId: string;
}

interface SeederOutput {
  clubKey: string;
  crossTenantClubKey: string;
  freshnessToken: string;
  bundlePath: string;
}

interface ResolvedBundle {
  bundle: Buffer;
  uploadId: string;
  freshnessToken: string;
}

interface AircraftListItem {
  id: string;
  immatriculation: string;
}

interface FlightListItem {
  id: string;
  aircraftId: string;
  flightAircraftType: string;
}

interface FlightDetail {
  towFlightId?: string | null;
}

async function capturePrincipalBearer(browser: Browser, baseURL: string): Promise<string> {
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
    await fillKcLogin(page, PRINCIPAL_USER, PRINCIPAL_PASSWORD);
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
    await page.goto('/flights');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

async function mintHandshake(api: APIRequestContext, bearer: string): Promise<HandshakeResponse> {
  const res = await api.post('/api/v1/migrations/handshake', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: '',
  });
  if (!res.ok()) {
    throw new Error(`handshake failed (${res.status()}): ${await res.text()}`);
  }
  return (await res.json()) as HandshakeResponse;
}

async function buildBundleBytes(
  publicKeyPem: string,
  uploadId: string,
  freshnessToken: string,
  clubKey: string,
): Promise<Buffer> {
  const workDir = await mkdtemp(join(tmpdir(), 'j2-flight-'));
  const pemPath = join(workDir, 'handshake-public-key.pem');
  const outPath = join(workDir, 'bundle.b64');
  await writeFile(pemPath, publicKeyPem, 'utf8');

  const seederArgs = [pemPath, uploadId, freshnessToken, clubKey, outPath].join(' ');
  const { stdout } = await execFileAsync(
    GRADLEW,
    ['--quiet', 'seedFlightParityBundle', `-PseederArgs=${seederArgs}`],
    { cwd: SERVER_DIR, maxBuffer: 16 * 1024 * 1024 },
  );
  const line = stdout
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{') && l.endsWith('}'))
    .pop();
  if (!line) {
    throw new Error(`flight seeder produced no JSON result line; stdout was:\n${stdout}`);
  }
  const parsed = JSON.parse(line) as SeederOutput;
  const b64 = await readFile(parsed.bundlePath, 'utf8');
  return Buffer.from(b64, 'base64');
}

async function resolveSynthBundle(
  api: APIRequestContext,
  bearer: string,
  attempt: number,
): Promise<ResolvedBundle> {
  const attemptTag = `${Date.now().toString(36)}${attempt > 0 ? `r${attempt}` : ''}`;
  const freshnessToken = `J2${attemptTag.slice(-8).toUpperCase()}`;
  const clubKey = `J2A${runId().slice(0, 4).toUpperCase()}${attempt}`;
  const handshake = await mintHandshake(api, bearer);
  const bundle = await buildBundleBytes(
    handshake.publicKeyPem,
    handshake.uploadId,
    freshnessToken,
    clubKey,
  );
  return { bundle, uploadId: handshake.uploadId, freshnessToken };
}

async function resolveRealBundle(): Promise<ResolvedBundle> {
  const handshakeFile = requiredEnvPath('J2_REAL_HANDSHAKE_FILE');
  const bundleFile = requiredEnvPath('J2_REAL_BUNDLE_FILE');
  const freshnessToken = requiredEnvPath('J2_REAL_FRESHNESS_TOKEN');

  const handshakeRaw = await readFile(handshakeFile, 'utf8');
  const handshake = JSON.parse(handshakeRaw) as HandshakeResponse;
  if (!handshake.uploadId) {
    throw new Error(
      `J2_REAL_HANDSHAKE_FILE (${handshakeFile}) has no uploadId — the workflow must save the ` +
        `/api/v1/migrations/handshake response verbatim before running alpenflight-export.`,
    );
  }
  const bundle = await readFile(bundleFile);
  return { bundle, uploadId: handshake.uploadId, freshnessToken };
}

async function ingestBundle(
  api: APIRequestContext,
  bearer: string,
  uploadId: string,
  bundle: Buffer,
): Promise<IngestResponse> {
  const res = await api.post(`/api/v1/migrations/${uploadId}/bundle`, {
    headers: { authorization: bearer, 'content-type': 'application/octet-stream' },
    data: bundle,
  });
  if (!res.ok()) {
    throw new Error(`bundle ingest failed (${res.status()}): ${await res.text()}`);
  }
  return (await res.json()) as IngestResponse;
}

function migratedAdminUsername(clubId: string): string {
  return `migrated-admin+${clubId}@migrated.alpenflight.local`;
}

async function loginableAdmin(clubId: string): Promise<MigratedClubAdmin> {
  const username = migratedAdminUsername(clubId);
  const kcUser = await findUserByUsername(username);
  if (!kcUser) {
    throw new Error(
      `migration did not provision a Keycloak admin for club ${clubId} ` +
        `(expected username ${username}) — provisioning regression`,
    );
  }
  await makeMigratedAdminLoginable(kcUser.id, username, E2E_CANNED_PASSWORD);
  return { clubId, username, password: E2E_CANNED_PASSWORD, kcUserId: kcUser.id };
}

async function tenantOwnsMigratedPair(api: APIRequestContext, bearer: string): Promise<boolean> {
  const acRes = await api.get('/api/v1/aircraft', { headers: { authorization: bearer } });
  if (!acRes.ok()) {
    return false;
  }
  const aircraft = (await acRes.json()) as AircraftListItem[];
  const glider = aircraft.find((a) => a.immatriculation === MIGRATED_GLIDER_IMMAT);
  if (!glider) {
    return false;
  }

  const flRes = await api.get('/api/v1/flights', { headers: { authorization: bearer } });
  if (!flRes.ok()) {
    return false;
  }
  const body = (await flRes.json()) as { items: FlightListItem[] };
  const items = body.items ?? [];
  const gliderFlights = items.filter(
    (f) => f.flightAircraftType === 'GLIDER' && f.aircraftId === glider.id,
  );
  for (const candidate of gliderFlights) {
    const detRes = await api.get(`/api/v1/flights/${candidate.id}`, {
      headers: { authorization: bearer },
    });
    if (!detRes.ok()) {
      continue;
    }
    const detail = (await detRes.json()) as FlightDetail;
    const towFlightId = detail.towFlightId;
    if (!towFlightId) {
      continue;
    }
    const tow = items.find((t) => t.id === towFlightId);
    if (tow && tow.flightAircraftType === 'TOW') {
      return true;
    }
  }
  return false;
}

const PER_CLUB_LOGIN_BUDGET_MS = 12_000;

async function tryBearerForMigratedAdmin(
  browser: Browser,
  baseURL: string,
  admin: MigratedClubAdmin,
): Promise<string | undefined> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  const bearerPromise = page
    .waitForRequest(
      (req) =>
        new URL(req.url()).pathname === '/api/v1/flights' &&
        typeof req.headers()['authorization'] === 'string' &&
        /^Bearer /i.test(req.headers()['authorization']!),
      { timeout: PER_CLUB_LOGIN_BUDGET_MS },
    )
    .then(
      (req) => req.headers()['authorization'],
      () => undefined,
    );
  try {
    await loginAsMigratedAdmin(page, admin, PER_CLUB_LOGIN_BUDGET_MS);
    await page.goto('/flights');
    return await bearerPromise;
  } catch (err) {
    console.warn(
      `[J-2] club ${admin.clubId} admin did not reach the authed root within ` +
        `${PER_CLUB_LOGIN_BUDGET_MS}ms — treating as non-owner (${(err as Error).message})`,
    );
    return undefined;
  } finally {
    await bearerPromise;
    await context.close();
  }
}

export async function seedFlightParity(
  browser: Browser,
  api: APIRequestContext,
  baseURL: string,
  attempt = 0,
): Promise<FlightParityFixture> {
  const bearer = await capturePrincipalBearer(browser, baseURL);

  const { bundle, uploadId, freshnessToken } = useRealBundle()
    ? await resolveRealBundle()
    : await resolveSynthBundle(api, bearer, attempt);

  const ingest = await ingestBundle(api, bearer, uploadId, bundle);
  if (ingest.clubIds.length < 1) {
    throw new Error(`ingest provisioned ${ingest.clubIds.length} club(s) — need ≥1`);
  }

  let owner: MigratedClubAdmin | undefined;
  for (const clubId of ingest.clubIds) {
    try {
      const admin = await loginableAdmin(clubId);
      const tenantBearer = await tryBearerForMigratedAdmin(browser, baseURL, admin);
      if (tenantBearer && (await tenantOwnsMigratedPair(api, tenantBearer))) {
        owner = admin;
        break;
      }
    } catch (err) {
      console.warn(
        `[J-2] club ${clubId} ownership check failed — skipping (${(err as Error).message})`,
      );
    }
  }

  expect(
    owner,
    `exactly one migrated club must own the migrated glider+tow paired flight ` +
      `(glider on ${MIGRATED_GLIDER_IMMAT}); none of ${ingest.clubIds.length} provisioned clubs did`,
  ).toBeTruthy();

  return { gliderImmatriculation: MIGRATED_GLIDER_IMMAT, freshnessToken, owner: owner! };
}


const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const GLIDER_AIRCRAFT_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';
const MOTOR_AIRCRAFT_TYPE_ID = '019e2e15-2c00-7afc-8000-000000002afc';
const GRASS_RUNWAY_LOCATION_TYPE_ID = '019e2e15-2c00-72c9-8000-0000000032c9';
export const AEROTOW_START_TYPE_ID = '019e2e15-2c00-7fa1-8000-000000000fa1';

export interface FlightMasterdata {
  locationId: string;
  gliderFlightTypeId: string;
  pilotPersonId: string;
  towPilotPersonId: string;
  gliderAircraftId: string;
  gliderImmat: string;
  towAircraftId: string;
  towImmat: string;
  motorAircraftId: string;
  motorImmat: string;
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

export async function seedFlightMasterdata(
  api: APIRequestContext,
  bearer: string,
): Promise<FlightMasterdata> {
  const tag = shortTag();

  const location = await postJson(api, bearer, '/api/v1/locations', {
    locationName: `J2 Airfield ${tag}`,
    locationShortName: 'J2AF',
    countryId: CH_COUNTRY_ID,
    locationTypeId: GRASS_RUNWAY_LOCATION_TYPE_ID,
    isInboundRouteRequired: false,
    isOutboundRouteRequired: false,
    isFastEntryRecord: false,
  });

  const gliderFlightType = await postJson(api, bearer, '/api/v1/flight-types', {
    flightTypeName: `J2 Local ${tag}`,
    flightCode: `J2L${tag}`.slice(0, 30),
    isInstructorRequired: false,
    isObserverPilotOrInstructorRequired: false,
    isCheckFlight: false,
    isPassengerFlight: false,
    isSoloFlight: false,
    isForGliderFlights: true,
    isForTowFlights: true,
    isForMotorFlights: true,
    isFlightCostBalanceSelectable: false,
    isCouponNumberRequired: false,
    isForAircraftReservationType: false,
  });

  const pilot = await postJson(api, bearer, '/api/v1/persons', {
    firstname: 'J2',
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
  const towPilot = await postJson(api, bearer, '/api/v1/persons', {
    firstname: 'J2',
    lastname: `TowPilot ${tag}`,
    preferMailToBusinessMail: false,
    receiveOwnedAircraftStatisticReports: false,
    enableAddress: false,
    initialClubMembership: {
      isMotorPilot: true,
      isTowPilot: true,
      isGliderInstructor: false,
      isGliderPilot: false,
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

  const gliderImmat = `HB-2${tag.slice(-3)}`;
  const glider = await postJson(api, bearer, '/api/v1/aircraft', {
    aircraftTypeId: GLIDER_AIRCRAFT_TYPE_ID,
    immatriculation: gliderImmat,
    manufacturerName: 'Schleicher',
    aircraftModel: 'ASK 21',
    nrOfSeats: 2,
    isTowingOrWinchRequired: true,
    isTowingStartAllowed: true,
    isWinchStartAllowed: true,
    isTowingAircraft: false,
  });

  const towImmat = `HB-T${tag.slice(-3)}`;
  const tow = await postJson(api, bearer, '/api/v1/aircraft', {
    aircraftTypeId: MOTOR_AIRCRAFT_TYPE_ID,
    immatriculation: towImmat,
    manufacturerName: 'Robin',
    aircraftModel: 'DR400',
    nrOfSeats: 4,
    isTowingOrWinchRequired: false,
    isTowingStartAllowed: false,
    isWinchStartAllowed: false,
    isTowingAircraft: true,
  });

  const motorImmat = `HB-M${tag.slice(-3)}`;
  const motor = await postJson(api, bearer, '/api/v1/aircraft', {
    aircraftTypeId: MOTOR_AIRCRAFT_TYPE_ID,
    immatriculation: motorImmat,
    manufacturerName: 'Cessna',
    aircraftModel: 'C172',
    nrOfSeats: 4,
    isTowingOrWinchRequired: false,
    isTowingStartAllowed: false,
    isWinchStartAllowed: false,
    isTowingAircraft: false,
  });

  return {
    locationId: String(location['id']),
    gliderFlightTypeId: String(gliderFlightType['id']),
    pilotPersonId: String(pilot['id']),
    towPilotPersonId: String(towPilot['id']),
    gliderAircraftId: String(glider['id']),
    gliderImmat,
    towAircraftId: String(tow['id']),
    towImmat,
    motorAircraftId: String(motor['id']),
    motorImmat,
  };
}

export async function loginAsMigratedAdmin(
  page: Page,
  admin: MigratedClubAdmin,
  leaveRealmTimeoutMs = 30_000,
): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, admin.username, admin.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), {
    timeout: leaveRealmTimeoutMs,
  });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}
