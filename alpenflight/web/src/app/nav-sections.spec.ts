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

  it('dual-role (sysadmin + club-admin) sees the role UNION — tenant + Users + Clubs (J-26 T-28)', () => {
    const p = paths({ isSystemAdmin: true, isClubAdmin: true });
    expect(p).toEqual([
      '/flights',
      '/flightreports',
      '/reservations',
      '/planning',
      '/aircraft',
      '/locations',
      '/persons',
      '/flight-types',
      '/users',
      '/clubs',
    ]);
  });

  it('Flight types sits directly after Persons — tail of the masterdata run, per legacy nav (J-26 T-28)', () => {
    // Legacy placed FlightTypes at the tail of the masterdata entries AlpenFlight
    // carries (persons/aircrafts/locations → flightTypes; flsweb
    // navigation-bar-directive.html:75-104). Visible to EVERY tenant principal —
    // the screen's guard is tenantRequiredGuard with isAuthenticated() reads, so
    // the entry follows the screen, not legacy's club-admin nav gate
    // (AuthService.js:37).
    for (const isClubAdmin of [false, true]) {
      const p = paths({ isSystemAdmin: false, isClubAdmin });
      expect(p.indexOf('/flight-types')).toBe(p.indexOf('/persons') + 1);
    }
    // Sysadmin-only principals keep exactly today's sections (no tenant nav).
    expect(paths({ isSystemAdmin: true, isClubAdmin: false })).not.toContain('/flight-types');
  });
});
