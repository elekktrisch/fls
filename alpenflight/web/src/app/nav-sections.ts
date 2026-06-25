import type { Signal } from '@angular/core';

import type { NavItem } from '@ui/organisms/af-nav-bar';

export const TENANT_TOP_SECTIONS: readonly NavItem[] = [
  { path: '/flights', label: 'Flights', icon: 'plane' },
  { path: '/flightreports', label: 'Reports', icon: 'file-text' },
  { path: '/reservations', label: 'Reservations', icon: 'calendar' },
  { path: '/planning', label: 'Planning', icon: 'calendar' },
];

export const MASTERDATA_TENANT_ITEMS: readonly NavItem[] = [
  { path: '/aircraft', label: 'Aircraft', icon: 'plane' },
  { path: '/locations', label: 'Locations', icon: 'map-pin' },
  { path: '/persons', label: 'Persons', icon: 'users' },
  { path: '/flight-types', label: 'Flight types', icon: 'tags' },
];

// Every endpoint behind these screens is CLUB_ADMINISTRATOR-gated on the server
// (even GET reads), so they append to the Masterdata group only for an admin.
export const MASTERDATA_CLUB_ADMIN_ITEMS: readonly NavItem[] = [
  { path: '/join-requests', label: 'Join requests', icon: 'user-plus' },
  { path: '/users', label: 'Users', icon: 'shield' },
  { path: '/accountingrules', label: 'Accounting rules', icon: 'file-text' },
  { path: '/deliverycreationtests', label: 'Delivery creation tests', icon: 'file-text' },
  { path: '/deliveries', label: 'Deliveries', icon: 'file-text' },
  { path: '/email-templates', label: 'Email templates', icon: 'file-text' },
];

// Clubs is a cross-tenant surface deliberately hidden from club-admins + regular
// users (a divergence from legacy, which showed it to everyone).
export const SYS_ADMIN_SECTIONS: readonly NavItem[] = [
  { path: '/clubs', label: 'Clubs', icon: 'plane' },
];

export interface NavRoleFlags {
  readonly isSystemAdmin: boolean;
  readonly isClubAdmin: boolean;
}

/** Live signals threaded onto specific nav entries (e.g. the join-request badge). */
export interface NavBadges {
  readonly joinRequests?: Signal<number>;
}

const JOIN_REQUESTS_BADGE_TESTID = 'nav-join-requests-badge';

function withJoinRequestsBadge(items: readonly NavItem[], badges: NavBadges): readonly NavItem[] {
  const badge = badges.joinRequests;
  if (!badge) {
    return items;
  }
  return items.map((item) =>
    item.path === '/join-requests'
      ? { ...item, badge, badgeTestId: JOIN_REQUESTS_BADGE_TESTID }
      : item,
  );
}

function masterdataGroup(isClubAdmin: boolean, badges: NavBadges): NavItem {
  const children = isClubAdmin
    ? [...MASTERDATA_TENANT_ITEMS, ...withJoinRequestsBadge(MASTERDATA_CLUB_ADMIN_ITEMS, badges)]
    : [...MASTERDATA_TENANT_ITEMS];
  return { label: 'Masterdata', icon: 'database', children };
}

/**
 * Pure nav-section assembly per principal role. A sysadmin-only principal has no
 * managing tenant (no clubId claim), so it gets the cross-tenant Clubs surface
 * only; club-admins + regular users get the tenant sections (admins also see the
 * admin-gated Masterdata children); a dual-role principal gets the role union.
 */
export function navSectionsFor(flags: NavRoleFlags, badges: NavBadges = {}): readonly NavItem[] {
  if (flags.isSystemAdmin && !flags.isClubAdmin) {
    return SYS_ADMIN_SECTIONS;
  }
  const sections: NavItem[] = [...TENANT_TOP_SECTIONS, masterdataGroup(flags.isClubAdmin, badges)];
  if (flags.isSystemAdmin) {
    sections.push(...SYS_ADMIN_SECTIONS);
  }
  return sections;
}
