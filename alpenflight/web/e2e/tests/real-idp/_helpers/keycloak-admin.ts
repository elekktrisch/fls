import { isCleanupCandidate, type TestUser } from './test-user';

/**
 * Minimal Keycloak Admin REST client for the real-idp suite.
 *
 * Uses the `alpenflight-backend-admin` confidential client's
 * client-credentials grant against the realm-local token endpoint
 * (`/realms/alpenflight/protocol/openid-connect/token`, NOT `/realms/master`
 * — the service account lives in `alpenflight`). Service-account scope is
 * enumerated + enforced in `alpenflight/auth/scripts/check-realm-shape.sh`
 * (`EXPECTED_SA_ROLES`); this file does not duplicate the list.
 *
 * Token caching is worker-scoped via the module-level promise — single
 * `workers: 1` for real-idp means one cached token per CI run. Refreshed
 * 30s before expiry, OR on any 401.
 *
 * **Localhost guard:** `assertLocalhostIssuer()` must fire before any admin
 * call. The committed dev secret cannot reach a non-localhost issuer; the
 * guard is also the boundary that bounds the realm-mutation scope (the
 * `manage-realm` grant) to local dev + nightly CI.
 */

/**
 * Shortened `accessTokenLifespan` (seconds) used by the silent-refresh +
 * hard-401 specs to force a token-renewal cycle inside the test window.
 * Restored via `withRealmPatch`'s try/finally; globalTeardown sweeps drift.
 *
 * Sized to sit below the SPA's `renewTimeBeforeTokenExpiresInSeconds = 60`
 * (auth.config.ts) so renewal fires immediately on login — guarantees the
 * "renewal already triggered" path without flirting with KC's minimum-
 * lifespan rules.
 */
export const SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS = 30;

/**
 * Canonical realm-default `accessTokenLifespan` (seconds). Matches the
 * ADR 0007 + realm-export contract checked by `check-realm-shape.sh`.
 * globalTeardown PUTs this back if a worker SIGKILL skipped
 * `withRealmPatch`'s `finally`.
 */
export const CANONICAL_ACCESS_TOKEN_LIFESPAN_SECONDS = 900;

const ISSUER = process.env['E2E_KC_ISSUER'] ?? 'http://localhost:8090/realms/alpenflight';
const ADMIN_CLIENT_ID = 'alpenflight-backend-admin';
const ADMIN_CLIENT_SECRET_DEFAULT = 'alpenflight-backend-admin-dev-secret';
const ADMIN_CLIENT_SECRET_OVERRIDE = process.env['ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET'];
// Treat exported-but-empty (`export ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET=`) as
// "no override" so a typo'd env-var assignment doesn't silently auth with
// an empty secret AND suppress the dev-fallback warn.
const ADMIN_CLIENT_SECRET =
  ADMIN_CLIENT_SECRET_OVERRIDE && ADMIN_CLIENT_SECRET_OVERRIDE.length > 0
    ? ADMIN_CLIENT_SECRET_OVERRIDE
    : ADMIN_CLIENT_SECRET_DEFAULT;
const REALM = 'alpenflight';
const ADMIN_BASE = ISSUER.replace(`/realms/${REALM}`, '') + `/admin/realms/${REALM}`;
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`;

if (ADMIN_CLIENT_SECRET === ADMIN_CLIENT_SECRET_DEFAULT) {
  // Surface the silent fallback so an operator who intended to override but
  // typo'd the env-var name (or exported it empty) notices. Stderr so it
  // doesn't pollute stdout.
  // eslint-disable-next-line no-console
  console.warn(
    `[real-idp] using committed dev secret for ${ADMIN_CLIENT_ID}; ` +
      `set ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET to override.`,
  );
}

export interface AdminUser {
  id: string;
  username: string;
  email?: string;
  enabled?: boolean;
  emailVerified?: boolean;
  // KC represents every user-attribute value as a string array; a scalar
  // attribute (e.g. `clubId`) is a single-element array.
  attributes?: Record<string, string[]>;
}

interface CachedToken {
  accessToken: string;
  expiresAt: number; // ms epoch
}

let tokenPromise: Promise<CachedToken> | undefined;

export function assertLocalhostIssuer(): void {
  const u = new URL(ISSUER);
  if (u.hostname !== 'localhost' && u.hostname !== '127.0.0.1') {
    throw new Error(
      `refusing to run with non-localhost issuer '${ISSUER}' — the committed dev secret ` +
        `'${ADMIN_CLIENT_ID}-dev-secret' must never reach a deployed Keycloak. Set ` +
        `E2E_KC_ISSUER + ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET explicitly to override.`,
    );
  }
}

async function mintToken(): Promise<CachedToken> {
  const body = new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: ADMIN_CLIENT_ID,
    client_secret: ADMIN_CLIENT_SECRET,
  });
  const res = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!res.ok) {
    // Status only — KC's response body may include the realm + client id
    // on a 400/401; not the secret, but artifact-noise we don't need.
    throw new Error(`admin token mint failed (${res.status} ${res.statusText})`);
  }
  const payload = (await res.json()) as { access_token: string; expires_in: number };
  return {
    accessToken: payload.access_token,
    // Refresh 30s before expiry to avoid mid-request invalidation.
    expiresAt: Date.now() + (payload.expires_in - 30) * 1000,
  };
}

async function getToken(): Promise<string> {
  if (tokenPromise) {
    const cached = await tokenPromise;
    if (cached.expiresAt > Date.now()) return cached.accessToken;
  }
  tokenPromise = mintToken();
  return (await tokenPromise).accessToken;
}

function invalidateToken(): void {
  tokenPromise = undefined;
}

async function adminRequest(
  path: string,
  init: RequestInit = {},
  // One-shot retry on 401: covers a token that expired between cache
  // check and Keycloak hand-off without a complex token-binding handshake.
  retry = true,
): Promise<Response> {
  // Re-asserted per-call so an audit reading any single function sees the gate.
  assertLocalhostIssuer();
  const token = await getToken();
  const headers = new Headers(init.headers ?? {});
  headers.set('authorization', `Bearer ${token}`);
  if (init.body !== undefined && !headers.has('content-type')) {
    headers.set('content-type', 'application/json');
  }
  const res = await fetch(`${ADMIN_BASE}${path}`, { ...init, headers });
  if (res.status === 401 && retry) {
    invalidateToken();
    return adminRequest(path, init, false);
  }
  return res;
}

export async function findUserByEmail(email: string): Promise<AdminUser | undefined> {
  const res = await adminRequest(`/users?email=${encodeURIComponent(email)}&exact=true&max=2`);
  if (!res.ok) {
    throw new Error(`findUserByEmail(${email}) failed (${res.status}): ${await res.text()}`);
  }
  const users = (await res.json()) as AdminUser[];
  if (users.length > 1) {
    throw new Error(`expected ≤1 user with email '${email}', found ${users.length}`);
  }
  return users[0];
}

/**
 * Read one user's full representation by id, including `attributes`. The
 * enumeration surface (`GET /users?email=`) used by {@link findUserByEmail}
 * omits attributes; the single-user `GET /users/{id}` is the one that returns
 * them, so the S-181 bind-existing assertion ("the `clubId` attribute is now
 * set on the invitee") reads through here.
 */
export async function getUserById(userId: string): Promise<AdminUser> {
  const res = await adminRequest(`/users/${encodeURIComponent(userId)}`);
  if (!res.ok) {
    throw new Error(`getUserById(${userId}) failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()) as AdminUser;
}

export async function createUser(user: TestUser): Promise<string> {
  return createUserWithAttributes(user, {});
}

/**
 * Create a user with arbitrary realm user-attributes. The realm's
 * `clubId` client scope projects the `clubId` user-attribute as a `clubId`
 * claim on the access token (realm-export.json `clubId-mapper`); the
 * backend's `ClubTenantIdentifierResolver` parses that claim as the tenant
 * UUID directly when it is a valid UUID. So a CLUB_ADMINISTRATOR bound to a
 * real `t_club` id only needs `{ clubId: ['<club-uuid>'] }` here — no
 * `t_user` seed row is required for tenant resolution.
 *
 * Attribute values are string arrays per KC's representation; callers pass
 * single-element arrays for scalar attributes.
 */
export async function createUserWithAttributes(
  user: TestUser,
  attributes: Record<string, string[]>,
): Promise<string> {
  const res = await adminRequest('/users', {
    method: 'POST',
    body: JSON.stringify({
      username: user.email,
      email: user.email,
      firstName: user.firstName,
      lastName: user.lastName,
      enabled: true,
      emailVerified: true,
      attributes,
      credentials: [{ type: 'password', value: user.password, temporary: false }],
    }),
  });
  if (!res.ok) {
    throw new Error(`createUser(${user.email}) failed (${res.status}): ${await res.text()}`);
  }
  // Keycloak puts the new user id in the Location header tail.
  const location = res.headers.get('location');
  if (!location) throw new Error('createUser: no Location header on 201');
  return location.split('/').pop()!;
}

/**
 * Look a user up by username (exact). The migration-provisioned club admins
 * (`migrated-admin+<clubId>@migrated.alpenflight.local`) carry their email ==
 * username but with `emailVerified:false`, and they live OUTSIDE the
 * `e2e-…@example.com` cleanup namespace — so the email-keyed lookup +
 * cleanup-predicate path doesn't apply to them. Returns `undefined` when no
 * user carries the username.
 */
export async function findUserByUsername(username: string): Promise<AdminUser | undefined> {
  const res = await adminRequest(
    `/users?username=${encodeURIComponent(username)}&exact=true&max=2`,
  );
  if (!res.ok) {
    throw new Error(`findUserByUsername(${username}) failed (${res.status}): ${await res.text()}`);
  }
  const users = (await res.json()) as AdminUser[];
  if (users.length > 1) {
    throw new Error(`expected ≤1 user with username '${username}', found ${users.length}`);
  }
  return users[0];
}

/**
 * Enumerate users whose username matches `search` (KC's `search` is a
 * substring/infix match across username + email + names). Used by the J-5
 * migrated-read to discover EVERY provisioned `migrated-admin+<clubId>@…`
 * identity, because the migrated CLUB does NOT keep its legacy UUID — the
 * ingest reconciles it onto a provisioning-minted t_club (a fresh UUID;
 * `EntityStreamIngestor`: "CLUB reconciles onto the provisioning-minted
 * t_club … rewrite the row's own legacy id to the provisioned id"), so the
 * admin username is keyed by the NEW UUID, not `0fa7b76f-…`. The caller picks
 * the right club by which tenant actually carries the migrated reservation
 * (the J-0c ownership-detection pattern). `max` bounds the page; the migrated
 * realm has a handful of clubs.
 */
export async function findUsersByUsernameSearch(search: string, max = 50): Promise<AdminUser[]> {
  const res = await adminRequest(`/users?search=${encodeURIComponent(search)}&max=${max}`);
  if (!res.ok) {
    throw new Error(
      `findUsersByUsernameSearch(${search}) failed (${res.status}): ${await res.text()}`,
    );
  }
  return (await res.json()) as AdminUser[];
}

/**
 * Make a migration-provisioned club admin loginable for the proof spec.
 *
 * Production `provisionClubAdminIdentity` provisions each migrated club admin
 * with the `UPDATE_PASSWORD` required action and NO usable password (S-082
 * reset-mail is out of that slice's scope). This helper does the test-only
 * setup the operator would otherwise do by hand: set a known password
 * (non-temporary) and clear the pending required actions, so the Playwright
 * login form completes in one shot.
 *
 * firstName/lastName are NOT set here (J-1 T-06): production
 * `provisionClubAdminIdentity` now stamps them at mint time, so the migrated
 * admin already satisfies the realm's declarative user-profile and never hits
 * the VERIFY_PROFILE interstitial. The earlier name fixup masked that prod gap
 * and is removed.
 *
 * This does NOT weaken production behavior — it only mutates the test user's
 * own credential + required-action list via the Admin REST surface, exactly as
 * `two-club-fixture` / `token-lifecycle.spec` mint loginable users. The
 * username guard pins it to the `migrated-admin+…@migrated.alpenflight.local`
 * synthetic namespace so it can never touch a seeded or `e2e-` user by typo.
 */
export async function makeMigratedAdminLoginable(
  userId: string,
  username: string,
  password: string,
): Promise<void> {
  if (
    !username.startsWith('migrated-admin+') ||
    !username.endsWith('@migrated.alpenflight.local')
  ) {
    throw new Error(
      `makeMigratedAdminLoginable: refusing to mutate '${username}' — only the synthetic ` +
        `migrated-admin+<clubId>@migrated.alpenflight.local namespace is allowed.`,
    );
  }
  // (1) Set a known, non-temporary password via the dedicated reset-password
  //     surface (PUT /users/{id}/reset-password).
  const pwRes = await adminRequest(`/users/${encodeURIComponent(userId)}/reset-password`, {
    method: 'PUT',
    body: JSON.stringify({ type: 'password', value: password, temporary: false }),
  });
  if (!pwRes.ok) {
    throw new Error(
      `makeMigratedAdminLoginable: reset-password failed (${pwRes.status}): ${await pwRes.text()}`,
    );
  }
  // (2) Clear the UPDATE_PASSWORD required action (else KC forces the
  //     change-password screen mid-login) and mark the email verified so the
  //     `email_verified` claim is true for any later verified-email gate.
  //     PUT /users/{id} is a partial merge — only these two keys change.
  //     firstName/lastName are intentionally NOT touched: production
  //     provisioning already sets them, so VERIFY_PROFILE never fires.
  const userRes = await adminRequest(`/users/${encodeURIComponent(userId)}`, {
    method: 'PUT',
    body: JSON.stringify({
      requiredActions: [],
      emailVerified: true,
    }),
  });
  if (!userRes.ok) {
    throw new Error(
      `makeMigratedAdminLoginable: clear required-actions failed (${userRes.status}): ` +
        `${await userRes.text()}`,
    );
  }
}

interface RealmRoleRepresentation {
  id: string;
  name: string;
}

/**
 * Fetch a realm-role representation by name. The role must already exist in
 * the realm-export (the `manage-users`/`manage-realm` service-account scope
 * does NOT include role creation — this helper assigns existing roles only).
 */
export async function findRealmRole(roleName: string): Promise<RealmRoleRepresentation> {
  const res = await adminRequest(`/roles/${encodeURIComponent(roleName)}`);
  if (!res.ok) {
    throw new Error(`findRealmRole(${roleName}) failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()) as RealmRoleRepresentation;
}

/**
 * Assign an existing realm role to a user via the role-mappings REST surface
 * (`POST /users/{id}/role-mappings/realm`). Idempotent on KC's side — a
 * repeat assignment of an already-held role is a no-op 204.
 */
export async function assignRealmRole(userId: string, roleName: string): Promise<void> {
  const role = await findRealmRole(roleName);
  const res = await adminRequest(`/users/${encodeURIComponent(userId)}/role-mappings/realm`, {
    method: 'POST',
    body: JSON.stringify([{ id: role.id, name: role.name }]),
  });
  if (!res.ok) {
    throw new Error(
      `assignRealmRole(${userId}, ${roleName}) failed (${res.status}): ${await res.text()}`,
    );
  }
}

/**
 * Idempotent variant — returns the existing user id if one already matches,
 * else creates fresh. Used by `setup.ts` for `e2e-occupied@example.com`.
 */
export async function ensureUser(user: TestUser): Promise<string> {
  const existing = await findUserByEmail(user.email);
  if (existing) return existing.id;
  return await createUser(user);
}

export async function deleteUser(userId: string, emailForGuard?: string): Promise<void> {
  if (!isCleanupCandidate(emailForGuard)) {
    throw new Error(
      `cleanup-predicate violation: refusing to DELETE user '${userId}' with email ` +
        `'${emailForGuard}'. Predicate requires email.startsWith('e2e-') && ` +
        `email.endsWith('@example.com').`,
    );
  }
  // Retry-on-404 (3×, 500ms): KC's POST /users returns 201 before all
  // session writes flush. afterEach can race the create.
  for (let attempt = 0; attempt < 3; attempt++) {
    const res = await adminRequest(`/users/${encodeURIComponent(userId)}`, { method: 'DELETE' });
    if (res.ok || res.status === 204) return;
    if (res.status !== 404) {
      throw new Error(`deleteUser(${userId}) failed (${res.status}): ${await res.text()}`);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`deleteUser(${userId}): user not found after 3 retries`);
}

/**
 * Sweep every `e2e-*@example.com` user in the realm. Called by
 * globalTeardown as the safety net for suite-crash leaks where afterEach
 * never ran.
 */
export async function sweepE2eUsers(): Promise<number> {
  // Server-side filter (`search=e2e-`) narrows to candidates; client-side
  // re-check enforces the full prefix+suffix predicate.
  const res = await adminRequest('/users?search=e2e-&max=200');
  if (!res.ok) {
    throw new Error(`sweep failed (${res.status}): ${await res.text()}`);
  }
  const users = (await res.json()) as AdminUser[];
  let deleted = 0;
  for (const user of users) {
    if (!isCleanupCandidate(user.email)) continue;
    try {
      await deleteUser(user.id, user.email);
      deleted++;
    } catch (err) {
      // Best-effort sweep — keep going, but surface so the operator sees
      // partial cleanup in the test report.
      // eslint-disable-next-line no-console
      console.warn(`sweep: failed to delete ${user.email} (${user.id}): ${(err as Error).message}`);
    }
  }
  return deleted;
}

/**
 * Fetch the realm representation. Returns the raw JSON KC ships — callers
 * pick the keys they need (e.g. `accessTokenLifespan`) and pass partials
 * back through `updateRealm` / `withRealmPatch`. Never inspect outside the
 * helper file's contract — the realm doc is large and the surface narrow.
 */
export async function getRealm(): Promise<Record<string, unknown>> {
  const res = await adminRequest('');
  if (!res.ok) {
    throw new Error(`getRealm failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()) as Record<string, unknown>;
}

/**
 * Apply a partial PUT against the realm representation. The KC Admin REST
 * contract treats `PUT /admin/realms/{realm}` as a merge — keys NOT present
 * in the body are left untouched. Callers should only ever route through
 * `withRealmPatch`; this is its primitive.
 */
async function updateRealm(partial: Record<string, unknown>): Promise<void> {
  const res = await adminRequest('', {
    method: 'PUT',
    body: JSON.stringify(partial),
  });
  if (!res.ok) {
    throw new Error(`updateRealm failed (${res.status}): ${await res.text()}`);
  }
}

let realmMutexBusy = false;

/**
 * Snapshot-apply-restore HOF for realm-policy mutation. Captures the keys
 * named in `partial` BEFORE applying, runs `fn` with the patch live, and
 * restores the snapshot in `finally` (even on thrown assertions). Module-
 * scoped mutex refuses concurrent invocation — a dev-machine net; `workers:
 * 1` makes contention impossible in CI.
 *
 * Restore target is the value captured at call time, NOT a hardcoded
 * constant — protects against future ADR 0007 token-policy bumps where the
 * canonical lifespan has drifted from 900s.
 */
export async function withRealmPatch<T>(
  partial: Record<string, unknown>,
  fn: () => Promise<T>,
): Promise<T> {
  if (realmMutexBusy) {
    throw new Error(
      'withRealmPatch: concurrent invocation refused. Wrap realm-mutating ' +
        "specs in `test.describe.configure({ mode: 'serial' })` and ensure " +
        'only one realm-mutating describe runs at a time.',
    );
  }
  realmMutexBusy = true;
  try {
    const realm = await getRealm();
    // Refuse to patch a key that has no prior value: `JSON.stringify` strips
    // `undefined`, so a restore PUT would silently no-op and leave the
    // patched value live. Today's callers patch `accessTokenLifespan` which
    // is always set on the realm — refuse upfront if a future caller hits
    // the foot-gun.
    const snapshot: Record<string, unknown> = {};
    for (const key of Object.keys(partial)) {
      if (realm[key] === undefined) {
        throw new Error(
          `withRealmPatch: refusing to patch '${key}' — realm has no prior value, ` +
            'so the restore PUT would silently no-op. Set a default in realm-export ' +
            'first OR extend this helper with a documented restore strategy for the key.',
        );
      }
      snapshot[key] = realm[key];
    }
    await updateRealm(partial);
    try {
      return await fn();
    } finally {
      // Best-effort restore: a failure here must not mask the test result.
      // globalTeardown's `restoreCanonicalAccessTokenLifespan` is the safety
      // net if this branch loses.
      try {
        await updateRealm(snapshot);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error(
          `withRealmPatch: restore failed — ${(err as Error).message}. ` +
            'globalTeardown will reset accessTokenLifespan to the canonical value.',
        );
      }
    }
  } finally {
    realmMutexBusy = false;
  }
}

/**
 * Toggle a user's `enabled` flag via PUT /users/{id}. Predicate-guarded
 * identically to `deleteUser` — seed users (pilot1, clubadmin1, sysadmin)
 * cannot be disabled through this helper even by typo.
 *
 * Body is pinned to `{ enabled }` only. Do NOT widen this signature without
 * a parallel review of the predicate guard — KC's PUT /users/{id} is a
 * partial-merge surface and a wider payload could overwrite other fields.
 */
export async function setUserEnabled(
  userId: string,
  enabled: boolean,
  emailForGuard: string,
): Promise<void> {
  if (!isCleanupCandidate(emailForGuard)) {
    throw new Error(
      `cleanup-predicate violation: refusing to set enabled=${enabled} on user '${userId}' ` +
        `with email '${emailForGuard}'. Predicate requires email.startsWith('e2e-') && ` +
        `email.endsWith('@example.com').`,
    );
  }
  const res = await adminRequest(`/users/${encodeURIComponent(userId)}`, {
    method: 'PUT',
    body: JSON.stringify({ enabled }),
  });
  if (!res.ok) {
    throw new Error(
      `setUserEnabled(${userId}, ${enabled}) failed (${res.status}): ${await res.text()}`,
    );
  }
}

/**
 * Poll `GET /users/{id}` until the user's `enabled` flag reads `false`.
 *
 * `setUserEnabled(userId, false, …)` is a fire-and-forget PUT: it returns as
 * soon as KC accepts the write, which is BEFORE the disable is guaranteed
 * visible to the refresh-grant handler that the disabled-user redirect spec
 * depends on. Reading the user store back until `enabled === false` gives the
 * assertion a propagation barrier — the redirect step only proceeds once KC's
 * own user store confirms the disable, instead of gambling on a fixed
 * wallclock wait that races the refresh-grant rejection.
 *
 * Bounded: `attempts` reads spaced `intervalMs` apart (default ~10s total).
 * Throws with a clear message on timeout so the failure names the propagation
 * barrier, not the downstream `waitForURL`.
 */
export async function pollUserDisabled(
  userId: string,
  opts: { attempts?: number; intervalMs?: number } = {},
): Promise<void> {
  const attempts = opts.attempts ?? 20;
  const intervalMs = opts.intervalMs ?? 500;
  for (let i = 0; i < attempts; i++) {
    const user = await getUserById(userId);
    if (user.enabled === false) return;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(
    `pollUserDisabled(${userId}): user still enabled after ${attempts} reads ` +
      `(~${(attempts * intervalMs) / 1000}s) — KC disable did not propagate.`,
  );
}

/**
 * Best-effort restore of `accessTokenLifespan` to the canonical value. Used
 * by globalTeardown as the safety net for a SIGKILL'd worker that skipped
 * `withRealmPatch`'s `finally`. Idempotent: PUTs only if the live value has
 * drifted from canonical.
 */
export async function restoreCanonicalAccessTokenLifespan(): Promise<boolean> {
  const realm = await getRealm();
  const live = realm['accessTokenLifespan'];
  if (live === CANONICAL_ACCESS_TOKEN_LIFESPAN_SECONDS) return false;
  await updateRealm({ accessTokenLifespan: CANONICAL_ACCESS_TOKEN_LIFESPAN_SECONDS });
  return true;
}

export const _testing = {
  invalidateToken,
  TOKEN_ENDPOINT,
  ADMIN_BASE,
  ISSUER,
};
