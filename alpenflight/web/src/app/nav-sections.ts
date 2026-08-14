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

export const MASTERDATA_CLUB_ADMIN_ITEMS: readonly NavItem[] = [
  { path: '/join-requests', label: 'Join requests', icon: 'user-plus' },
  { path: '/users', label: 'Users', icon: 'shield' },
  { path: '/accountingrules', label: 'Accounting rules', icon: 'file-text' },
  { path: '/deliverycreationtests', label: 'Delivery creation tests', icon: 'file-text' },
  { path: '/deliveries', label: 'Deliveries', icon: 'file-text' },
  { path: '/email-templates', label: 'Email templates', icon: 'file-text' },
  { path: '/system/logs', label: 'Audit logs', icon: 'file-text' },
];

export const SYS_ADMIN_SECTIONS: readonly NavItem[] = [
  { path: '/clubs', label: 'Clubs', icon: 'plane' },
  { path: '/system/jobs', label: 'Jobs', icon: 'file-text' },
];

export interface NavPrincipal {
  readonly isSystemAdmin: boolean;
  readonly isClubAdmin: boolean;
  readonly clubId?: string | null;
}

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

export const CLUB_SETTINGS_TEST_ID = 'af-nav-section-club-settings';

function clubSettingsItems(clubId: string | null | undefined): readonly NavItem[] {
  return clubId
    ? [
        {
          path: `/clubs/${clubId}/edit`,
          label: 'Club settings',
          icon: 'settings',
          testId: CLUB_SETTINGS_TEST_ID,
        },
      ]
    : [];
}

function masterdataGroup(principal: NavPrincipal, badges: NavBadges): NavItem {
  const children = principal.isClubAdmin
    ? [
        ...MASTERDATA_TENANT_ITEMS,
        ...clubSettingsItems(principal.clubId),
        ...withJoinRequestsBadge(MASTERDATA_CLUB_ADMIN_ITEMS, badges),
      ]
    : [...MASTERDATA_TENANT_ITEMS];
  return { label: 'Masterdata', icon: 'database', children };
}

export function navSectionsFor(
  principal: NavPrincipal,
  badges: NavBadges = {},
): readonly NavItem[] {
  if (principal.isSystemAdmin && !principal.isClubAdmin) {
    return SYS_ADMIN_SECTIONS;
  }
  const sections: NavItem[] = [...TENANT_TOP_SECTIONS, masterdataGroup(principal, badges)];
  if (principal.isSystemAdmin) {
    sections.push(...SYS_ADMIN_SECTIONS);
  }
  return sections;
}
