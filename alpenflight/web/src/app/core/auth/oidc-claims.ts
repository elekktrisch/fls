import type { AppRole, User } from '../session/session.store';

export interface KeycloakClaims {
  sub?: unknown;
  preferred_username?: unknown;
  email?: unknown;
  given_name?: unknown;
  family_name?: unknown;
  clubId?: unknown;
  realm_access?: { roles?: unknown };
}

const KNOWN_ROLES: ReadonlySet<AppRole> = new Set<AppRole>([
  'SYSTEM_ADMINISTRATOR',
  'CLUB_ADMINISTRATOR',
  'FLIGHT_OPERATOR',
  'PILOT',
  'OFFICE_USER',
  'GUEST',
]);

function asString(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function asNullableString(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null;
}

function extractRoles(claims: KeycloakClaims): readonly AppRole[] {
  const raw = claims.realm_access?.roles;
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw.filter((r): r is AppRole => typeof r === 'string' && KNOWN_ROLES.has(r as AppRole));
}

/**
 * Translates a Keycloak access-token claim payload into the SessionStore's
 * User shape. Returns `null` when the payload is not a plausible principal
 * (missing `sub`, wrong type, etc.); callers should treat that as "logged
 * out" rather than "logged in with empty fields."
 */
export function mapClaimsToUser(claims: unknown): User | null {
  if (claims === null || typeof claims !== 'object') {
    return null;
  }
  const c = claims as KeycloakClaims;
  const sub = asString(c.sub);
  if (!sub) {
    return null;
  }
  return {
    id: sub,
    username: asString(c.preferred_username),
    email: asString(c.email),
    firstName: asString(c.given_name),
    lastName: asString(c.family_name),
    clubId: asNullableString(c.clubId),
    roles: extractRoles(c),
  };
}
