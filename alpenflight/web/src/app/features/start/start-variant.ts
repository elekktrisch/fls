import type { AppRole } from '../../core/session/session.store';

/**
 * The three role variants the `/start` shell can render. The pilot variant is
 * the shipped S-165 dashboard; clubadmin (S-166) + sysadmin (S-167) bodies are
 * filled by T-09 / T-11 — T-07 only routes to their containers.
 */
export type StartVariant = 'pilot' | 'clubadmin' | 'sysadmin';

/**
 * The variant an admin (club-admin or sysadmin) gets by their own role, before
 * any "Pilot view" override. A pilot has no role-variant to override away from.
 */
export type AdminVariant = Exclude<StartVariant, 'pilot'>;

/**
 * Variant a principal's roles map to, by precedence: sysadmin > clubadmin >
 * pilot. A user holding several roles sees the highest. Mirrors the
 * `SessionStore.isSystemAdmin` / `isClubAdmin` membership checks
 * (`roles.includes(...)`) so the shell and the store agree on who is what.
 *
 * Pilot is the default — any non-admin role (PILOT / FLIGHT_OPERATOR /
 * OFFICE_USER / GUEST), or no roles at all, lands here.
 */
export function roleVariant(roles: readonly AppRole[]): StartVariant {
  if (roles.includes('SYSTEM_ADMINISTRATOR')) return 'sysadmin';
  if (roles.includes('CLUB_ADMINISTRATOR')) return 'clubadmin';
  return 'pilot';
}

/**
 * Whether the principal is an admin (their role variant is not the pilot one),
 * i.e. whether the shell should offer the "Pilot view" fallback toggle.
 */
export function isAdminVariant(roles: readonly AppRole[]): boolean {
  return roleVariant(roles) !== 'pilot';
}

/**
 * The variant the shell actually renders: the role variant, unless an admin has
 * toggled into "Pilot view" — then the pilot variant overrides. The override is
 * inert for a pilot (they're already on the pilot variant), so it never hides a
 * pilot's own dashboard.
 */
export function effectiveVariant(
  roles: readonly AppRole[],
  pilotViewOverride: boolean,
): StartVariant {
  if (pilotViewOverride && isAdminVariant(roles)) return 'pilot';
  return roleVariant(roles);
}
