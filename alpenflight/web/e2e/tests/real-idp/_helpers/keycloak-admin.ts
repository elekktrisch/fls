import { isCleanupCandidate, type TestUser } from './test-user';

/**
 * Minimal Keycloak Admin REST client for the real-idp suite.
 *
 * Uses the `alpenflight-backend-admin` confidential client's
 * client-credentials grant against the realm-local token endpoint
 * (`/realms/alpenflight/protocol/openid-connect/token`, NOT `/realms/master`
 * — the service account lives in `alpenflight`). Service-account roles are
 * scoped to `manage-realm` + `manage-users` + `view-users` + `query-users`
 * on `realm-management` only (S-019 base + S-175 `manage-realm` for
 * accessTokenLifespan mutation); never `manage-clients` / impersonation.
 *
 * Token caching is worker-scoped via the module-level promise — single
 * `workers: 1` for real-idp means one cached token per CI run. Refreshed
 * 30s before expiry, OR on any 401.
 *
 * **Localhost guard:** assertLocalhostIssuer() must fire before any admin
 * call. The dev secret cannot run against a non-localhost issuer; the
 * guard is the security boundary for the committed dev credential AND
 * the boundary that bounds the S-175 `manage-realm` scope expansion to
 * local dev + nightly CI.
 */

/**
 * Shortened `accessTokenLifespan` (seconds) used by the silent-refresh +
 * hard-401 specs to force a token-renewal cycle inside the test window.
 * Restored via `withRealmPatch`'s try/finally; globalTeardown sweeps drift.
 *
 * 30s is wide enough to absorb the SPA's `renewTimeBeforeTokenExpiresInSeconds
 * = 60` (auth.config.ts:48) — the renewal fires immediately on login, so any
 * lifespan above the renewal threshold collapses to the renewal interval. A
 * value < 60 simply guarantees the renewal-already-triggered path; 30s keeps
 * the spec deterministic without flirting with KC's minimum-lifespan rules.
 */
export const SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS = 30;

/**
 * Canonical realm-default `accessTokenLifespan` (seconds). Matches the
 * ADR 0007 + realm-export contract checked by `check-realm-shape.sh:142-147`.
 * globalTeardown PUTs this back if a worker SIGKILL skipped `withRealmPatch`'s
 * `finally`.
 */
export const CANONICAL_ACCESS_TOKEN_LIFESPAN_SECONDS = 900;

const ISSUER = process.env['E2E_KC_ISSUER'] ?? 'http://localhost:8090/realms/alpenflight';
const ADMIN_CLIENT_ID = 'alpenflight-backend-admin';
const ADMIN_CLIENT_SECRET_DEFAULT = 'alpenflight-backend-admin-dev-secret';
const ADMIN_CLIENT_SECRET =
  process.env['ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET'] ?? ADMIN_CLIENT_SECRET_DEFAULT;
const REALM = 'alpenflight';
const ADMIN_BASE = ISSUER.replace(`/realms/${REALM}`, '') + `/admin/realms/${REALM}`;
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`;

if (
  ADMIN_CLIENT_SECRET === ADMIN_CLIENT_SECRET_DEFAULT &&
  !process.env['ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET']
) {
  // Surface the silent fallback so an operator who intended to override but
  // typo'd the env-var name notices. Stderr so it doesn't pollute stdout.
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
  // Defense-in-depth: every admin request re-asserts the localhost
  // boundary. Module-scoped `ISSUER` is frozen at import time, but
  // the explicit call here means an audit reading any single function
  // sees the gate without having to trace the call graph back to setup.
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

export async function createUser(user: TestUser): Promise<string> {
  const res = await adminRequest('/users', {
    method: 'POST',
    body: JSON.stringify({
      username: user.email,
      email: user.email,
      firstName: user.firstName,
      lastName: user.lastName,
      enabled: true,
      emailVerified: true,
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
    const res = await adminRequest(`/users/${userId}`, { method: 'DELETE' });
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
 *
 * Specs never call `updateRealm` directly.
 */
export async function withRealmPatch<T>(
  partial: Record<string, unknown>,
  fn: () => Promise<T>,
): Promise<T> {
  if (realmMutexBusy) {
    throw new Error(
      'withRealmPatch: concurrent invocation refused. Wrap realm-mutating ' +
        'specs in `test.describe.configure({ mode: \'serial\' })` and ensure ' +
        'only one realm-mutating describe runs at a time.',
    );
  }
  realmMutexBusy = true;
  try {
    const realm = await getRealm();
    const snapshot: Record<string, unknown> = {};
    for (const key of Object.keys(partial)) {
      snapshot[key] = realm[key];
    }
    await updateRealm(partial);
    try {
      return await fn();
    } finally {
      // Restore the snapshot — keys absent in the snapshot (KC defaults)
      // are restored as `undefined`, which KC accepts and treats as the
      // default. Best-effort: a restore failure should not mask the
      // original test failure but must be loud.
      try {
        await updateRealm(snapshot);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error(
          `withRealmPatch: restore failed — ${(err as Error).message}. ` +
            'globalTeardown will reset accessTokenLifespan to the canonical value ' +
            'as the safety net.',
        );
      }
    }
  } finally {
    realmMutexBusy = false;
  }
}

/**
 * Toggle a user's `enabled` flag via PUT /users/{id}. Predicate-guarded
 * (`isCleanupCandidate(emailForGuard)`) identically to `deleteUser` —
 * seed users (`pilot1`, `clubadmin1`, `sysadmin`) cannot be disabled
 * through this helper even by typo.
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
  const res = await adminRequest(`/users/${userId}`, {
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
