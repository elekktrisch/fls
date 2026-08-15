import type { UserUpdateRequestRolesItem } from '@api/generated/model';

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

export function mergeManagedRoles(
  currentFromServer: readonly UserUpdateRequestRolesItem[],
  checkedBoxes: readonly UserUpdateRequestRolesItem[],
): UserUpdateRequestRolesItem[] {
  const preserved = currentFromServer.filter((r) => !MANAGED_ROLE_NAMES.has(r));
  return Array.from(new Set<UserUpdateRequestRolesItem>([...preserved, ...checkedBoxes]));
}
