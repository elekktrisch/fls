import { describe, expect, it } from 'vitest';

import { navSectionsFor } from './nav-sections';

const paths = (flags: { isSystemAdmin: boolean; isClubAdmin: boolean }) =>
  navSectionsFor(flags).map((s) => s.path);

describe('navSectionsFor', () => {
  it('sysadmin sees Clubs and no tenant sections', () => {
    const p = paths({ isSystemAdmin: true, isClubAdmin: false });
    expect(p).toEqual(['/clubs']);
    expect(p).not.toContain('/reservations');
    expect(p).not.toContain('/users');
  });

  it('club-admin sees Reservations + tenant + Users, but NOT Clubs', () => {
    const p = paths({ isSystemAdmin: false, isClubAdmin: true });
    expect(p).toContain('/reservations');
    expect(p).toContain('/users');
    expect(p).not.toContain('/clubs');
    // tenant sections present
    expect(p).toContain('/planning');
    expect(p).toContain('/flights');
  });

  it('regular user sees Reservations + tenant, but NOT Users or Clubs', () => {
    const p = paths({ isSystemAdmin: false, isClubAdmin: false });
    expect(p).toContain('/reservations');
    expect(p).not.toContain('/users');
    expect(p).not.toContain('/clubs');
  });

  it('Reports sits directly after Flights for every tenant principal (legacy nav parity)', () => {
    for (const isClubAdmin of [false, true]) {
      const p = paths({ isSystemAdmin: false, isClubAdmin });
      expect(p.indexOf('/flightreports')).toBe(p.indexOf('/flights') + 1);
    }
    expect(paths({ isSystemAdmin: true, isClubAdmin: false })).not.toContain('/flightreports');
  });

  it('sysadmin flag wins even if club-admin is also set', () => {
    const p = paths({ isSystemAdmin: true, isClubAdmin: true });
    expect(p).toEqual(['/clubs']);
  });
});
