import type { AppRole } from '../../core/session/session.store';

export type StartVariant = 'pilot' | 'clubadmin' | 'sysadmin';

export type AdminVariant = Exclude<StartVariant, 'pilot'>;

export function roleVariant(roles: readonly AppRole[]): StartVariant {
  if (roles.includes('SYSTEM_ADMINISTRATOR')) return 'sysadmin';
  if (roles.includes('CLUB_ADMINISTRATOR')) return 'clubadmin';
  return 'pilot';
}

export function isAdminVariant(roles: readonly AppRole[]): boolean {
  return roleVariant(roles) !== 'pilot';
}

export function effectiveVariant(
  roles: readonly AppRole[],
  pilotViewOverride: boolean,
): StartVariant {
  if (pilotViewOverride && isAdminVariant(roles)) return 'pilot';
  return roleVariant(roles);
}
