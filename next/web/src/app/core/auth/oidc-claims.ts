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

export function mapClaimsToUser(claims: unknown): User | null {
  throw new Error('S-021 stub: mapClaimsToUser');
}

// Exported only so the implementation can drop the unused import once
// real bodies land. Keeps eslint quiet during the red test phase.
export const _appRoles: ReadonlySet<AppRole> = KNOWN_ROLES;
