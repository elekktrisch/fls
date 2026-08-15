import { execFile } from 'node:child_process';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

import { type APIRequestContext, type Browser, type Page, expect } from '@playwright/test';

import { fillKcLogin } from './kc-form';
import {
  findUserByUsername,
  findUsersByUsernameSearch,
  makeMigratedAdminLoginable,
} from './keycloak-admin';
import { E2E_CANNED_PASSWORD } from './test-user';

const execFileAsync = promisify(execFile);

const MIGRATION_PRINCIPAL_USER = 'clubadmin1@example.com';
const MIGRATION_PRINCIPAL_PASSWORD = 'clubadmin1-dev-2026!';

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the fan-out fixture');
  }
  return id;
}

function useRealBundle(): boolean {
  return (process.env['J0C_BUNDLE_SOURCE'] ?? 'synth').toLowerCase() === 'real';
}

function requiredEnvPath(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} not set — J0C_BUNDLE_SOURCE=real requires the proof workflow to point at the ` +
        `legacy-exported artifact (the export + handshake steps must run before this spec).`,
    );
  }
  return value;
}

interface ResolvedBundle {
  bundle: Buffer;
  uploadId: string;
  locationName: string;
}

async function resolveSynthBundle(
  api: APIRequestContext,
  bearer: string,
  attempt: number,
): Promise<ResolvedBundle> {
  const attemptTag = `${Date.now().toString(36)}${attempt > 0 ? `r${attempt}` : ''}`;
  const locationName = `J0C-${runId()}-${attemptTag}`;
  const clubKeyPrefix = `J0C${runId().slice(0, 4).toUpperCase()}${attempt}`;
  const handshake = await mintHandshake(api, bearer);
  const bundle = await buildBundleBytes(
    handshake.publicKeyPem,
    handshake.uploadId,
    locationName,
    clubKeyPrefix,
  );
  return { bundle, uploadId: handshake.uploadId, locationName };
}

async function resolveRealBundle(): Promise<ResolvedBundle> {
  const handshakeFile = requiredEnvPath('J0C_REAL_HANDSHAKE_FILE');
  const bundleFile = requiredEnvPath('J0C_REAL_BUNDLE_FILE');
  const locationName = requiredEnvPath('J0C_REAL_LOCATION_NAME');

  const handshakeRaw = await readFile(handshakeFile, 'utf8');
  const handshake = JSON.parse(handshakeRaw) as HandshakeResponse;
  if (!handshake.uploadId) {
    throw new Error(
      `J0C_REAL_HANDSHAKE_FILE (${handshakeFile}) has no uploadId — the workflow must save the ` +
        `/api/v1/migrations/handshake response verbatim before running alpenflight-export.`,
    );
  }
  const bundle = await readFile(bundleFile);
  return { bundle, uploadId: handshake.uploadId, locationName };
}

const SERVER_DIR = resolve(__dirname, '../../../../../server');
const GRADLEW = resolve(SERVER_DIR, 'gradlew');

export interface MigratedClubAdmin {
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

export interface FanOutParityFixture {
  locationName: string;
  clubA: MigratedClubAdmin;
  clubB: MigratedClubAdmin;
  nonOwner?: MigratedClubAdmin;
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

interface MigrationRunStatusView {
  uploadId: string;
  state: 'DECRYPTING' | 'PROVISIONING' | 'INGESTING' | 'COMPLETING' | 'COMPLETED' | 'FAILED';
  deploymentId: string | null;
  clubIds: string[];
  errorCode: string | null;
}

interface IngestAttempt {
  deploymentId: string;
  clubIds: string[];
  reusedPriorDeployment: boolean;
}

interface LocationListItem {
  id: string;
  locationName: string;
}

interface SeederOutput {
  clubKeyA: string;
  clubKeyB: string;
  locationName: string;
  bundlePath: string;
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
    await fillKcLogin(page, MIGRATION_PRINCIPAL_USER, MIGRATION_PRINCIPAL_PASSWORD);
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
    await page.goto('/locations');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

export async function mintRealHandshakeToFile(
  browser: Browser,
  api: APIRequestContext,
  baseURL: string,
  outFile: string,
): Promise<HandshakeResponse> {
  const bearer = await capturePrincipalBearer(browser, baseURL);
  const handshake = await mintHandshake(api, bearer);
  await writeFile(outFile, JSON.stringify(handshake), 'utf8');
  return handshake;
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
  locationName: string,
  clubKeyPrefix: string,
): Promise<Buffer> {
  const workDir = await mkdtemp(join(tmpdir(), 'j0c-fanout-'));
  const pemPath = join(workDir, 'handshake-public-key.pem');
  const outPath = join(workDir, 'bundle.b64');
  await writeFile(pemPath, publicKeyPem, 'utf8');

  const seederArgs = [pemPath, uploadId, locationName, clubKeyPrefix, outPath].join(' ');
  const { stdout } = await execFileAsync(
    GRADLEW,
    ['--quiet', 'seedFanOutParityBundle', `-PseederArgs=${seederArgs}`],
    { cwd: SERVER_DIR, maxBuffer: 16 * 1024 * 1024 },
  );
  const line = stdout
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{') && l.endsWith('}'))
    .pop();
  if (!line) {
    throw new Error(`seeder produced no JSON result line; stdout was:\n${stdout}`);
  }
  const parsed = JSON.parse(line) as SeederOutput;
  const b64 = await readFile(parsed.bundlePath, 'utf8');
  return Buffer.from(b64, 'base64');
}

interface ProblemDetail {
  errorCode?: string;
  existingDeploymentId?: string;
}

function problemDetailOrEmpty(text: string): ProblemDetail {
  try {
    return JSON.parse(text) as ProblemDetail;
  } catch {
    return {};
  }
}

async function ingestBundle(
  api: APIRequestContext,
  bearer: string,
  uploadId: string,
  bundle: Buffer,
): Promise<IngestAttempt> {
  const res = await api.post(`/api/v1/migrations/${uploadId}/bundle`, {
    headers: { authorization: bearer, 'content-type': 'application/octet-stream' },
    data: bundle,
  });
  if (res.ok()) {
    const body = (await res.json()) as IngestResponse;
    await pollDeploymentToCompleted(api, bearer, uploadId);
    return { deploymentId: body.deploymentId, clubIds: body.clubIds, reusedPriorDeployment: false };
  }

  const status = res.status();
  const text = await res.text();
  if (status === 409) {
    const problem = problemDetailOrEmpty(text);
    if (problem.errorCode === 'DEPLOYMENT_EXISTS') {
      const existing = problem.existingDeploymentId ?? '';
      if (!existing) {
        throw new Error(
          `409 DEPLOYMENT_EXISTS carried no existingDeploymentId — cannot reuse the prior ` +
            `migration (body: ${text})`,
        );
      }
      return { deploymentId: existing, clubIds: [], reusedPriorDeployment: true };
    }
  }
  throw new Error(`bundle ingest failed (${status}): ${text}`);
}

async function pollDeploymentToCompleted(
  api: APIRequestContext,
  bearer: string,
  uploadId: string,
): Promise<MigrationRunStatusView> {
  const deadline = Date.now() + STATUS_POLL_BUDGET_MS;
  let last: MigrationRunStatusView | undefined;
  while (Date.now() < deadline) {
    const res = await api.get(`/api/v1/migrations/${uploadId}/status`, {
      headers: { authorization: bearer },
    });
    if (res.ok()) {
      last = (await res.json()) as MigrationRunStatusView;
      if (last.state === 'COMPLETED') {
        return last;
      }
      if (last.state === 'FAILED') {
        throw new Error(
          `migration run for upload ${uploadId} reached FAILED (errorCode ${last.errorCode}) — ` +
            `the ingest did not complete`,
        );
      }
    }
    await new Promise((r) => setTimeout(r, STATUS_POLL_INTERVAL_MS));
  }
  throw new Error(
    `migration run for upload ${uploadId} did not reach COMPLETED within ` +
      `${STATUS_POLL_BUDGET_MS}ms (last state ${last?.state ?? 'unknown'})`,
  );
}

const STATUS_POLL_BUDGET_MS = 30_000;
const STATUS_POLL_INTERVAL_MS = 500;

function migratedAdminUsername(clubId: string): string {
  return `migrated-admin+${clubId}@migrated.alpenflight.local`;
}

async function loginableAdmin(clubId: string): Promise<MigratedClubAdmin> {
  const username = migratedAdminUsername(clubId);
  const kcUser = await findUserByUsername(username);
  if (!kcUser) {
    throw new Error(
      `migration did not provision a Keycloak admin for club ${clubId} ` +
        `(expected username ${username}) — T-02 provisioning regression`,
    );
  }
  await makeMigratedAdminLoginable(kcUser.id, username, E2E_CANNED_PASSWORD);
  return { clubId, username, password: E2E_CANNED_PASSWORD, kcUserId: kcUser.id };
}

const PER_CLUB_LOGIN_BUDGET_MS = 12_000;

async function tryBearerForMigratedAdmin(
  browser: Browser,
  baseURL: string,
  admin: MigratedClubAdmin,
): Promise<string | undefined> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  const bearerPromiseSettledBeforeClose = page
    .waitForRequest(
      (req) =>
        new URL(req.url()).pathname === '/api/v1/locations' &&
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
    await page.goto('/locations');
    return await bearerPromiseSettledBeforeClose;
  } catch (err) {
    console.warn(
      `[T-23] club ${admin.clubId} admin did not reach the authed root within ` +
        `${PER_CLUB_LOGIN_BUDGET_MS}ms — treating as non-owner (${(err as Error).message})`,
    );
    return undefined;
  } finally {
    await bearerPromiseSettledBeforeClose;
    await context.close();
  }
}

async function tenantHasLocation(
  api: APIRequestContext,
  bearer: string,
  locationName: string,
): Promise<boolean> {
  const res = await api.get('/api/v1/locations', { headers: { authorization: bearer } });
  if (!res.ok()) {
    throw new Error(`GET /api/v1/locations failed (${res.status()}): ${await res.text()}`);
  }
  const items = (await res.json()) as LocationListItem[];
  return items.some((item) => item.locationName === locationName);
}

const MIGRATED_ADMIN_USERNAME_INFIX = 'migrated-admin+';

function clubIdFromMigratedUsername(username: string): string | null {
  const m = /^migrated-admin\+([0-9a-f-]{36})@migrated\.alpenflight\.local$/i.exec(username);
  return m ? m[1]! : null;
}

async function partitionLocationOwners(
  browser: Browser,
  api: APIRequestContext,
  baseURL: string,
  admins: MigratedClubAdmin[],
  locationName: string,
): Promise<{ owners: MigratedClubAdmin[]; nonOwners: MigratedClubAdmin[] }> {
  const owners: MigratedClubAdmin[] = [];
  const nonOwners: MigratedClubAdmin[] = [];
  for (const admin of admins) {
    try {
      await makeMigratedAdminLoginable(admin.kcUserId, admin.username, admin.password);
      const tenantBearer = await tryBearerForMigratedAdmin(browser, baseURL, admin);
      if (tenantBearer && (await tenantHasLocation(api, tenantBearer, locationName))) {
        owners.push(admin);
      } else {
        nonOwners.push(admin);
      }
    } catch (err) {
      console.warn(
        `[T-23] club ${admin.clubId} ownership check failed — treating as non-owner ` +
          `(${(err as Error).message})`,
      );
      nonOwners.push(admin);
    }
  }
  return { owners, nonOwners };
}

async function reusedDeploymentFixture(
  browser: Browser,
  api: APIRequestContext,
  baseURL: string,
  locationName: string,
): Promise<FanOutParityFixture> {
  const candidates = await findUsersByUsernameSearch(MIGRATED_ADMIN_USERNAME_INFIX);
  const admins = candidates
    .map((u): MigratedClubAdmin | null => {
      const clubId = clubIdFromMigratedUsername(u.username);
      return clubId
        ? { clubId, username: u.username, password: E2E_CANNED_PASSWORD, kcUserId: u.id }
        : null;
    })
    .filter((a): a is MigratedClubAdmin => a !== null);
  if (admins.length < 2) {
    throw new Error(
      `409 DEPLOYMENT_EXISTS (bundle already migrated) but only ${admins.length} provisioned ` +
        `migrated admin(s) exist — the prior migration did not provision the clubs.`,
    );
  }

  const { owners, nonOwners } = await partitionLocationOwners(
    browser,
    api,
    baseURL,
    admins,
    locationName,
  );
  const [clubA, clubB] = owners;
  if (!clubA || !clubB) {
    throw new Error(
      `409 DEPLOYMENT_EXISTS reuse: of the ${admins.length} provisioned migrated club(s), only ` +
        `${owners.length} carry the fanned-out Location "${locationName}" — expected exactly 2 ` +
        `owners (each club's distinct copy). A prior migration provisioned the clubs but the ` +
        `2-way Location fan-out did not survive, or an owner's admin could not authenticate.`,
    );
  }
  const nonOwner = nonOwners[0];
  return { locationName, clubA, clubB, ...(nonOwner ? { nonOwner } : {}) };
}

export async function seedFanOutParity(
  browser: Browser,
  api: APIRequestContext,
  baseURL: string,
  attempt = 0,
): Promise<FanOutParityFixture> {
  const bearer = await capturePrincipalBearer(browser, baseURL);

  const { bundle, uploadId, locationName } = useRealBundle()
    ? await resolveRealBundle()
    : await resolveSynthBundle(api, bearer, attempt);

  const ingest = await ingestBundle(api, bearer, uploadId, bundle);

  if (ingest.reusedPriorDeployment) {
    return reusedDeploymentFixture(browser, api, baseURL, locationName);
  }

  if (ingest.clubIds.length < 2) {
    throw new Error(
      `ingest provisioned ${ingest.clubIds.length} club(s) — need ≥2 to host a 2-way fan-out`,
    );
  }

  const admins: MigratedClubAdmin[] = [];
  for (const clubId of ingest.clubIds) {
    try {
      admins.push(await loginableAdmin(clubId));
    } catch (err) {
      console.warn(
        `[T-23] club ${clubId} admin resolution failed — skipping ` + `(${(err as Error).message})`,
      );
    }
  }

  const { owners, nonOwners } = await partitionLocationOwners(
    browser,
    api,
    baseURL,
    admins,
    locationName,
  );

  expect(
    owners.length,
    useRealBundle()
      ? `real FLSTest migrates ${ingest.clubIds.length} clubs (FULL_PORT CLUB), but only the 2 that ` +
          `reference the shared legacy Location "${locationName}" via Clubs.HomebaseId fan it out → ` +
          `exactly 2 tenants must carry a copy (found ${owners.length} of ${ingest.clubIds.length})`
      : `the synthesized bundle declares 2 clubs both referencing the shared Location "${locationName}" → ` +
          `exactly 2 tenants must carry a copy (found ${owners.length} of ${ingest.clubIds.length})`,
  ).toBe(2);

  const [clubA, clubB] = owners;
  if (!clubA || !clubB) {
    throw new Error(`expected 2 Location-owning clubs, got ${owners.length}`);
  }

  const nonOwner = nonOwners[0];
  return { locationName, clubA, clubB, ...(nonOwner ? { nonOwner } : {}) };
}

let singleUseBundleIngestPromise: Promise<FanOutParityFixture> | undefined;

export async function ensureSharedMigrationBundle(
  browser: Browser,
  baseURL: string,
): Promise<FanOutParityFixture> {
  if (singleUseBundleIngestPromise) return singleUseBundleIngestPromise;
  singleUseBundleIngestPromise = (async () => {
    const context = await browser.newContext({ baseURL });
    try {
      return await seedFanOutParity(browser, context.request, baseURL);
    } finally {
      await context.close();
    }
  })();
  try {
    return await singleUseBundleIngestPromise;
  } catch (err) {
    singleUseBundleIngestPromise = undefined;
    throw err;
  }
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
