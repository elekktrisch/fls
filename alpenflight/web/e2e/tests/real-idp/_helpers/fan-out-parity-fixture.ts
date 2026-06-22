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
 * Fan-out target selection: ALL migrated clubs are provisioned (FULL_PORT
 * CLUB), but only the ones that referenced the shared legacy Location get a
 * fanned-out `t_location` copy — 2 of 2 in synth mode, 2 of 4 against the real
 * FLSTest DB. Since `IngestResponse` carries no club names and the provisioned
 * admin username is derived from the clubId (not the legacy username), the 2
 * targets are identified by OWNERSHIP: each migrated admin logs in and queries
 * its own `/api/v1/locations` (the read API the spec asserts on); exactly 2 must
 * carry the Location (the true fan-out assertion). clubA/clubB are those 2.
 *
 * Returns the two Location-owning club admin handles + the (synth-random OR
 * legacy-created) Location name the UI asserts on (+ an optional non-owning
 * club in real mode).
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

async function resolveSynthBundle(
  api: APIRequestContext,
  bearer: string,
  attempt: number,
): Promise<ResolvedBundle> {
  // Attempt-scoped suffix so a Playwright retry NEVER reuses the prior run's
  // uploadId / club keys / Location name: a failed ingest seals its upload
  // FAILED (a re-POST of the same uploadId 409s BUNDLE_PRIOR_RUN_FAILED), and a
  // half-provisioned deployment would collide a re-run on the club keys. Each
  // attempt mints a fresh handshake (new uploadId) below AND derives unique
  // keys here, so the retry re-handshakes cleanly.
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
  /** A migrated club that owns a fanned-out copy of the shared Location. */
  clubA: MigratedClubAdmin;
  /** The OTHER migrated club that owns a copy (distinct fanned-out row). */
  clubB: MigratedClubAdmin;
  /**
   * A migrated club that does NOT carry the fanned-out Location (real FLSTest
   * has ≥1; synth mode has none, so `undefined` there). Not consumed by the
   * current 3 spec cases — exposed for a future cross-tenant case that wants a
   * club that should never see the Location.
   */
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

/** `GET /api/v1/migrations/{uploadId}/status` view (the SPA poll shape). */
interface MigrationRunStatusView {
  uploadId: string;
  state: 'DECRYPTING' | 'PROVISIONING' | 'INGESTING' | 'COMPLETING' | 'COMPLETED' | 'FAILED';
  deploymentId: string | null;
  clubIds: string[];
  errorCode: string | null;
}

/**
 * Outcome of an ingest attempt against the shared single-use bundle. `reused`
 * marks the {@code 409 DEPLOYMENT_EXISTS} path: the principal already owns a
 * non-terminal Deployment from a PRIOR ingest (a deployment a migration leaves
 * ACTIVE once it COMPLETED — `findActiveByOwner` matches TRIAL/ACTIVE/PAST_DUE/
 * CANCELLED), so re-POSTing the same data is a no-op the harness must treat as
 * "already migrated", not a hard failure. `clubIds` is populated only on a fresh
 * 200 (the 409 body carries just the existing deployment id); reuse recovers the
 * migrated clubs by enumerating the provisioned Keycloak admins downstream.
 */
interface IngestAttempt {
  deploymentId: string;
  clubIds: string[];
  reused: boolean;
}

/** `GET /api/v1/locations` list-item projection (the SPA's generated `LocationListItem`). */
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

/**
 * POST the bundle through the real migration endpoint. The ingest pipeline is
 * SYNCHRONOUS — a 200 returns only after the txn commits with the run COMPLETED +
 * the Deployment ACTIVE — so a fresh 200 needs no poll. But the migration also
 * leaves that Deployment ACTIVE, and `findActiveByOwner` (the pre-decrypt guard)
 * matches every non-EXPIRED lifecycle state: a SECOND POST by the same principal
 * (a re-run against a Postgres that already carries the migrated deployment, the
 * steady state of local-first iteration on the LAN PG) is refused with
 * {@code 409 DEPLOYMENT_EXISTS}. That is NOT a failure — the data is already
 * migrated — so we reuse `existingDeploymentId` from the problem-detail body and
 * never re-ingest the single-use upload. A real failure (any other 4xx/5xx) still
 * throws.
 */
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
    // Defensive against any async finalization (Keycloak provision / read-model
    // rebuild that runs post-commit): block until the run reaches a terminal
    // COMPLETED before downstream reads. A 200 is already terminal, so this
    // returns on the first poll — it is the SAFETY for a future async ingest.
    await pollDeploymentToCompleted(api, bearer, uploadId);
    return { deploymentId: body.deploymentId, clubIds: body.clubIds, reused: false };
  }

  const status = res.status();
  const text = await res.text();
  if (status === 409) {
    let problem: { errorCode?: string; existingDeploymentId?: string } = {};
    try {
      problem = JSON.parse(text) as typeof problem;
    } catch {
      // Non-JSON 409 body — fall through to the generic throw below.
    }
    if (problem.errorCode === 'DEPLOYMENT_EXISTS') {
      const existing = problem.existingDeploymentId ?? '';
      if (!existing) {
        throw new Error(
          `409 DEPLOYMENT_EXISTS carried no existingDeploymentId — cannot reuse the prior ` +
            `migration (body: ${text})`,
        );
      }
      // The reused deployment is ACTIVE, which a migration reaches only AFTER a
      // synchronous COMPLETED ingest — so it is already terminal; no poll on our
      // own uploadId (which never ran a Deployment) is possible or needed.
      return { deploymentId: existing, clubIds: [], reused: true };
    }
  }
  throw new Error(`bundle ingest failed (${status}): ${text}`);
}

/**
 * Poll {@code GET /api/v1/migrations/{uploadId}/status} until the run is terminal,
 * returning the COMPLETED view. A FAILED terminal throws (a real ingest failure
 * must surface, never silently pass). Bounded so a stuck async run can't hang the
 * worker forever; a synchronous 200 ingest is already COMPLETED, so this returns
 * on the first poll.
 */
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

/**
 * Bound the status poll — the synchronous ingest is COMPLETED on the FIRST read,
 * so this budget only matters for a future async ingest. Sized well under the
 * resolver's widened hook budget so a deployment that stalls non-terminal (e.g.
 * a server-side provision that left the run mid-flight) fails FAST and precisely
 * — never spinning long enough to blow the hook, throw, clear the shared-bundle
 * memo, and trigger a re-POST → 409 reuse → re-enumeration cascade.
 */
const STATUS_POLL_BUDGET_MS = 30_000;
const STATUS_POLL_INTERVAL_MS = 500;

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

/**
 * Per-club login budget for the ownership-detection loop (T-23).
 *
 * The real FLSTest DB migrates 4 clubs FULL_PORT, and we have to log each
 * provisioned admin in (no ROPC on `alpenflight-web`; `GET /api/v1/locations`
 * is `@TenantId`-scoped, so the migration principal can't read cross-tenant —
 * a per-tenant UI login is the only path to a tenant bearer). Iterating that
 * with the default 30s `waitForURL` is fragile: a single admin that can't reach
 * the authed root burns 30s, and 4×30s blows the 60s `beforeAll` budget. Bound
 * each attempt well below the default but generous enough for a healthy login
 * on a warm server (the principal login in this very fixture completes in a few
 * seconds). On timeout/error the club is treated as a NON-owner (it can't be
 * displaying the user-created Location if it can't even authenticate), never a
 * hard failure of the whole seed. An under-count of REAL owners still fails the
 * `expect(owners.length).toBe(2)` assertion downstream — never false-green.
 */
const PER_CLUB_LOGIN_BUDGET_MS = 12_000;

/**
 * Drive a migrated club admin through the SPA + real-KC login in a throwaway
 * context and capture the tenant-scoped Bearer the OIDC interceptor attaches to
 * its first `/api/v1/locations` call. This is the ONLY path to a per-tenant
 * token here: the `alpenflight-web` SPA client has no direct-access grant
 * (resource-owner-password flow is disabled), so a curl-token mint is
 * impossible — the login form is the only way to a tenant JWT (same constraint
 * `capturePrincipalBearer` works around for the migration principal).
 *
 * Bounded by `PER_CLUB_LOGIN_BUDGET_MS` (T-23). Returns the Bearer on success.
 * On timeout/error returns `undefined` — and crucially the pending
 * `waitForRequest` is SETTLED (awaited + swallowed) BEFORE the context closes,
 * so it cannot reject-and-escape with "Target page, context or browser has been
 * closed" when the failure path tears the context down (the round-15 teardown
 * race). The caller treats `undefined` as a non-owner.
 */
async function tryBearerForMigratedAdmin(
  browser: Browser,
  baseURL: string,
  admin: MigratedClubAdmin,
): Promise<string | undefined> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  // Attach the listener up front but NEVER let it float unhandled: a rejection
  // (e.g. the context closing under it) is caught here so it can't surface as
  // an unhandled rejection / escape past `context.close()`.
  const bearerPromise = page
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
    // `loginAsMigratedAdmin` lands on the authed root; navigate to the list to
    // guarantee the tenant-scoped GET /api/v1/locations fires.
    await page.goto('/locations');
    return await bearerPromise;
  } catch (err) {
    // A bounded login that never left /realms/ (VERIFY_PROFILE / cold ng-serve /
    // a genuinely broken admin) lands here. Log + treat as non-owner; do NOT
    // abort the seed.
    console.warn(
      `[T-23] club ${admin.clubId} admin did not reach the authed root within ` +
        `${PER_CLUB_LOGIN_BUDGET_MS}ms — treating as non-owner (${(err as Error).message})`,
    );
    return undefined;
  } finally {
    // Settle the bearer listener before tearing the page out from under it,
    // closing the round-15 teardown race regardless of which branch we took.
    await bearerPromise;
    await context.close();
  }
}

/**
 * `true` when this tenant (resolved off `bearer`'s `clubId` claim) carries a
 * Location with the random fan-out name — i.e. this is one of the clubs the
 * shared legacy Location fanned out into. Uses the SAME read API the parity
 * spec asserts on (`GET /api/v1/locations`), so "who owns the Location" is
 * judged by the product's own tenant-filtered list, not a side channel.
 */
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

/** The provisioned migrated-admin username namespace (KC `search` infix). */
const MIGRATED_ADMIN_USERNAME_INFIX = 'migrated-admin+';

/** Parse the provisioned clubId out of a `migrated-admin+<clubId>@…` username. */
function clubIdFromMigratedUsername(username: string): string | null {
  const m = /^migrated-admin\+([0-9a-f-]{36})@migrated\.alpenflight\.local$/i.exec(username);
  return m ? m[1]! : null;
}

/**
 * Partition a set of migrated club admins into the ones whose tenant actually
 * carries the random-named fan-out Location (`owners`) and the rest
 * (`nonOwners`), judged by the SAME tenant-scoped `GET /api/v1/locations` the
 * spec asserts on. Each admin is made loginable, logged in to capture its
 * `@TenantId` Bearer (bounded per club — T-23), and queried. Fault-isolated
 * per club: an un-loginable / slow club is a non-owner, never a hard throw, so
 * one bad club can't sink the whole partition. Shared by BOTH the fresh-ingest
 * path and the 409-reuse path so clubA/clubB are ALWAYS the true Location
 * owners, never an arbitrary KC-search-order pick (the J-10 reuse-path gap that
 * red-ed `fan-out-migration-parity.spec.ts:167` — club-B was a non-owner).
 */
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

/**
 * Build the fixture for the 409-reuse path (the bundle was already migrated by a
 * PRIOR ingest whose Deployment is still ACTIVE — the steady state of local-first
 * iteration on the LAN PG, AND the CI path when the first consumer's `beforeAll`
 * blows its 45s budget mid-ownership-loop, clearing the memo so the second
 * consumer re-POSTs and 409s).
 *
 * The reuse 409 carries no clubIds, so enumerate the provisioned migrated admins
 * and partition them by ACTUAL Location ownership — identical to the fresh path
 * — rather than picking the first two in KC-search order. The earlier pick-first-
 * two shortcut made `fixture.clubB` whatever KC returned second, which on the real
 * 4-club FLSTest could be a NON-owner: club-A (an owner) passed at :146 while
 * club-B (a non-owner) red-ed at :167 with the list visible but its row absent
 * (J-27 T-03b). `locationName` is the real env name here (real-bundle mode), so
 * the ownership lookup is valid. Fails loud on <2 owners — never papers a gap.
 */
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
  // The migration PRINCIPAL bearer is needed by BOTH modes (the bundle POST is
  // an authenticated, verified-email call). The synth path additionally uses it
  // to mint its own handshake; the real path reuses the workflow's handshake.
  const bearer = await capturePrincipalBearer(browser, baseURL);

  // `attempt` (Playwright's testInfo.retry) makes the synth seed unique per try
  // so a retry after a failed ingest re-handshakes with a fresh uploadId rather
  // than re-POSTing the FAILED upload (409 BUNDLE_PRIOR_RUN_FAILED). The real
  // path's bundle is sealed to the workflow's handshake, so a retry there is the
  // workflow's job to re-mint — the spec can't re-seal a pre-encrypted bundle.
  const { bundle, uploadId, locationName } = useRealBundle()
    ? await resolveRealBundle()
    : await resolveSynthBundle(api, bearer, attempt);

  const ingest = await ingestBundle(api, bearer, uploadId, bundle);

  // REUSE (409 DEPLOYMENT_EXISTS): the bundle was already migrated by a PRIOR
  // ingest whose Deployment is still ACTIVE. This branch is hit on local re-runs
  // (LAN PG steady state) AND on CI when the first migrated-data consumer's 45s
  // `beforeAll` blows mid-ownership-loop and clears the worker memo, so the second
  // consumer re-POSTs the (committed) bundle and 409s. `locationName` is the real
  // env name in real-bundle mode, so `reusedDeploymentFixture` resolves clubA/clubB
  // by the SAME Location-ownership partition the fresh path uses — never the first-
  // two-in-KC-order pick that red-ed club-B at :167 (J-27 T-03b).
  if (ingest.reused) {
    return reusedDeploymentFixture(browser, api, baseURL, locationName);
  }

  // Every migrated club gets a provisioned admin (FULL_PORT CLUB) — but only
  // the clubs that referenced the shared legacy Location (via Clubs.HomebaseId)
  // get a fanned-out `t_location` copy. So we CANNOT pick the 2 fan-out targets
  // by `clubIds[0]/[1]`:
  //   - synth bundle: declares exactly 2 clubs, both referencing → 2 clubIds,
  //     both own the Location.
  //   - real FLSTest: 4 clubs migrate, only TestClub + OtherClub reference the
  //     Location → 4 clubIds, exactly 2 own a copy. `IngestResponse` carries no
  //     club names and the provisioned admin username is derived from the
  //     clubId (not the legacy username), so there is no identity-based map back
  //     to TestClub/OtherClub.
  // Instead, identify the fan-out targets by WHICH tenants actually carry the
  // random-named Location: log in as each migrated admin, query that tenant's
  // own `/api/v1/locations` (the same read API the spec asserts on), and keep
  // the ones whose list contains it. Exactly-2-owning IS the fan-out assertion.
  //
  if (ingest.clubIds.length < 2) {
    throw new Error(
      `ingest provisioned ${ingest.clubIds.length} club(s) — need ≥2 to host a 2-way fan-out`,
    );
  }

  // Resolve each provisioned admin's KC identity, then partition by Location
  // ownership through the shared helper (same path the 409-reuse fixture uses, so
  // both paths pick clubA/clubB by ACTUAL ownership). Resolution is fault-isolated
  // per club: a clubId with no provisioned admin is skipped, never a hard throw —
  // under-counting a REAL owner still trips the `expect(owners.length).toBe(2)`.
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

  // A non-owning club (real mode has ≥1; synth mode has none) is a real subject
  // for a stronger cross-tenant check, but the current spec's 404 case already
  // proves isolation between the two OWNING tenants (GET club-B's copy as
  // club-A). Surface the first non-owner for any future case that wants a club
  // that should never see the Location; the spec consumes only clubA/clubB.
  // (`exactOptionalPropertyTypes` forbids `nonOwner: undefined` — omit the key
  // when there is no non-owner, e.g. the synth path's exactly-2-club bundle.)
  const nonOwner = nonOwners[0];
  return { locationName, clubA, clubB, ...(nonOwner ? { nonOwner } : {}) };
}

/**
 * Worker-scoped memo of the ONE shared real-bundle ingest+provision (J-8 T-17).
 *
 * The shared real legacy TestClub bundle (the J-0c fan-out export) carries the
 * rows EVERY migrated-data consumer reads — the migrated Location (J-0c), the
 * reservation (J-5), the planning rows (J-6), AND the AccountingRuleFilter rows
 * (J-8). It is sealed to ONE single-use `uploadId`, so it must be POSTed to
 * `.../bundle` EXACTLY ONCE per run; a second POST of the same uploadId 409s
 * (`BUNDLE_PRIOR_RUN_FAILED`). Memoizing the `seedFanOutParity` call in this
 * worker-scoped promise (real-idp is `workers:1`, so the whole real-bundle
 * invocation is ONE process → module state is shared across all specs) makes
 * the ingest order-INDEPENDENT: whichever migrated spec runs first triggers it,
 * everyone else awaits the cached promise. This closes the J-8 T-17 bug where
 * `accounting-rules-parity` (alphabetically BEFORE `fan-out-migration-parity`)
 * raced ahead of provisioning under Playwright's spec-sort, and removes the
 * latent alphabetical-luck fragility for reservations/planning too.
 *
 * Mirrors the `tokenPromise` worker-singleton pattern in `keycloak-admin.ts`.
 */
let sharedBundlePromise: Promise<FanOutParityFixture> | undefined;

/**
 * Ensure the shared real-bundle migration has been ingested + its per-club
 * admins provisioned, exactly once per worker. Idempotent: the first caller
 * runs `seedFanOutParity`; subsequent callers (any other migrated-data spec)
 * await the same cached promise rather than re-ingesting the single-use bundle.
 * On failure the memo is cleared so a retry (synth path) can re-attempt.
 *
 * REAL-bundle only: callers gate on `useRealBundle()` before invoking. The
 * `attempt` is always 0 in real mode (`retries:0`), so no per-retry uploadId
 * re-mint is needed here — that concern is synth-only, where `fan-out` calls
 * `seedFanOutParity` directly with `testInfo.retry`.
 */
export async function ensureSharedMigrationBundle(
  browser: Browser,
  baseURL: string,
): Promise<FanOutParityFixture> {
  if (sharedBundlePromise) return sharedBundlePromise;
  sharedBundlePromise = (async () => {
    const context = await browser.newContext({ baseURL });
    try {
      return await seedFanOutParity(browser, context.request, baseURL);
    } finally {
      await context.close();
    }
  })();
  try {
    return await sharedBundlePromise;
  } catch (err) {
    // Clear the memo on failure so a (synth-path) retry re-attempts rather than
    // re-throwing a stale rejected promise.
    sharedBundlePromise = undefined;
    throw err;
  }
}

/**
 * Log a migrated club admin in through the SPA + Keycloak login form, landing
 * on the authed root. The page's storageState is the per-club session — the
 * `clubId` claim (provisioned club UUID) resolves the tenant, and
 * `JitUserMaterializer` (S-169) projects the `t_user` on first login.
 */
export async function loginAsMigratedAdmin(
  page: Page,
  admin: MigratedClubAdmin,
  // T-23: ownership detection bounds the leave-/realms/ wait per club so a
  // single un-loginable admin can't burn 30s × N. The spec's own clubA/clubB
  // logins (proven owners — production `provisionClubAdminIdentity` stamps
  // firstName/lastName at mint time so VERIFY_PROFILE never fires) keep the
  // generous default.
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
