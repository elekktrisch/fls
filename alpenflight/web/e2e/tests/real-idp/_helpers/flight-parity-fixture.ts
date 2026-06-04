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

/**
 * J-2 T-08/T-09 flight migration parity fixture — the migrated-data half of the
 * Flight real chain. Mirrors the J-1 `aircraft-parity-fixture.ts`: it ingests a
 * migrated Flight + FlightCrew bundle through the REAL
 * `POST /api/v1/migrations/{id}/bundle` endpoint (never a DB INSERT) so the
 * migration ingest + Keycloak club-admin provisioning run LIVE, then resolves
 * the loginable migrated admin of the club that owns the migrated glider flight.
 *
 * TWO bundle fidelities, ONE downstream path (identical to J-1):
 *   - **Synth (default — fast inner loop, no legacy stack).** Mint a handshake
 *     here, shell out to the Gradle `seedFlightParityBundle` task (the only Java
 *     seam — the ALPF bundle envelope is built in Java by the same factory the
 *     server round-trip ITs use). It emits the encrypted Flight bundle as base64
 *     to a temp file. The synth bundle declares TWO clubs in one deployment: the
 *     owning club (glider+tow paired flight, a MOTOR flight, a `DeliveryBooked`
 *     read-only flight) + a cross-tenant club (one flight, for the 404).
 *   - **Real (`J2_BUNDLE_SOURCE=real` — full chain).** Reuse the workflow's
 *     handshake JSON (`J2_REAL_HANDSHAKE_FILE`) + the `alpenflight-export` `.enc`
 *     bundle (`J2_REAL_BUNDLE_FILE`) + the legacy-stamped freshness token
 *     (`J2_REAL_FRESHNESS_TOKEN`). The real export is the only run that validates
 *     the Flight producer SELECT against the real MSSQL schema (J-1 T-16 class).
 *
 * Shared tail (both modes): log in the Flyway-seeded `clubadmin3` migration
 * principal (real KC, verified-email + V28 `t_user`), capture its Bearer, POST
 * the bundle → the ingest provisions one Keycloak club-admin per migrated club.
 * The owning club is identified by OWNERSHIP: each provisioned admin queries its
 * own `/api/v1/flights`; exactly the one carrying a GLIDER flight that links to a
 * distinct TOW flight (the migrated paired pair) is the owner.
 *
 * DISTINCT migration principal (J-2 T-18, mirroring J-1 T-17): this spec uses
 * its OWN `clubadmin3`, disjoint from J-0c's `clubadmin1` and J-1's `clubadmin2`.
 * The migration ingest provisions a non-terminal Deployment OWNED BY the
 * principal's Keycloak sub (DeploymentProvisioningService#provision →
 * findActiveByOwner, the `ux_deployment_owner_active` gate). All THREE real-idp
 * proof specs (J-0c Locations, J-1 Aircraft, J-2 Flights) now run in ONE
 * `playwright test` invocation (ci.yml — one proof video covers the journey
 * set). The earlier assumption that reusing `clubadmin2` was safe (serial
 * within-project execution + fresh uploadId per attempt) was WRONG: the active
 * Deployment is keyed on the principal's sub, NOT the upload, so the second
 * spec sharing `clubadmin2` 409'd DEPLOYMENT_EXISTS on the first's still-active
 * Deployment (plus an `ux_user_username_lower_alive` dup on the provisioned-admin
 * path). A per-spec principal gives each a disjoint deployment owner so
 * `findActiveByOwner` never collides. (Realm-export carries `clubadmin3` as a
 * third verified-email user; V28 seeds its `t_user` row.)
 */

/** Seeded `clubadmin3` (V28 dev user seed + realm-export). The J-2 migration principal — disjoint from J-0c's `clubadmin1` and J-1's `clubadmin2` (T-18). */
const PRINCIPAL_USER = 'clubadmin3@example.com';
const PRINCIPAL_PASSWORD = 'clubadmin3-dev-2026!';

/**
 * Repo paths: this file is alpenflight/web/e2e/tests/real-idp/_helpers/, so five
 * `..` reach `alpenflight/`. The server is its own Gradle root project; its
 * wrapper + the seeder tasks live under `alpenflight/server/`.
 */
const SERVER_DIR = resolve(__dirname, '../../../../../server');
const GRADLEW = resolve(SERVER_DIR, 'gradlew');

/**
 * The migrated glider aircraft's immatriculation — fixed in the synth seeder
 * (`FlightParityBundleSeeder` writes `HB-3000` for the owner club's glider, with
 * the linked tow on `HB-TOW1`). The owning club's `/flights` list resolves the
 * glider row's immat via the AircraftStore lookup, so the spec asserts on this.
 */
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
  /** Raw provisioned club UUID — matches the `clubId` claim + admin username tag. */
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

export interface FlightParityFixture {
  /** The migrated glider aircraft's immatriculation (rendered on the owning club's list row). */
  gliderImmatriculation: string;
  /** The token stamped onto the migrated glider flight's comment (proves data flowed end to end). */
  freshnessToken: string;
  /** The migrated admin of the club that owns the migrated glider+tow paired flight. */
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

/** `GET /api/v1/aircraft` list-item projection (the SPA's generated `AircraftListItem`). */
interface AircraftListItem {
  id: string;
  immatriculation: string;
}

/**
 * `GET /api/v1/flights` list-item projection (the SPA's generated `FlightListItem`).
 * NB: the list projection carries NO `towFlightId` — the tow self-FK link is a
 * DETAIL-only field (`FlightDetail`, `GET /api/v1/flights/{id}`), exactly as the
 * migrated-render spec reads it. Owner-detection resolves the link via the detail
 * endpoint, not off the list row (J-2 T-19).
 */
interface FlightListItem {
  id: string;
  aircraftId: string;
  flightAircraftType: string;
}

/** `GET /api/v1/flights/{id}` detail projection — the only place `towFlightId` lands. */
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
    // The flights list is the principal's authed read surface; navigating to it
    // guarantees a /api/v1/* call fires so the interceptor attaches a Bearer.
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

/**
 * Invoke the Gradle `seedFlightParityBundle` task to build the encrypted Flight
 * bundle. Returns the raw bundle bytes (the task base64-encodes to a temp file;
 * we decode here). Args (positional): `<pem> <uploadId> <freshnessToken> <clubKey> <out>`.
 */
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
  // Attempt-scoped suffix so a Playwright retry never reuses the prior run's
  // uploadId / club key: a failed ingest seals its upload FAILED (a re-POST
  // 409s BUNDLE_PRIOR_RUN_FAILED).
  const attemptTag = `${Date.now().toString(36)}${attempt > 0 ? `r${attempt}` : ''}`;
  // The freshness token stamps the migrated glider flight's comment (proves data
  // flowed end to end); keep it short + alphanumeric for the seeder arg split.
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

/** Deterministic migrated-admin username (mirrors the server's directory adapter). */
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

/**
 * `true` when this tenant (resolved off `bearer`'s `clubId` claim) owns the
 * migrated glider+tow paired flight: a GLIDER-type flight whose `aircraftId`
 * resolves to the migrated glider immatriculation AND whose DETAIL carries a
 * `towFlightId` to a distinct TOW-type flight (the migrated paired pair). The
 * cross-tenant club carries a lone glider flight with NO tow link, so it is
 * NOT mistaken for the owner. Uses the SAME read APIs the parity spec asserts on.
 *
 * The tow self-FK link is a DETAIL-only field — the `/api/v1/flights` LIST
 * projection (`FlightListItem`) does NOT carry `towFlightId`, so the link is
 * confirmed by GETting the candidate glider's detail (`GET /flights/{id}` →
 * `FlightDetail.towFlightId`), exactly as the `:560` migrated-render spec does.
 * (J-2 T-19: the prior owner-detection read `towFlightId` off the list row — a
 * field the list API never returns — so it resolved no owner even though the
 * migrated pair ingested correctly and rendered.)
 */
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
  // Candidate glider flights: GLIDER type on the migrated glider aircraft.
  const gliderFlights = items.filter(
    (f) => f.flightAircraftType === 'GLIDER' && f.aircraftId === glider.id,
  );
  // Confirm the paired-tow link via the DETAIL endpoint (the only place the tow
  // self-FK surfaces) and that the linked flight is a distinct TOW-type row.
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

/**
 * Drive a migrated club admin through the SPA + real-KC login in a throwaway
 * context and capture the tenant-scoped Bearer the OIDC interceptor attaches.
 * Bounded so a single un-loginable admin can't burn the beforeAll budget;
 * returns `undefined` on timeout/error (treated as non-owner by the caller).
 */
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

/**
 * Seed the migrated flight bundle + resolve the loginable admin of the club that
 * owns the migrated glider+tow paired flight. The bundle declares two clubs (the
 * owner + a cross-tenant club); we identify the owner by OWNERSHIP — each
 * provisioned admin queries its own `/api/v1/flights`; exactly the one carrying
 * the linked glider↔tow pair is the owner (the cross club's lone glider has no
 * tow link).
 */
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

// ===========================================================================
// Clean-seed masterdata — the flight wizard needs real rows to select.
// ===========================================================================
// A clean realm's `seed-club-1` (club A) has NO aircraft / persons / locations /
// flight-types (V5 seeds only the bare club row). The 3-step wizard's dropdowns
// read those stores, so the clean-seed flight CRUD case must seed the
// prerequisites through the REAL create APIs first (no mocking — same chain the
// gate proves). Seeded once in the spec's beforeAll with club A's Bearer.

/** Canonical reference seeds (V2/V3 — the IDs the create requests reference). */
const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const GLIDER_AIRCRAFT_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';
const MOTOR_AIRCRAFT_TYPE_ID = '019e2e15-2c00-7afc-8000-000000002afc';
const GRASS_RUNWAY_LOCATION_TYPE_ID = '019e2e15-2c00-72c9-8000-0000000032c9';
/** AEROTOW start type (V2) — the clean-seed glider create drives this explicitly. */
export const AEROTOW_START_TYPE_ID = '019e2e15-2c00-7fa1-8000-000000000fa1';

/** The masterdata the wizard selects from, seeded for the clean-seed flight CRUD. */
export interface FlightMasterdata {
  /** `loc-<uuid>` start location. */
  locationId: string;
  /** `ft-<uuid>` glider flight type. */
  gliderFlightTypeId: string;
  /** `pn-<uuid>` glider pilot. */
  pilotPersonId: string;
  /** `pn-<uuid>` tow pilot. */
  towPilotPersonId: string;
  /** `ac-<uuid>` glider aircraft + its immatriculation. */
  gliderAircraftId: string;
  gliderImmat: string;
  /** `ac-<uuid>` tow aircraft + its immatriculation. */
  towAircraftId: string;
  towImmat: string;
  /** `ac-<uuid>` motor aircraft (for the unified /flights motor create) + immatriculation. */
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

/**
 * Seed (via the REAL create APIs, as club A's admin) the masterdata the 3-step
 * wizard selects from: a start location, a glider flight type, two pilots, and
 * glider / tow / motor aircraft. Returns the SPA-prefixed ids (`ac-`/`pn-`/etc.)
 * the wizard's af-select options carry, so the spec drives the dropdowns
 * deterministically. Idempotency is by uniqueness — each run mints fresh
 * immatriculations / names tagged with the run id.
 */
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
    // The flight-code is unique per (operating_club_id, flight_code) — V3
    // ux_flight_type_club_code. Tag it with the run-scoped suffix (as the name
    // already is) so a Playwright retry's beforeAll re-seed, or a prior attempt
    // that already inserted, never collides on a hardcoded literal (J-2 T-19:
    // a hardcoded `J2L` 500'd the 2nd seed → the pilot option never rendered).
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

  // The crew person picker (`personOptions()` ← PersonsStore.entities()) reads
  // `GET /api/v1/persons`, whose projection is one row per (Person, caller-tenant
  // PersonClub) pair — `JpaPersonRepository.findActiveListRowsInCurrentTenant`
  // pivots `FROM PersonClub pc JOIN pc.person p` so Hibernate's @TenantId filter
  // applies. A Person created with NO `initialClubMembership` has no PersonClub
  // row in club A, so it never appears in the picker (the dropdown opened but
  // rendered "No Data" — J-2 T-20). A real pickable pilot is made by giving it a
  // membership in the caller's tenant in the same create transaction, so seed one
  // (minimal flags; memberStateId optional). This is how a real pilot becomes
  // selectable — not a test shortcut.
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

/**
 * Log a migrated club admin in through the SPA + Keycloak login form, landing
 * on the authed root. Production `provisionClubAdminIdentity` stamps
 * firstName/lastName so VERIFY_PROFILE never fires.
 */
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
