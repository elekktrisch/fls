import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { navSectionsFor } from './nav-sections';

interface Flags {
  isSystemAdmin: boolean;
  isClubAdmin: boolean;
}

/** Top-level entries: a leaf carries `path`, a group carries `label`/`children`. */
const topLabels = (flags: Flags) => navSectionsFor(flags).map((s) => s.path ?? `group:${s.label}`);

/** The "Masterdata" group's children paths (undefined when there is no group). */
const masterdataPaths = (flags: Flags): string[] | undefined =>
  navSectionsFor(flags)
    .find((s) => s.label === 'Masterdata')
    ?.children?.map((c) => c.path as string);

/** Every reachable path (top-level leaves + group children) — flattened. */
const allPaths = (flags: Flags): string[] =>
  navSectionsFor(flags).flatMap((s) =>
    s.children ? s.children.map((c) => c.path as string) : [s.path as string],
  );

describe('navSectionsFor', () => {
  it('sysadmin sees Clubs only — no tenant sections, no Masterdata group', () => {
    expect(topLabels({ isSystemAdmin: true, isClubAdmin: false })).toEqual(['/clubs']);
    expect(masterdataPaths({ isSystemAdmin: true, isClubAdmin: false })).toBeUndefined();
    expect(allPaths({ isSystemAdmin: true, isClubAdmin: false })).not.toContain('/users');
  });

  it('regular user: top-level tenant + a Masterdata group with ONLY the 4 tenant items', () => {
    const flags = { isSystemAdmin: false, isClubAdmin: false };
    // Top-level: Flights, Reports, Reservations, Planning, then the Masterdata group.
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
    // Club-admin-only masterdata items + Clubs are hidden for a regular user.
    expect(allPaths(flags)).not.toContain('/users');
    expect(allPaths(flags)).not.toContain('/accountingrules');
    expect(allPaths(flags)).not.toContain('/deliverycreationtests');
    expect(allPaths(flags)).not.toContain('/deliveries');
    expect(allPaths(flags)).not.toContain('/email-templates');
    expect(allPaths(flags)).not.toContain('/clubs');
  });

  it('club-admin: Masterdata group ALSO carries Join requests + Users + Accounting rules + Delivery creation tests + Deliveries + Email templates; NO Clubs', () => {
    const flags = { isSystemAdmin: false, isClubAdmin: true };
    expect(masterdataPaths(flags)).toEqual([
      '/aircraft',
      '/locations',
      '/persons',
      '/flight-types',
      '/join-requests',
      '/users',
      '/accountingrules',
      '/deliverycreationtests',
      '/deliveries',
      '/email-templates',
    ]);
    expect(topLabels(flags)).not.toContain('/clubs');
    // Reservations + Flights stay top-level (not under Masterdata).
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
    const flags = { isSystemAdmin: true, isClubAdmin: true };
    expect(topLabels(flags)).toEqual([
      '/flights',
      '/flightreports',
      '/reservations',
      '/planning',
      'group:Masterdata',
      '/clubs',
    ]);
    expect(masterdataPaths(flags)).toEqual([
      '/aircraft',
      '/locations',
      '/persons',
      '/flight-types',
      '/join-requests',
      '/users',
      '/accountingrules',
      '/deliverycreationtests',
      '/deliveries',
      '/email-templates',
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
    // No other entry carries a badge.
    expect(md?.filter((c) => c.badge).map((c) => c.path)).toEqual(['/join-requests']);
  });

  it('omits the badge when no signal is supplied (default assembly)', () => {
    const flags = { isSystemAdmin: false, isClubAdmin: true };
    const md = navSectionsFor(flags)
      .find((s) => s.label === 'Masterdata')
      ?.children?.find((c) => c.path === '/join-requests');
    expect(md?.badge).toBeUndefined();
  });

  it('Flight types is the tail of the open tenant masterdata items (legacy nav parity, J-26 T-28)', () => {
    // Legacy placed FlightTypes at the tail of the masterdata entries AlpenFlight
    // carries (persons/aircrafts/locations → flightTypes; flsweb
    // navigation-bar-directive.html:75-104). Visible to EVERY tenant principal.
    for (const isClubAdmin of [false, true]) {
      const md = masterdataPaths({ isSystemAdmin: false, isClubAdmin })!;
      expect(md.indexOf('/flight-types')).toBe(md.indexOf('/persons') + 1);
    }
  });
});
