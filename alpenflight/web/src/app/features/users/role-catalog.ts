import type { UserUpdateRequestRolesItem } from '@api/generated/model';

/**
 * Roles the CLUB_ADMIN UI may grant. Mirrors
 * `ch.alpenflight.users.application.RoleAssignmentPolicy.CLUB_ADMIN_GRANTABLE`.
 * SYSTEM_ADMINISTRATOR is intentionally absent — backend 403s the grant; we
 * never offer the box. Keycloak built-ins + `proffix-sync` are stripped at
 * the wire boundary already.
 */
export const CLUB_ADMIN_GRANTABLE_ROLES: readonly UserUpdateRequestRolesItem[] = [
  'CLUB_ADMINISTRATOR',
  'FLIGHT_OPERATOR',
  'PILOT',
  'OFFICE_USER',
  'GUEST',
] as const;

export const MANAGED_ROLE_NAMES: ReadonlySet<string> = new Set<string>(CLUB_ADMIN_GRANTABLE_ROLES);

const ROLE_LABELS: Readonly<Record<string, string>> = {
  SYSTEM_ADMINISTRATOR: 'System administrator',
  CLUB_ADMINISTRATOR: 'Club administrator',
  FLIGHT_OPERATOR: 'Flight operator',
  PILOT: 'Pilot',
  OFFICE_USER: 'Office user',
  GUEST: 'Guest',
};

export function roleLabel(role: string): string {
  return ROLE_LABELS[role] ?? role;
}

/**
 * Compose the PUT-payload role set as
 * `(currentFromServer \ uiManagedRoles) ∪ checkedBoxes`.
 *
 * Load-bearing safety: preserves SYSTEM_ADMINISTRATOR and any future
 * out-of-band realm role on profile edit. Without this, a CLUB_ADMIN
 * editing a sysadmin's friendlyName would silently demote them — the
 * backend's `RoleAssignmentPolicy` only checks the *added* set against
 * the grant catalogue, not the *removed* set.
 */
export function mergeManagedRoles(
  currentFromServer: readonly UserUpdateRequestRolesItem[],
  checkedBoxes: readonly UserUpdateRequestRolesItem[],
): UserUpdateRequestRolesItem[] {
  const preserved = currentFromServer.filter((r) => !MANAGED_ROLE_NAMES.has(r));
  return Array.from(new Set<UserUpdateRequestRolesItem>([...preserved, ...checkedBoxes]));
}
