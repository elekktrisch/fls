import { isCleanupCandidate, type TestUser } from './test-user';

export const ACCESS_TOKEN_LIFESPAN_BELOW_SPA_RENEW_WINDOW_SECONDS = 30;

export const CANONICAL_ACCESS_TOKEN_LIFESPAN_SECONDS = 900;

const ISSUER = process.env['E2E_KC_ISSUER'] ?? 'http://localhost:8090/realms/alpenflight';
const ADMIN_CLIENT_ID = 'alpenflight-backend-admin';
const ADMIN_CLIENT_SECRET_DEFAULT = 'alpenflight-backend-admin-dev-secret';
const ADMIN_CLIENT_SECRET_OVERRIDE = process.env['ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET'];
const ADMIN_CLIENT_SECRET =
  ADMIN_CLIENT_SECRET_OVERRIDE && ADMIN_CLIENT_SECRET_OVERRIDE.length > 0
    ? ADMIN_CLIENT_SECRET_OVERRIDE
    : ADMIN_CLIENT_SECRET_DEFAULT;
const REALM = 'alpenflight';
const ADMIN_BASE = ISSUER.replace(`/realms/${REALM}`, '') + `/admin/realms/${REALM}`;
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`;

if (ADMIN_CLIENT_SECRET === ADMIN_CLIENT_SECRET_DEFAULT) {
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
  attributes?: Record<string, string[]>;
}

const TOKEN_REFRESH_LEEWAY_SECONDS = 30;

interface CachedToken {
  accessToken: string;
  expiresAtEpochMs: number;
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
    throw new Error(`admin token mint failed (${res.status} ${res.statusText})`);
  }
  const payload = (await res.json()) as { access_token: string; expires_in: number };
  return {
    accessToken: payload.access_token,
    expiresAtEpochMs: Date.now() + (payload.expires_in - TOKEN_REFRESH_LEEWAY_SECONDS) * 1000,
  };
}

async function getToken(): Promise<string> {
  if (tokenPromise) {
    const cached = await tokenPromise;
    if (cached.expiresAtEpochMs > Date.now()) return cached.accessToken;
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
  retryOnUnauthorized = true,
): Promise<Response> {
  assertLocalhostIssuer();
  const token = await getToken();
  const headers = new Headers(init.headers ?? {});
  headers.set('authorization', `Bearer ${token}`);
  if (init.body !== undefined && !headers.has('content-type')) {
    headers.set('content-type', 'application/json');
  }
  const res = await fetch(`${ADMIN_BASE}${path}`, { ...init, headers });
  if (res.status === 401 && retryOnUnauthorized) {
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
  const createdUserLocation = res.headers.get('location');
  if (!createdUserLocation) throw new Error('createUser: no Location header on 201');
  return createdUserLocation.split('/').pop()!;
}

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

export async function findUsersByUsernameSearch(search: string, max = 50): Promise<AdminUser[]> {
  const res = await adminRequest(`/users?search=${encodeURIComponent(search)}&max=${max}`);
  if (!res.ok) {
    throw new Error(
      `findUsersByUsernameSearch(${search}) failed (${res.status}): ${await res.text()}`,
    );
  }
  return (await res.json()) as AdminUser[];
}

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
  const pwRes = await adminRequest(`/users/${encodeURIComponent(userId)}/reset-password`, {
    method: 'PUT',
    body: JSON.stringify({ type: 'password', value: password, temporary: false }),
  });
  if (!pwRes.ok) {
    throw new Error(
      `makeMigratedAdminLoginable: reset-password failed (${pwRes.status}): ${await pwRes.text()}`,
    );
  }
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

export async function findRealmRole(roleName: string): Promise<RealmRoleRepresentation> {
  const res = await adminRequest(`/roles/${encodeURIComponent(roleName)}`);
  if (!res.ok) {
    throw new Error(`findRealmRole(${roleName}) failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()) as RealmRoleRepresentation;
}

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

export async function sweepE2eUsers(): Promise<number> {
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
      // eslint-disable-next-line no-console
      console.warn(`sweep: failed to delete ${user.email} (${user.id}): ${(err as Error).message}`);
    }
  }
  return deleted;
}

export async function getRealm(): Promise<Record<string, unknown>> {
  const res = await adminRequest('');
  if (!res.ok) {
    throw new Error(`getRealm failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()) as Record<string, unknown>;
}

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
