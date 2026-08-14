import { describe, expect, it } from 'vitest';

import type { AppRole } from '../../core/session/session.store';

import { effectiveVariant, isAdminVariant, roleVariant } from './start-variant';

describe('start-variant selection', () => {
  describe('roleVariant — precedence sysadmin > clubadmin > pilot', () => {
    it('maps a pilot to the pilot variant', () => {
      expect(roleVariant(['PILOT'])).toBe('pilot');
    });

    it('maps other non-admin roles to the pilot variant', () => {
      expect(roleVariant(['FLIGHT_OPERATOR'])).toBe('pilot');
      expect(roleVariant(['OFFICE_USER'])).toBe('pilot');
      expect(roleVariant(['GUEST'])).toBe('pilot');
    });

    it('maps no roles at all to the pilot variant (default)', () => {
      expect(roleVariant([])).toBe('pilot');
    });

    it('maps a CLUB_ADMINISTRATOR to the clubadmin variant', () => {
      expect(roleVariant(['CLUB_ADMINISTRATOR'])).toBe('clubadmin');
    });

    it('maps a SYSTEM_ADMINISTRATOR to the sysadmin variant', () => {
      expect(roleVariant(['SYSTEM_ADMINISTRATOR'])).toBe('sysadmin');
    });

    it('gives a multi-role user the highest variant (sysadmin wins over clubadmin)', () => {
      const both: AppRole[] = ['CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR'];
      expect(roleVariant(both)).toBe('sysadmin');
      expect(roleVariant(['SYSTEM_ADMINISTRATOR', 'CLUB_ADMINISTRATOR'])).toBe('sysadmin');
    });

    it('gives clubadmin precedence over a co-held pilot role', () => {
      expect(roleVariant(['PILOT', 'CLUB_ADMINISTRATOR'])).toBe('clubadmin');
    });
  });

  describe('isAdminVariant — whether the Pilot-view toggle is offered', () => {
    it('is false for a pilot / non-admin', () => {
      expect(isAdminVariant(['PILOT'])).toBe(false);
      expect(isAdminVariant([])).toBe(false);
    });

    it('is true for a club-admin and a sysadmin', () => {
      expect(isAdminVariant(['CLUB_ADMINISTRATOR'])).toBe(true);
      expect(isAdminVariant(['SYSTEM_ADMINISTRATOR'])).toBe(true);
    });
  });

  describe('effectiveVariant — Pilot-view override', () => {
    it('renders the role variant when the override is off', () => {
      expect(effectiveVariant(['CLUB_ADMINISTRATOR'], false)).toBe('clubadmin');
      expect(effectiveVariant(['SYSTEM_ADMINISTRATOR'], false)).toBe('sysadmin');
      expect(effectiveVariant(['PILOT'], false)).toBe('pilot');
    });

    it('flips an admin to the pilot variant when the override is on', () => {
      expect(effectiveVariant(['CLUB_ADMINISTRATOR'], true)).toBe('pilot');
      expect(effectiveVariant(['SYSTEM_ADMINISTRATOR'], true)).toBe('pilot');
    });

    it('flips back to the role variant when the override is cleared', () => {
      expect(effectiveVariant(['SYSTEM_ADMINISTRATOR'], true)).toBe('pilot');
      expect(effectiveVariant(['SYSTEM_ADMINISTRATOR'], false)).toBe('sysadmin');
    });

    it('is inert for a pilot (the override never hides the pilot dashboard)', () => {
      expect(effectiveVariant(['PILOT'], true)).toBe('pilot');
      expect(effectiveVariant([], true)).toBe('pilot');
    });
  });
});
