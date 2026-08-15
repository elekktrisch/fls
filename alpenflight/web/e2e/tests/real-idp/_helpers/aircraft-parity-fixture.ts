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

const AIRCRAFT_SPEC_OWN_MIGRATION_PRINCIPAL_USER = 'clubadmin2@example.com';
const AIRCRAFT_SPEC_OWN_MIGRATION_PRINCIPAL_PASSWORD = 'clubadmin2-dev-2026!';

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
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

export interface AircraftParityFixture {
  immatriculation: string;
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
    await fillKcLogin(
      page,
      AIRCRAFT_SPEC_OWN_MIGRATION_PRINCIPAL_USER,
      AIRCRAFT_SPEC_OWN_MIGRATION_PRINCIPAL_PASSWORD,
    );
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
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
  const perAttemptUniqueTag = `${Date.now().toString(36)}${attempt > 0 ? `r${attempt}` : ''}`;
  const immatriculation = `J1-${perAttemptUniqueTag.slice(-6).toUpperCase()}`;
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

export async function seedAircraftOwnerLink(opts: {
  aircraftId: string;
  ownerKeycloakSub: string;
  ownerClubId: string;
  languageId: string;
}): Promise<string> {
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
