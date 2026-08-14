import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { CLUB_SETTINGS_TEST_ID, navSectionsFor } from './nav-sections';

interface Flags {
  isSystemAdmin: boolean;
  isClubAdmin: boolean;
  clubId?: string | null;
}

const OWN_CLUB_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const topLabels = (flags: Flags) => navSectionsFor(flags).map((s) => s.path ?? `group:${s.label}`);

const masterdataPaths = (flags: Flags): string[] | undefined =>
  navSectionsFor(flags)
    .find((s) => s.label === 'Masterdata')
    ?.children?.map((c) => c.path as string);

const allPaths = (flags: Flags): string[] =>
  navSectionsFor(flags).flatMap((s) =>
    s.children ? s.children.map((c) => c.path as string) : [s.path as string],
  );

describe('navSectionsFor', () => {
  it('sysadmin sees Clubs + Jobs only — no tenant sections, no Masterdata group', () => {
    expect(topLabels({ isSystemAdmin: true, isClubAdmin: false })).toEqual([
      '/clubs',
      '/system/jobs',
    ]);
    expect(masterdataPaths({ isSystemAdmin: true, isClubAdmin: false })).toBeUndefined();
    expect(allPaths({ isSystemAdmin: true, isClubAdmin: false })).not.toContain('/users');
  });

  it('regular user: top-level tenant + a Masterdata group with ONLY the 4 tenant items', () => {
    const flags = { isSystemAdmin: false, isClubAdmin: false, clubId: OWN_CLUB_ID };
    expect(topLabels(flags)).toEqual([
      '/flights',
      '/flightreports',
      '/reservations',
      '/planning',
      'group:Masterdata',
    ]);
    expect(masterdataPaths(flags)).toEqual([
      '/aircraft',
      '/locations',
      '/persons',
      '/flight-types',
    ]);
    expect(allPaths(flags)).not.toContain('/users');
    expect(allPaths(flags)).not.toContain('/accountingrules');
    expect(allPaths(flags)).not.toContain('/deliverycreationtests');
    expect(allPaths(flags)).not.toContain('/deliveries');
    expect(allPaths(flags)).not.toContain('/email-templates');
    expect(allPaths(flags)).not.toContain('/clubs');
    expect(allPaths(flags)).not.toContain(`/clubs/${OWN_CLUB_ID}/edit`);
  });

  it('club-admin: Masterdata group ALSO carries Club settings + Join requests + Users + Accounting rules + Delivery creation tests + Deliveries + Email templates + Audit logs; NO Clubs', () => {
    const flags = { isSystemAdmin: false, isClubAdmin: true, clubId: OWN_CLUB_ID };
    expect(masterdataPaths(flags)).toEqual([
      '/aircraft',
      '/locations',
      '/persons',
      '/flight-types',
      `/clubs/${OWN_CLUB_ID}/edit`,
      '/join-requests',
      '/users',
      '/accountingrules',
      '/deliverycreationtests',
      '/deliveries',
      '/email-templates',
      '/system/logs',
    ]);
    expect(topLabels(flags)).not.toContain('/clubs');
    expect(topLabels(flags)).toContain('/reservations');
    expect(topLabels(flags)).toContain('/flights');
  });

  it('Reports sits directly after Flights top-level for every tenant principal (legacy nav parity)', () => {
    for (const isClubAdmin of [false, true]) {
      const t = topLabels({ isSystemAdmin: false, isClubAdmin });
      expect(t.indexOf('/flightreports')).toBe(t.indexOf('/flights') + 1);
    }
    expect(topLabels({ isSystemAdmin: true, isClubAdmin: false })).not.toContain('/flightreports');
  });

  it('Masterdata is the LAST top-level entry for a tenant-only principal', () => {
    for (const isClubAdmin of [false, true]) {
      const t = topLabels({ isSystemAdmin: false, isClubAdmin });
      expect(t[t.length - 1]).toBe('group:Masterdata');
    }
  });

  it('dual-role (sysadmin + club-admin) sees the role UNION — top-level + full Masterdata + Clubs', () => {
    const flags = { isSystemAdmin: true, isClubAdmin: true, clubId: OWN_CLUB_ID };
    expect(topLabels(flags)).toEqual([
      '/flights',
      '/flightreports',
      '/reservations',
      '/planning',
      'group:Masterdata',
      '/clubs',
      '/system/jobs',
    ]);
    expect(masterdataPaths(flags)).toEqual([
      '/aircraft',
      '/locations',
      '/persons',
      '/flight-types',
      `/clubs/${OWN_CLUB_ID}/edit`,
      '/join-requests',
      '/users',
      '/accountingrules',
      '/deliverycreationtests',
      '/deliveries',
      '/email-templates',
      '/system/logs',
    ]);
  });

  it('threads the join-request badge signal + testid onto the /join-requests entry only', () => {
    const flags = { isSystemAdmin: false, isClubAdmin: true };
    const badge = signal(4);
    const md = navSectionsFor(flags, { joinRequests: badge }).find(
      (s) => s.label === 'Masterdata',
    )?.children;
    const joinEntry = md?.find((c) => c.path === '/join-requests');
    expect(joinEntry?.badge?.()).toBe(4);
    expect(joinEntry?.badgeTestId).toBe('nav-join-requests-badge');
    expect(md?.filter((c) => c.badge).map((c) => c.path)).toEqual(['/join-requests']);
  });

  it('omits the badge when no signal is supplied (default assembly)', () => {
    const flags = { isSystemAdmin: false, isClubAdmin: true };
    const md = navSectionsFor(flags)
      .find((s) => s.label === 'Masterdata')
      ?.children?.find((c) => c.path === '/join-requests');
    expect(md?.badge).toBeUndefined();
  });

  describe('own-club settings entry', () => {
    const clubSettings = (flags: Flags) =>
      navSectionsFor(flags)
        .flatMap((s) => s.children ?? [s])
        .filter((i) => i.testId === CLUB_SETTINGS_TEST_ID);

    it('points a club-admin at its OWN club under a stable testid', () => {
      const entries = clubSettings({
        isSystemAdmin: false,
        isClubAdmin: true,
        clubId: OWN_CLUB_ID,
      });
      expect(entries).toHaveLength(1);
      expect(entries[0]?.path).toBe(`/clubs/${OWN_CLUB_ID}/edit`);
      expect(entries[0]?.label).toBe('Club settings');
    });

    it.each([
      ['a plain member (pilot)', { isSystemAdmin: false, isClubAdmin: false }],
      ['a sysadmin-only principal', { isSystemAdmin: true, isClubAdmin: false }],
    ])('is hidden from %s', (_who, roles) => {
      expect(clubSettings({ ...roles, clubId: OWN_CLUB_ID })).toEqual([]);
    });

    it('is omitted until /me resolves the club id — no unroutable link', () => {
      expect(clubSettings({ isSystemAdmin: false, isClubAdmin: true, clubId: null })).toEqual([]);
      expect(clubSettings({ isSystemAdmin: false, isClubAdmin: true })).toEqual([]);
    });

    it('leaves the sysadmin catalog entry intact for a dual-role principal', () => {
      const flags = { isSystemAdmin: true, isClubAdmin: true, clubId: OWN_CLUB_ID };
      expect(topLabels(flags)).toContain('/clubs');
      expect(clubSettings(flags)).toHaveLength(1);
    });
  });

  it('Flight types is the tail of the open tenant masterdata items (legacy nav parity, J-26 T-28)', () => {
    for (const isClubAdmin of [false, true]) {
      const md = masterdataPaths({ isSystemAdmin: false, isClubAdmin })!;
      expect(md.indexOf('/flight-types')).toBe(md.indexOf('/persons') + 1);
    }
  });
});
