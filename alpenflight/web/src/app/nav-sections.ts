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
 * - sysadmin → Clubs only (tenant-scoped pages render empty; no clubId claim, S-159).
 * - club-admin → tenant sections + Users; NO Clubs.
 * - regular user → tenant sections; NO Users, NO Clubs.
 */
export function navSectionsFor(flags: NavRoleFlags): readonly NavItem[] {
  if (flags.isSystemAdmin) {
    return SYS_ADMIN_SECTIONS;
  }
  const base = [...TENANT_SECTIONS];
  if (flags.isClubAdmin) {
    return [...base, ...CLUB_ADMIN_SECTIONS];
  }
  return base;
}
