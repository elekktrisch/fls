import type { NavItem } from '@ui/organisms/af-nav-bar';

// Top-level tenant-scoped nav (require a managing club; hidden for sysadmin).
// The masterdata screens live UNDER a "Masterdata" group (assembled in
// `navSectionsFor()` per role); these stay top-level (operator grouping,
// legacy-parity — J-8 T-22).
export const TENANT_TOP_SECTIONS: readonly NavItem[] = [
  { path: '/flights', label: 'Flights', icon: 'plane' },
  // Directly after Flights = legacy parity: flsweb's nav put FLIGHTREPORTS
  // right after the start-list entry, visible to every logged-in user (no
  // role gate — J-7 deliberately serves the PILOT own-flights report).
  { path: '/flightreports', label: 'Reports', icon: 'file-text' },
  { path: '/reservations', label: 'Reservations', icon: 'calendar' },
  { path: '/planning', label: 'Planning', icon: 'calendar' },
];

// Masterdata children visible to EVERY tenant principal. The masterdata
// screens are open to any tenant user (tenantRequiredGuard only; GET reads are
// isAuthenticated()) — the legacy club-admin nav gate is NOT carried over for
// these four (J-26 T-28 / J-8 T-22a).
export const MASTERDATA_TENANT_ITEMS: readonly NavItem[] = [
  { path: '/aircraft', label: 'Aircraft', icon: 'plane' },
  { path: '/locations', label: 'Locations', icon: 'map-pin' },
  { path: '/persons', label: 'Persons', icon: 'users' },
  // Legacy parity: flsweb's masterdata dropdown put FlightTypes at the tail of
  // the masterdata run (navigation-bar-directive.html:75-104).
  { path: '/flight-types', label: 'Flight types', icon: 'tags' },
];

// Masterdata children added ONLY for club admins. Sysadmin has no
// `/api/v1/users/**` path and every accounting CRUD endpoint is
// CLUB_ADMINISTRATOR-gated on the server (a pure config screen; even reads are
// admin-gated), so these two append to the Masterdata group only when
// `isClubAdmin` (J-8 T-22a).
export const MASTERDATA_CLUB_ADMIN_ITEMS: readonly NavItem[] = [
  { path: '/users', label: 'Users', icon: 'shield' },
  { path: '/accountingrules', label: 'Accounting rules', icon: 'file-text' },
  { path: '/deliverycreationtests', label: 'Delivery creation tests', icon: 'file-text' },
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
 * The "Masterdata" group entry for a tenant principal: a group NavItem (no
 * `path`, carries `children`). The children compose per role — every tenant
 * principal sees the four open masterdata screens; a club admin also sees Users
 * + Accounting rules (J-8 T-22a operator grouping).
 */
function masterdataGroup(isClubAdmin: boolean): NavItem {
  const children = isClubAdmin
    ? [...MASTERDATA_TENANT_ITEMS, ...MASTERDATA_CLUB_ADMIN_ITEMS]
    : [...MASTERDATA_TENANT_ITEMS];
  return { label: 'Masterdata', icon: 'database', children };
}

/**
 * Pure nav-section assembly per principal role.
 *
 * - sysadmin-only → Clubs only (tenant-scoped pages render empty; no clubId claim, S-159).
 * - club-admin → top-level tenant sections + a Masterdata group whose children
 *   are the 4 tenant items + Users + Accounting rules; NO Clubs.
 * - regular user → top-level tenant sections + a Masterdata group with only the
 *   4 tenant items; NO Users, NO Accounting rules, NO Clubs.
 * - dual-role (sysadmin + club-admin) → the role UNION: top-level tenant
 *   sections + the club-admin Masterdata group + Clubs. The old
 *   `isSystemAdmin` short-circuit hid ALL tenant nav from a principal that
 *   legitimately operates a tenant (J-26 T-28; the mock-auth persona is exactly
 *   this shape).
 */
export function navSectionsFor(flags: NavRoleFlags): readonly NavItem[] {
  if (flags.isSystemAdmin && !flags.isClubAdmin) {
    return SYS_ADMIN_SECTIONS;
  }
  const sections: NavItem[] = [...TENANT_TOP_SECTIONS, masterdataGroup(flags.isClubAdmin)];
  if (flags.isSystemAdmin) {
    sections.push(...SYS_ADMIN_SECTIONS);
  }
  return sections;
}
