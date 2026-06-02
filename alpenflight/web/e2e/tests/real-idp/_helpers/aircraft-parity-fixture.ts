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
 * J-1 T-07 aircraft migration parity fixture — the migrated-data half of the
 * aircraft real chain. Mirrors the J-0c `fan-out-parity-fixture`: it ingests a
 * migrated bundle through the REAL `POST /api/v1/migrations/{id}/bundle`
 * endpoint (never a DB INSERT) so the migration ingest + Keycloak club-admin
 * provisioning run LIVE, then resolves the loginable migrated admin of the club
 * that owns the migrated aircraft.
 *
 * TWO bundle fidelities, ONE downstream path (identical to J-0c):
 *   - **Synth (default — fast inner loop, no legacy stack).** Mint a handshake
 *     here, shell out to the Gradle `seedAircraftParityBundle` task (the only
 *     Java seam — the ALPF bundle envelope is built in Java by the same factory
 *     the server round-trip ITs use). It emits the encrypted aircraft bundle
 *     (a club + owner Person + homebase Location + one AIRCRAFT with a random
 *     `J1-<rand>` immatriculation + state + counter) as base64 to a temp file.
 *   - **Real (`J1_BUNDLE_SOURCE=real` — full chain).** Reuse the workflow's
 *     handshake JSON (`J1_REAL_HANDSHAKE_FILE`) + the `alpenflight-export`
 *     `.enc` bundle (`J1_REAL_BUNDLE_FILE`), and the legacy-created
 *     immatriculation (`J1_REAL_IMMATRICULATION`).
 *
 * Shared tail (both modes): log in the Flyway-seeded `clubadmin1` migration
 * principal (real KC, verified-email + V8 `t_user`), capture its Bearer, POST
 * the bundle → the ingest provisions one Keycloak club-admin per migrated club.
 * The aircraft's managing club is the single declared club; its provisioned
 * admin is made loginable so the spec can render `/aircraft` as that admin.
 */

/** Seeded `clubadmin1` (V8 dev user seed + realm-export). The migration principal. */
const PRINCIPAL_USER = 'clubadmin1@example.com';
const PRINCIPAL_PASSWORD = 'clubadmin1-dev-2026!';

/**
 * Repo paths: this file is alpenflight/web/e2e/tests/real-idp/_helpers/, so five
 * `..` reach `alpenflight/`. The server is its own Gradle root project; its
 * wrapper + the seeder tasks live under `alpenflight/server/`.
 */
const SERVER_DIR = resolve(__dirname, '../../../../../server');
const GRADLEW = resolve(SERVER_DIR, 'gradlew');

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the aircraft fixture');
  }
  return id;
}

function useRealBundle(): boolean {
  return (process.env['J1_BUNDLE_SOURCE'] ?? 'synth').toLowerCase() === 'real';
}

function requiredEnvPath(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} not set — J1_BUNDLE_SOURCE=real requires the proof workflow to point at the ` +
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

export interface AircraftParityFixture {
  /** The migrated aircraft's immatriculation (random in synth, legacy-created in real). */
  immatriculation: string;
  /** The migrated admin of the club that owns (manages) the migrated aircraft. */
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
  immatriculation: string;
  bundlePath: string;
}

interface ResolvedBundle {
  bundle: Buffer;
  uploadId: string;
  immatriculation: string;
}

/** `GET /api/v1/aircraft` list-item projection (the SPA's generated `AircraftListItem`). */
interface AircraftListItem {
  id: string;
  immatriculation: string;
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
    // The aircraft list is the principal's authed read surface; navigating to it
    // guarantees a /api/v1/* call fires so the interceptor attaches a Bearer.
    await page.goto('/aircraft');
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
 * Invoke the Gradle `seedAircraftParityBundle` task to build the encrypted
 * aircraft bundle. Returns the raw bundle bytes (the task base64-encodes to a
 * temp file; we decode here).
 */
async function buildBundleBytes(
  publicKeyPem: string,
  uploadId: string,
  immatriculation: string,
  clubKey: string,
): Promise<Buffer> {
  const workDir = await mkdtemp(join(tmpdir(), 'j1-aircraft-'));
  const pemPath = join(workDir, 'handshake-public-key.pem');
  const outPath = join(workDir, 'bundle.b64');
  await writeFile(pemPath, publicKeyPem, 'utf8');

  const seederArgs = [pemPath, uploadId, immatriculation, clubKey, outPath].join(' ');
  const { stdout } = await execFileAsync(
    GRADLEW,
    ['--quiet', 'seedAircraftParityBundle', `-PseederArgs=${seederArgs}`],
    { cwd: SERVER_DIR, maxBuffer: 16 * 1024 * 1024 },
  );
  const line = stdout
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{') && l.endsWith('}'))
    .pop();
  if (!line) {
    throw new Error(`aircraft seeder produced no JSON result line; stdout was:\n${stdout}`);
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
  // uploadId / club key / immatriculation: a failed ingest seals its upload
  // FAILED (a re-POST 409s BUNDLE_PRIOR_RUN_FAILED).
  const attemptTag = `${Date.now().toString(36)}${attempt > 0 ? `r${attempt}` : ''}`;
  // Immatriculation must satisfy the SPA / domain pattern + length cap (<=15).
  // Keep it short, uppercase, hyphenated: `J1-<6 hex>`.
  const immatriculation = `J1-${attemptTag.slice(-6).toUpperCase()}`;
  const clubKey = `J1A${runId().slice(0, 4).toUpperCase()}${attempt}`;
  const handshake = await mintHandshake(api, bearer);
  const bundle = await buildBundleBytes(
    handshake.publicKeyPem,
    handshake.uploadId,
    immatriculation,
    clubKey,
  );
  return { bundle, uploadId: handshake.uploadId, immatriculation };
}

async function resolveRealBundle(): Promise<ResolvedBundle> {
  const handshakeFile = requiredEnvPath('J1_REAL_HANDSHAKE_FILE');
  const bundleFile = requiredEnvPath('J1_REAL_BUNDLE_FILE');
  const immatriculation = requiredEnvPath('J1_REAL_IMMATRICULATION');

  const handshakeRaw = await readFile(handshakeFile, 'utf8');
  const handshake = JSON.parse(handshakeRaw) as HandshakeResponse;
  if (!handshake.uploadId) {
    throw new Error(
      `J1_REAL_HANDSHAKE_FILE (${handshakeFile}) has no uploadId — the workflow must save the ` +
        `/api/v1/migrations/handshake response verbatim before running alpenflight-export.`,
    );
  }
  const bundle = await readFile(bundleFile);
  return { bundle, uploadId: handshake.uploadId, immatriculation };
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
 * `true` when this tenant (resolved off `bearer`'s `clubId` claim) carries an
 * aircraft with the migrated immatriculation. Uses the SAME read API the parity
 * spec asserts on (`GET /api/v1/aircraft`).
 */
async function tenantHasAircraft(
  api: APIRequestContext,
  bearer: string,
  immatriculation: string,
): Promise<boolean> {
  const res = await api.get('/api/v1/aircraft', { headers: { authorization: bearer } });
  if (!res.ok()) {
    throw new Error(`GET /api/v1/aircraft failed (${res.status()}): ${await res.text()}`);
  }
  const items = (await res.json()) as AircraftListItem[];
  return items.some((item) => item.immatriculation === immatriculation);
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
        new URL(req.url()).pathname === '/api/v1/aircraft' &&
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
    await page.goto('/aircraft');
    return await bearerPromise;
  } catch (err) {
    console.warn(
      `[J-1] club ${admin.clubId} admin did not reach the authed root within ` +
        `${PER_CLUB_LOGIN_BUDGET_MS}ms — treating as non-owner (${(err as Error).message})`,
    );
    return undefined;
  } finally {
    await bearerPromise;
    await context.close();
  }
}

/**
 * Seed the migrated aircraft + resolve the loginable admin of its managing
 * club. The aircraft's managing club is the bundle's single declared club, but
 * the real FLSTest bundle may declare several clubs (all FULL_PORT) — only the
 * one carrying the migrated aircraft is the owner. We identify it by OWNERSHIP:
 * each provisioned admin queries its own `/api/v1/aircraft`; exactly the one
 * whose list contains the immatriculation is the managing club.
 */
export async function seedAircraftParity(
  browser: Browser,
  api: APIRequestContext,
  baseURL: string,
  attempt = 0,
): Promise<AircraftParityFixture> {
  const bearer = await capturePrincipalBearer(browser, baseURL);

  const { bundle, uploadId, immatriculation } = useRealBundle()
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
      if (tenantBearer && (await tenantHasAircraft(api, tenantBearer, immatriculation))) {
        owner = admin;
        break;
      }
    } catch (err) {
      console.warn(
        `[J-1] club ${clubId} ownership check failed — skipping (${(err as Error).message})`,
      );
    }
  }

  expect(
    owner,
    `exactly one migrated club must carry the migrated aircraft "${immatriculation}" ` +
      `(managing club); none of ${ingest.clubIds.length} provisioned clubs did`,
  ).toBeTruthy();

  return { immatriculation, owner: owner! };
}

/**
 * Log a migrated club admin in through the SPA + Keycloak login form, landing
 * on the authed root. Production `provisionClubAdminIdentity` stamps
 * firstName/lastName (T-06) so VERIFY_PROFILE never fires.
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

/**
 * Run the Gradle `seedAircraftOwnerLink` task (S-163 [edge] DB-fixture seam):
 * sets the owner Person + the JWT-sub→Person `t_user` link + the aircraft's
 * `aircraft_owner_person_id`. This is fixture STATE, not a mocked seam — the
 * S-163 access decision still runs fully real off these rows. Returns the
 * created `personId`.
 */
export async function seedAircraftOwnerLink(opts: {
  aircraftId: string;
  ownerKeycloakSub: string;
  ownerClubId: string;
  languageId: string;
}): Promise<string> {
  // The aircraft id arrives in external `ac-<uuid>` form; the seeder UPDATEs the
  // raw uuid PK, so strip the prefix.
  const rawAircraftId = opts.aircraftId.replace(/^ac-/, '');
  const seederArgs = [rawAircraftId, opts.ownerKeycloakSub, opts.ownerClubId, opts.languageId].join(
    ' ',
  );
  const { stdout } = await execFileAsync(
    GRADLEW,
    ['--quiet', 'seedAircraftOwnerLink', `-PseederArgs=${seederArgs}`],
    { cwd: SERVER_DIR, maxBuffer: 4 * 1024 * 1024 },
  );
  const line = stdout
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{') && l.endsWith('}'))
    .pop();
  if (!line) {
    throw new Error(`owner-link seeder produced no JSON result line; stdout was:\n${stdout}`);
  }
  const parsed = JSON.parse(line) as { personId: string };
  return parsed.personId;
}
