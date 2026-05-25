import { describe, expect, it } from 'vitest';

import {
  FlightTemplateResponseFlightAircraftType,
  type FlightDetail,
  type FlightLastContextResponse,
  type FlightTemplateResponse,
} from '@api/generated/model';

import {
  buildDefaultsForCopy,
  buildDefaultsForEdit,
  buildDefaultsForNew,
} from './flight-form.defaults';

const TEMPLATE_BLANK: FlightTemplateResponse = {
  flightAircraftType: FlightTemplateResponseFlightAircraftType.GLIDER,
  crew: [],
  isSoloFlight: false,
  noStartTimeInformation: false,
  noLdgTimeInformation: false,
};

const TEMPLATE_WITH_DEFAULTS: FlightTemplateResponse = {
  ...TEMPLATE_BLANK,
  flightTypeId: 'ft-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  startLocationId: 'loc-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  flightDate: '2026-05-25',
};

const LAST_CONTEXT_FULL: FlightLastContextResponse = {
  flightTypeId: 'ft-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  pilotPersonId: 'pn-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  invoiceRecipientPersonId: 'pn-cccccccc-cccc-cccc-cccc-cccccccccccc',
  startLocationId: 'loc-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  ldgLocationId: 'loc-cccccccc-cccc-cccc-cccc-cccccccccccc',
  outboundRoute: 'route-out',
  inboundRoute: 'route-in',
  startTypeId: 'st-towing',
  tow: {
    aircraftId: 'ac-tow-1',
    flightTypeId: 'ft-tow-1',
    pilotPersonId: 'pn-tow-1',
    ldgLocationId: 'loc-tow-ldg',
  },
};

describe('flight-form.defaults', () => {
  describe('buildDefaultsForNew', () => {
    it('falls back to template when last-context is null', () => {
      const snap = buildDefaultsForNew(TEMPLATE_WITH_DEFAULTS, null, {});
      expect(snap.glider.flightTypeId).toBe('ft-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
      expect(snap.glider.startLocationId).toBe('loc-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    });

    it('overlays last-context only on empty fields (template wins over context)', () => {
      const snap = buildDefaultsForNew(TEMPLATE_WITH_DEFAULTS, LAST_CONTEXT_FULL, {});
      // Template's flightTypeId stays — last-context never overwrites.
      expect(snap.glider.flightTypeId).toBe('ft-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
      // last-context fills empty fields.
      expect(snap.glider.pilotPersonId).toBe('pn-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');
      expect(snap.glider.outboundRoute).toBe('route-out');
    });

    it('fills empty fields from last-context when template is blank', () => {
      const snap = buildDefaultsForNew(TEMPLATE_BLANK, LAST_CONTEXT_FULL, {});
      expect(snap.startTypeId).toBe('st-towing');
      expect(snap.glider.flightTypeId).toBe('ft-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');
      expect(snap.glider.startLocationId).toBe('loc-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');
      expect(snap.tow.aircraftId).toBe('ac-tow-1');
      expect(snap.tow.flightTypeId).toBe('ft-tow-1');
      expect(snap.tow.pilotPersonId).toBe('pn-tow-1');
    });

    it('prefs.lastStartLocation overlays empty start/ldg only (never overwrites)', () => {
      const snap = buildDefaultsForNew(TEMPLATE_BLANK, null, {
        lastStartLocation: 'loc-pref-1',
      });
      expect(snap.glider.startLocationId).toBe('loc-pref-1');
      expect(snap.glider.ldgLocationId).toBe('loc-pref-1');
      expect(snap.tow.startLocationId).toBe('loc-pref-1');
      expect(snap.tow.ldgLocationId).toBe('loc-pref-1');
    });

    it('prefs never overwrite a template- or context-provided start location', () => {
      const snap = buildDefaultsForNew(TEMPLATE_WITH_DEFAULTS, null, {
        lastStartLocation: 'loc-pref-1',
      });
      expect(snap.glider.startLocationId).toBe('loc-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    });
  });

  describe('buildDefaultsForCopy', () => {
    it('uses copy-template as the seed (last-context is NOT consulted)', () => {
      const snap = buildDefaultsForCopy(TEMPLATE_WITH_DEFAULTS, {
        lastStartLocation: 'loc-pref-1',
      });
      expect(snap.glider.flightTypeId).toBe('ft-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
      expect(snap.glider.startLocationId).toBe('loc-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    });

    it('overlays prefs for empty start/ldg only', () => {
      const snap = buildDefaultsForCopy(TEMPLATE_BLANK, { lastStartLocation: 'loc-pref-1' });
      expect(snap.glider.startLocationId).toBe('loc-pref-1');
    });
  });

  describe('buildDefaultsForEdit', () => {
    it('passes through the server detail verbatim', () => {
      const detail: FlightDetail = {
        id: 'fl-1',
        version: 1,
        flightAircraftType: 'glider' as never,
        airState: 'NEW' as never,
        processStateId: 'ps-x',
        canUpdateRecord: true,
        canDeleteRecord: true,
        crew: [],
        startLocationId: 'loc-x',
        flightDate: '2026-05-25',
        startTypeId: 'st-towing',
        isSoloFlight: false,
        noStartTimeInformation: false,
        noLdgTimeInformation: false,
      } as unknown as FlightDetail;
      const snap = buildDefaultsForEdit(detail, undefined);
      expect(snap.flightId).toBe('fl-1');
      expect(snap.startTypeId).toBe('st-towing');
      expect(snap.glider.startLocationId).toBe('loc-x');
    });
  });
});
