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
 * J-0c fan-out parity fixture — TWO bundle fidelities, ONE downstream path.
 *
 * Seeds the migrated fan-out data the parity spec asserts on, going through the
 * REAL migration endpoint (never a DB INSERT) so the fan-out keying + Keycloak
 * provisioning actually run. The ONLY thing that varies between the fast inner
 * loop (T-03) and the heavy full chain (T-05) is WHERE the encrypted bundle
 * bytes come from — everything after the `POST .../bundle` (fan-out, Keycloak
 * provision, the loginable-admin resolution) is byte-identical:
 *
 *   - **Synth (default — T-03 inner loop, no legacy stack).** Mint a handshake
 *     here, then shell out to the Gradle `seedFanOutParityBundle` task (the only
 *     Java seam — the ALPF bundle envelope is built in Java by the same factory
 *     the server round-trip ITs use). It emits the encrypted fan-out bundle
 *     bytes (one shared legacy Location with a random `J0C-<rand>` name,
 *     referenced by 2 clubs) as base64 to a temp file.
 *   - **Real (`J0C_BUNDLE_SOURCE=real` — T-05 full chain).** The
 *     `alpenflight-export` CLI already ran in the proof workflow against the
 *     legacy MSSQL the T-04 flsweb spec seeded, encrypting against a handshake
 *     the workflow ALSO minted. So this mode mints NO handshake of its own —
 *     it reuses the workflow's handshake JSON (`J0C_REAL_HANDSHAKE_FILE`,
 *     `{ uploadId, publicKeyPem }` — the bundle is sealed to THAT `uploadId`)
 *     and the produced `.enc` bundle (`J0C_REAL_BUNDLE_FILE`). The asserted
 *     Location name is the random one the legacy spec created
 *     (`J0C_REAL_LOCATION_NAME`), not one we invent — that's the freshness
 *     guarantee the data actually flowed through the legacy UI.
 *
 * Shared tail (both modes):
 *   - Log in the Flyway-seeded `clubadmin1` (real KC, `email_verified` +
 *     pre-seeded `t_user` from V8) and capture the Bearer the OIDC interceptor
 *     attaches — the migration PRINCIPAL (the bundle endpoint requires a
 *     verified-email JWT that resolves to a `t_user`).
 *   - `POST /api/v1/migrations/{uploadId}/bundle` (real endpoint) → the ingest
 *     fans the Location out to 2 `t_location` rows + provisions one Keycloak
 *     club-admin identity per migrated club (T-02). Response carries `clubIds`.
 *   - For each provisioned club admin
 *     (`migrated-admin+<clubId>@migrated.alpenflight.local`), set a known
 *     password + clear `UPDATE_PASSWORD` via `makeMigratedAdminLoginable` so the
 *     Playwright login completes (test-only setup — does not weaken production
 *     provisioning).
 *
 * Returns the two club ids + their loginable admin handles + the
 * (synth-random OR legacy-created) Location name the UI asserts on.
 */

/** Seeded `clubadmin1` (V8 dev user seed + realm-export). The migration principal. */
const PRINCIPAL_USER = 'clubadmin1@example.com';
const PRINCIPAL_PASSWORD = 'clubadmin1-dev-2026!';

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the fan-out fixture');
  }
  return id;
}

/**
 * `true` when the proof workflow (T-05) wants the REAL legacy-exported bundle
 * instead of the synthesized Gradle one. The workflow sets `J0C_BUNDLE_SOURCE=real`
 * AFTER `alpenflight-export` has produced the `.enc` and the handshake JSON.
 */
function useRealBundle(): boolean {
  return (process.env['J0C_BUNDLE_SOURCE'] ?? 'synth').toLowerCase() === 'real';
}

/** Read a required env-var path for the real-bundle mode, or throw a precise error. */
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

/**
 * Resolve the bundle bytes + the handshake the bundle is sealed to + the
 * asserted Location name, branching on the bundle source. The synth path mints
 * its own handshake + invents a random name; the real path reuses the
 * workflow's handshake JSON and the legacy-created name (read from the
 * `.enc` / handshake files the export step wrote).
 */
interface ResolvedBundle {
  bundle: Buffer;
  uploadId: string;
  locationName: string;
}

async function resolveSynthBundle(api: APIRequestContext, bearer: string): Promise<ResolvedBundle> {
  const locationName = `J0C-${runId()}-${Date.now().toString(36)}`;
  const clubKeyPrefix = `J0C${runId().slice(0, 4).toUpperCase()}`;
  const handshake = await mintHandshake(api, bearer);
  const bundle = await buildBundleBytes(
    handshake.publicKeyPem,
    handshake.uploadId,
    locationName,
    clubKeyPrefix,
  );
  return { bundle, uploadId: handshake.uploadId, locationName };
}

/**
 * Real path: the bundle is the `alpenflight-export` output. It is sealed to the
 * handshake the workflow minted (`uploadId`), so we read that SAME handshake
 * JSON rather than minting a fresh one — minting here would produce a NEW
 * `uploadId` the bundle isn't encrypted for, and the ingest would reject it.
 */
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
  // The export CLI writes the raw encrypted bundle (the `.enc`); the ingest
  // endpoint accepts those bytes directly (`content-type: application/octet-stream`).
  const bundle = await readFile(bundleFile);
  return { bundle, uploadId: handshake.uploadId, locationName };
}

/**
 * Repo paths: this file is alpenflight/web/e2e/tests/real-idp/_helpers/, so five
 * `..` reach `alpenflight/`. The server is its OWN Gradle root project
 * (`alpenflight-server`, composite-including migration-bundle) — its wrapper +
 * the `seedFanOutParityBundle` task both live under `alpenflight/server/`.
 */
const SERVER_DIR = resolve(__dirname, '../../../../../server');
const GRADLEW = resolve(SERVER_DIR, 'gradlew');

export interface MigratedClubAdmin {
  /** Raw provisioned club UUID — matches the `clubId` claim + admin username tag. */
  clubId: string;
  username: string;
  password: string;
  kcUserId: string;
}

export interface FanOutParityFixture {
  locationName: string;
  clubA: MigratedClubAdmin;
  clubB: MigratedClubAdmin;
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
  clubKeyA: string;
  clubKeyB: string;
  locationName: string;
  bundlePath: string;
}

/**
 * Drive the seeded migration principal through the SPA login + capture the
 * Bearer the OIDC interceptor attaches to its first `/api/v1/*` call. Mirrors
 * `two-club-fixture.captureSysadminBearer` — navigate to `/locations` (the
 * principal's authed list) to guarantee such a call fires.
 */
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
    await page.goto('/locations');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

/**
 * T-05 full chain: mint the migration handshake and write it verbatim to
 * `outFile` so the proof workflow can hand it to `alpenflight-export
 * --handshake-file` BEFORE the parity spec runs. The export seals the bundle
 * to THIS handshake's public key + `uploadId`; `resolveRealBundle` then reads
 * the same JSON so the ingest `uploadId` matches what the bundle is encrypted
 * for. Reuses the SPA-driven principal bearer (no direct-access-grant on the
 * `alpenflight-web` client, so a curl token isn't available — the SPA login is
 * the only path to a verified-email principal JWT).
 */
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

/**
 * Invoke the Gradle seeder to build the encrypted fan-out bundle. Returns the
 * raw bundle bytes (the task base64-encodes to a temp file; we decode here).
 */
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
  // `--quiet` keeps Gradle's own chatter off stdout so the seeder's single
  // JSON line is the only thing we parse. The task runs against the test
  // runtime classpath (compiles src/test/java on demand if stale).
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

/** Resolve a provisioned club admin's KC user + make it loginable for the spec. */
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

export async function seedFanOutParity(
  browser: Browser,
  api: APIRequestContext,
  baseURL: string,
): Promise<FanOutParityFixture> {
  // The migration PRINCIPAL bearer is needed by BOTH modes (the bundle POST is
  // an authenticated, verified-email call). The synth path additionally uses it
  // to mint its own handshake; the real path reuses the workflow's handshake.
  const bearer = await capturePrincipalBearer(browser, baseURL);

  const { bundle, uploadId, locationName } = useRealBundle()
    ? await resolveRealBundle()
    : await resolveSynthBundle(api, bearer);

  const ingest = await ingestBundle(api, bearer, uploadId, bundle);

  expect(
    ingest.clubIds.length,
    useRealBundle()
      ? 'the legacy-exported bundle fans the shared Location out to exactly 2 clubs → ingest provisions 2'
      : 'the synthesized bundle declares exactly 2 clubs → ingest provisions 2',
  ).toBe(2);

  const clubAId = ingest.clubIds[0];
  const clubBId = ingest.clubIds[1];
  if (!clubAId || !clubBId) {
    throw new Error(`ingest returned ${ingest.clubIds.length} clubIds, expected 2`);
  }
  const clubA = await loginableAdmin(clubAId);
  const clubB = await loginableAdmin(clubBId);

  return { locationName, clubA, clubB };
}

/**
 * Log a migrated club admin in through the SPA + Keycloak login form, landing
 * on the authed root. The page's storageState is the per-club session — the
 * `clubId` claim (provisioned club UUID) resolves the tenant, and
 * `JitUserMaterializer` (S-169) projects the `t_user` on first login.
 */
export async function loginAsMigratedAdmin(page: Page, admin: MigratedClubAdmin): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, admin.username, admin.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}
