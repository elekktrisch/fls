import { NonNullableFormBuilder } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import type { FlightDetail, FlightTemplateResponse } from '@api/generated/model';

import {
  buildFlightForm,
  flightDetailToFormSnapshot,
  needsTowplane,
  snapshotToCreateRequests,
  snapshotToUpdateRequest,
  templateToFormSnapshot,
} from './flight-form.model';

const fb = new NonNullableFormBuilder();

const EMPTY_GUID = '00000000-0000-0000-0000-000000000000';

function gliderDetail(over: Partial<FlightDetail> = {}): FlightDetail {
  return {
    id: 'fl-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    flightAircraftType: 'GLIDER',
    aircraftId: 'ac-11111111-1111-1111-1111-111111111111',
    flightDate: '2026-05-25',
    startTypeId: 'st-towing',
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    airState: 'OnGround',
    processStateId: 'ps-new',
    version: 0,
    crew: [],
    ...over,
  } as FlightDetail;
}

describe('flight-form.model', () => {
  describe('buildFlightForm', () => {
    it('returns a strongly-typed FormGroup with glider+tow sub-groups', () => {
      const form = buildFlightForm(fb);
      expect(form.controls.flightId.value).toBeNull();
      expect(form.controls.startTypeId.value).toBeNull();
      expect(form.controls.canUpdateRecord.value).toBe(true);
      expect(form.controls.glider.controls.aircraftId.value).toBeNull();
      expect(form.controls.tow.controls.aircraftId.value).toBeNull();
      expect(form.controls.glider.controls.isSoloFlight.value).toBe(false);
    });
  });

  describe('needsTowplane', () => {
    it('returns true for the towing start-type id', () => {
      expect(needsTowplane('st-towing')).toBe(true);
      expect(needsTowplane('st-TOWING')).toBe(true);
    });

    it('returns false for other start-types', () => {
      expect(needsTowplane('st-winch')).toBe(false);
      expect(needsTowplane(null)).toBe(false);
      expect(needsTowplane(undefined)).toBe(false);
      expect(needsTowplane('')).toBe(false);
    });
  });

  describe('flightDetailToFormSnapshot — empty-Guid normalization on edit-load', () => {
    it('normalizes empty-Guid aircraftId/flightTypeId to null', () => {
      const snap = flightDetailToFormSnapshot(
        gliderDetail({
          aircraftId: EMPTY_GUID,
          flightTypeId: EMPTY_GUID,
          startLocationId: EMPTY_GUID,
        }),
        undefined,
      );
      expect(snap.glider.aircraftId).toBeNull();
      expect(snap.glider.flightTypeId).toBeNull();
      expect(snap.glider.startLocationId).toBeNull();
    });

    it('extracts crew slots from the server crew[] collection by flightCrewTypeId', () => {
      const snap = flightDetailToFormSnapshot(
        gliderDetail({
          crew: [
            { personId: 'pn-pilot-1', flightCrewTypeId: 'pilot' },
            { personId: 'pn-instr-1', flightCrewTypeId: 'instructor' },
            { personId: 'pn-pax-1', flightCrewTypeId: 'passenger' },
          ],
        }),
        undefined,
      );
      expect(snap.glider.pilotPersonId).toBe('pn-pilot-1');
      expect(snap.glider.instructorPersonId).toBe('pn-instr-1');
      expect(snap.glider.passengerPersonId).toBe('pn-pax-1');
      expect(snap.glider.coPilotPersonId).toBeNull();
      expect(snap.glider.observerPersonId).toBeNull();
    });

    it('drops empty-Guid personIds from the crew[] collection', () => {
      const snap = flightDetailToFormSnapshot(
        gliderDetail({
          crew: [
            { personId: 'pn-pilot-1', flightCrewTypeId: 'pilot' },
            { personId: EMPTY_GUID, flightCrewTypeId: 'coPilot' },
          ],
        }),
        undefined,
      );
      expect(snap.glider.pilotPersonId).toBe('pn-pilot-1');
      expect(snap.glider.coPilotPersonId).toBeNull();
    });

    it('produces a blank tow sub-group when no tow is linked', () => {
      const snap = flightDetailToFormSnapshot(gliderDetail(), undefined);
      expect(snap.tow.aircraftId).toBeNull();
      expect(snap.tow.crew).toBeUndefined();
    });

    it('parses startTime / ldgTime from ISO datetimes', () => {
      const snap = flightDetailToFormSnapshot(
        gliderDetail({
          startDateTime: '2026-05-25T10:15:00Z',
          ldgDateTime: '2026-05-25T11:30:00Z',
        }),
        undefined,
      );
      expect(snap.glider.startTime).toBe('10:15');
      expect(snap.glider.ldgTime).toBe('11:30');
    });
  });

  describe('templateToFormSnapshot — empty-Guid normalization on copy-template', () => {
    it('normalizes empty-Guids on copy too (regression: prior bug normalized only on edit)', () => {
      const template: FlightTemplateResponse = {
        flightAircraftType: 'GLIDER',
        aircraftId: EMPTY_GUID,
        flightTypeId: EMPTY_GUID,
        startLocationId: EMPTY_GUID,
        isSoloFlight: false,
        noStartTimeInformation: false,
        noLdgTimeInformation: false,
        crew: [],
      } as FlightTemplateResponse;
      const snap = templateToFormSnapshot(template);
      expect(snap.glider.aircraftId).toBeNull();
      expect(snap.glider.flightTypeId).toBeNull();
      expect(snap.glider.startLocationId).toBeNull();
    });

    it('flightId is null on template (new flight)', () => {
      const template: FlightTemplateResponse = {
        flightAircraftType: 'GLIDER',
        isSoloFlight: false,
        noStartTimeInformation: false,
        noLdgTimeInformation: false,
        crew: [],
      } as FlightTemplateResponse;
      const snap = templateToFormSnapshot(template);
      expect(snap.flightId).toBeNull();
    });
  });

  describe('snapshotToCreateRequests — paired-create flat split', () => {
    function snap(startTypeId: string | null, towAircraftId: string | null = null) {
      const form = buildFlightForm(fb);
      form.patchValue({
        flightDate: '2026-05-25',
        startTypeId,
        glider: {
          aircraftId: 'ac-glider',
          pilotPersonId: 'pn-pilot',
          startLocationId: 'loc-home',
          startTime: '10:00',
          outboundRoute: 'home->A',
        },
        tow: {
          aircraftId: towAircraftId,
          pilotPersonId: towAircraftId ? 'pn-tow-pilot' : null,
          startLocationId: 'loc-OTHER', // intentionally different to verify glider→tow sync wins
          startTime: '09:00',
          outboundRoute: 'OTHER',
        },
      });
      return form.getRawValue() as Parameters<typeof snapshotToCreateRequests>[0];
    }

    it('returns glider request only when start-type is not Towing', () => {
      const result = snapshotToCreateRequests(snap('st-winch', 'ac-tow'));
      expect(result.glider.aircraftId).toBe('ac-glider');
      expect(result.tow).toBeUndefined();
    });

    it('returns glider+tow when start-type Towing AND tow aircraft picked', () => {
      const result = snapshotToCreateRequests(snap('st-towing', 'ac-tow'));
      expect(result.glider.aircraftId).toBe('ac-glider');
      expect(result.tow?.aircraftId).toBe('ac-tow');
    });

    it('drops tow when start-type Towing but tow aircraft NOT picked (partial tow data discarded, parity)', () => {
      const result = snapshotToCreateRequests(snap('st-towing', null));
      expect(result.tow).toBeUndefined();
    });

    it('mirrors glider startLocationId / startTime / outboundRoute onto the tow request (parity FlightsController.js:370-372)', () => {
      const result = snapshotToCreateRequests(snap('st-towing', 'ac-tow'));
      expect(result.tow?.startLocationId).toBe('loc-home');
      expect(result.tow?.startDateTime).toBe('2026-05-25T10:00:00Z');
      expect(result.tow?.outboundRoute).toBe('home->A');
    });

    it('discriminates GLIDER vs TOW flightAircraftType', () => {
      const result = snapshotToCreateRequests(snap('st-towing', 'ac-tow'));
      expect(result.glider.flightAircraftType).toBe('GLIDER');
      expect(result.tow?.flightAircraftType).toBe('TOW');
    });

    it('packs crew slots into the server crew[] collection by flightCrewTypeId', () => {
      const form = buildFlightForm(fb);
      form.patchValue({
        flightDate: '2026-05-25',
        startTypeId: 'st-winch',
        glider: {
          aircraftId: 'ac-glider',
          pilotPersonId: 'pn-pilot',
          coPilotPersonId: 'pn-copilot',
          winchOperatorPersonId: 'pn-winch',
        },
      });
      const s = form.getRawValue() as Parameters<typeof snapshotToCreateRequests>[0];
      const r = snapshotToCreateRequests(s).glider;
      expect(r.crew).toEqual([
        { personId: 'pn-pilot', flightCrewTypeId: 'pilot' },
        { personId: 'pn-copilot', flightCrewTypeId: 'coPilot' },
        { personId: 'pn-winch', flightCrewTypeId: 'winchOperator' },
      ]);
    });
  });

  describe('snapshotToUpdateRequest', () => {
    function basicSnap() {
      const form = buildFlightForm(fb);
      form.patchValue({
        flightId: 'fl-existing',
        flightDate: '2026-05-25',
        startTypeId: 'st-towing',
        glider: {
          aircraftId: 'ac-glider',
          pilotPersonId: 'pn-pilot',
          startLocationId: 'loc-home',
          startTime: '10:00',
        },
        tow: {
          aircraftId: 'ac-tow',
          startLocationId: 'loc-OTHER',
          startTime: '09:00',
        },
      });
      return form.getRawValue() as Parameters<typeof snapshotToUpdateRequest>[0];
    }

    it('strips the discriminator from the update request (immutable per backend contract)', () => {
      const req = snapshotToUpdateRequest(basicSnap(), 'glider');
      expect('flightAircraftType' in req).toBe(false);
    });

    it('attaches towFlightId to the glider PUT body when paired-link step is requested', () => {
      const req = snapshotToUpdateRequest(basicSnap(), 'glider', 'fl-tow-id');
      expect(req.towFlightId).toBe('fl-tow-id');
    });

    it('mirrors glider start/location/route onto the tow PUT body (parity at submit)', () => {
      const req = snapshotToUpdateRequest(basicSnap(), 'tow');
      expect(req.startLocationId).toBe('loc-home');
      expect(req.startDateTime).toBe('2026-05-25T10:00:00Z');
    });
  });
});
