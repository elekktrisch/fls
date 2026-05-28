import { isCleanupCandidate, E2E_CANNED_PASSWORD, type TestUser } from './test-user';

/**
 * Minimal Keycloak Admin REST client for the real-idp suite.
 *
 * Uses the `alpenflight-backend-admin` confidential client's
 * client-credentials grant against the realm-local token endpoint
 * (`/realms/alpenflight/protocol/openid-connect/token`, NOT `/realms/master`
 * — the service account lives in `alpenflight`). Service-account roles are
 * scoped to `manage-users` + `view-users` + `query-users` on
 * `realm-management` only (per S-019); never `manage-realm` / `manage-clients`.
 *
 * Token caching is worker-scoped via the module-level promise — single
 * `workers: 1` for real-idp means one cached token per CI run. Refreshed
 * 30s before expiry, OR on any 401.
 *
 * **Localhost guard:** assertLocalhostIssuer() must fire before any admin
 * call. The dev secret cannot run against a non-localhost issuer; the
 * guard is the security boundary for the committed dev credential.
 */

const ISSUER = process.env['E2E_KC_ISSUER'] ?? 'http://localhost:8090/realms/alpenflight';
const ADMIN_CLIENT_ID = 'alpenflight-backend-admin';
const ADMIN_CLIENT_SECRET =
  process.env['ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET'] ?? 'alpenflight-backend-admin-dev-secret';
const REALM = 'alpenflight';
const ADMIN_BASE = ISSUER.replace(`/realms/${REALM}`, '') + `/admin/realms/${REALM}`;
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`;

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
    const text = await res.text();
    throw new Error(`admin token mint failed (${res.status}): ${text}`);
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
  const res = await adminRequest(
    `/users?email=${encodeURIComponent(email)}&exact=true&max=2`,
  );
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

export const _testing = {
  invalidateToken,
  TOKEN_ENDPOINT,
  ADMIN_BASE,
  ISSUER,
  // Used by setup.ts to spell the canned password into the long-lived
  // `e2e-occupied` user without re-import.
  cannedPassword: E2E_CANNED_PASSWORD,
};
