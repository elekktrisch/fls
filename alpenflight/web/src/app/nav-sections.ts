import type { NavItem } from '@ui/organisms/af-nav-bar';

// Tenant-scoped nav (require a managing club; hidden for sysadmin).
export const TENANT_SECTIONS: readonly NavItem[] = [
  { path: '/flights', label: 'Flights', icon: 'plane' },
  // Directly after Flights = legacy parity: flsweb's nav put FLIGHTREPORTS
  // right after the start-list entry, visible to every logged-in user (no
  // role gate — J-7 deliberately serves the PILOT own-flights report).
  { path: '/flightreports', label: 'Reports', icon: 'file-text' },
  { path: '/reservations', label: 'Reservations', icon: 'calendar' },
  { path: '/planning', label: 'Planning', icon: 'calendar' },
  { path: '/aircraft', label: 'Aircraft', icon: 'plane' },
  { path: '/locations', label: 'Locations', icon: 'map-pin' },
  { path: '/persons', label: 'Persons', icon: 'users' },
  // Legacy parity: flsweb's masterdata dropdown put FlightTypes at the tail of
  // the masterdata run AlpenFlight carries over (persons/clubs/aircrafts/
  // locations/users → flightTypes; navigation-bar-directive.html:75-104).
  // Legacy gated the ENTRY to club-admins (AuthService.js:37) but the screen
  // itself is open to every tenant principal (tenantRequiredGuard only; GET
  // reads are isAuthenticated()) — the entry follows the screen's guard, not
  // legacy's nav gate (J-26 T-28).
  { path: '/flight-types', label: 'Flight types', icon: 'tags' },
  // Future sections (Settings) land here as their feature stories ship —
  // kept inline so the nav-bar's input surface stays a pure data shape.
];

// CLUB_ADMIN-only nav. Sysadmin has no `/api/v1/users/**` path; the entry
// is hidden for them.
export const CLUB_ADMIN_SECTIONS: readonly NavItem[] = [
  { path: '/users', label: 'Users', icon: 'shield' },
];

// Sysadmin-only nav. Clubs is a cross-tenant management surface; per the
// J-6b operator decision it is hidden for club-admins and regular users
// (legacy showed it to everyone — this is a deliberate divergence, not parity).
export const SYS_ADMIN_SECTIONS: readonly NavItem[] = [
  { path: '/clubs', label: 'Clubs', icon: 'plane' },
];

export interface NavRoleFlags {
  readonly isSystemAdmin: boolean;
  readonly isClubAdmin: boolean;
}

/**
 * Pure nav-section assembly per principal role.
 *
 * - sysadmin-only → Clubs only (tenant-scoped pages render empty; no clubId claim, S-159).
 * - club-admin → tenant sections + Users; NO Clubs.
 * - regular user → tenant sections; NO Users, NO Clubs.
 * - dual-role (sysadmin + club-admin) → the role UNION: tenant sections +
 *   Users + Clubs. The old `isSystemAdmin` short-circuit hid ALL tenant nav
 *   from a principal that legitimately operates a tenant (J-26 T-28; the
 *   mock-auth persona is exactly this shape).
 */
export function navSectionsFor(flags: NavRoleFlags): readonly NavItem[] {
  if (flags.isSystemAdmin && !flags.isClubAdmin) {
    return SYS_ADMIN_SECTIONS;
  }
  const sections = [...TENANT_SECTIONS];
  if (flags.isClubAdmin) {
    sections.push(...CLUB_ADMIN_SECTIONS);
  }
  if (flags.isSystemAdmin) {
    sections.push(...SYS_ADMIN_SECTIONS);
  }
  return sections;
}
