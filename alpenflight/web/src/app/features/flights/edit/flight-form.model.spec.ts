import { FormBuilder } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import type {
  FlightCreateRequest,
  FlightDetail,
  FlightTemplateResponse,
} from '@api/generated/model';

import { revalidateTree } from '@shared/util/form/revalidate';

import {
  FLIGHT_CREW_TYPE_CO_PILOT,
  FLIGHT_CREW_TYPE_FLIGHT_COST_INVOICE_RECIPIENT,
  FLIGHT_CREW_TYPE_FLIGHT_INSTRUCTOR,
  FLIGHT_CREW_TYPE_PASSENGER,
  FLIGHT_CREW_TYPE_PILOT,
  FLIGHT_CREW_TYPE_WINCH_OPERATOR,
} from './flight-crew-types';
import {
  buildFlightForm,
  flightDetailToFormSnapshot,
  isFlightSaveable,
  needsTowplane,
  snapshotToCreateRequests,
  snapshotToUpdateRequest,
  templateToFormSnapshot,
  type CrewSnapshot,
  type FlightFormSnapshot,
} from './flight-form.model';
import { START_TYPE } from './flight-start-types';

const TOWING = START_TYPE.AEROTOW;
const NON_TOWING = START_TYPE.WINCH_LAUNCH;

const fb = new FormBuilder().nonNullable;

const EMPTY_GUID = '00000000-0000-0000-0000-000000000000';

function gliderDetail(over: Partial<FlightDetail> = {}): FlightDetail {
  return {
    id: 'fl-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    flightAircraftType: 'GLIDER',
    aircraftId: 'ac-11111111-1111-1111-1111-111111111111',
    flightDate: '2026-05-25',
    startTypeId: TOWING,
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

  describe('buildFlightForm — J-26 T-13 client required validators', () => {
    it('opens INVALID with flightDate / glider aircraft / glider pilot all empty', () => {
      const form = buildFlightForm(fb);
      expect(form.invalid).toBe(true);
      expect(form.controls.flightDate.hasError('required')).toBe(true);
      expect(form.controls.glider.controls.aircraftId.hasError('required')).toBe(true);
      expect(form.controls.glider.controls.pilotPersonId.hasError('required')).toBe(true);
    });

    it('stays INVALID while date / aircraft / pilot are still missing', () => {
      const form = buildFlightForm(fb);
      form.controls.flightDate.setValue('2026-07-01');
      expect(form.invalid).toBe(true); // aircraft + pilot still empty
      form.controls.glider.controls.aircraftId.setValue('ac-1');
      expect(form.invalid).toBe(true); // pilot still empty
      form.controls.glider.controls.pilotPersonId.setValue('pe-1');
      expect(form.invalid).toBe(true); // rest of the minimal-valid set still empty
    });
  });

  describe('buildFlightForm — minimal-valid glider field set', () => {
    function fillMinimalValid(form: ReturnType<typeof buildFlightForm>): void {
      form.controls.flightDate.setValue('2026-07-01');
      form.controls.startTypeId.setValue(START_TYPE.SELF_START);
      const g = form.controls.glider.controls;
      g.aircraftId.setValue('ac-1');
      g.pilotPersonId.setValue('pe-1');
      g.flightTypeId.setValue('ft-1');
      g.startLocationId.setValue('loc-start');
      g.ldgLocationId.setValue('loc-ldg');
      g.startTime.setValue('10:00');
      g.ldgTime.setValue('11:30');
      g.nrOfLdgs.setValue(1);
    }

    it('stays INVALID until the full minimal-valid glider set is present', () => {
      const form = buildFlightForm(fb);
      fillMinimalValid(form);
      expect(form.valid).toBe(true);
    });

    it('gates on flight type, start/landing location, start type and landing count', () => {
      const form = buildFlightForm(fb);
      fillMinimalValid(form);
      const g = form.controls.glider.controls;

      g.flightTypeId.setValue(null);
      expect(form.invalid).toBe(true);
      g.flightTypeId.setValue('ft-1');

      g.startLocationId.setValue(null);
      expect(form.invalid).toBe(true);
      g.startLocationId.setValue('loc-start');

      g.ldgLocationId.setValue(null);
      expect(form.invalid).toBe(true);
      g.ldgLocationId.setValue('loc-ldg');

      form.controls.startTypeId.setValue(null);
      expect(form.invalid).toBe(true);
      form.controls.startTypeId.setValue(START_TYPE.SELF_START);

      g.nrOfLdgs.setValue(0);
      expect(form.invalid).toBe(true);
      g.nrOfLdgs.setValue(1);

      expect(form.valid).toBe(true);
    });

    it('requires a start time unless noStartTimeInformation is set', () => {
      const form = buildFlightForm(fb);
      fillMinimalValid(form);
      const g = form.controls.glider.controls;

      g.startTime.setValue(null);
      expect(form.invalid).toBe(true);

      g.noStartTimeInformation.setValue(true);
      g.startTime.updateValueAndValidity();
      g.noStartTimeInformation.updateValueAndValidity();
      form.controls.glider.updateValueAndValidity();
      expect(form.valid).toBe(true);
    });

    it('requires a landing time unless noLdgTimeInformation is set', () => {
      const form = buildFlightForm(fb);
      fillMinimalValid(form);
      const g = form.controls.glider.controls;

      g.ldgTime.setValue(null);
      expect(form.invalid).toBe(true);

      g.noLdgTimeInformation.setValue(true);
      g.ldgTime.updateValueAndValidity();
      g.noLdgTimeInformation.updateValueAndValidity();
      form.controls.glider.updateValueAndValidity();
      expect(form.valid).toBe(true);
    });

    it('requires a winch operator on a winch launch, not on other start types', () => {
      const form = buildFlightForm(fb);
      fillMinimalValid(form);
      // fillMinimalValid uses a non-winch start type → no winch-operator gate.
      expect(form.valid).toBe(true);

      form.controls.startTypeId.setValue(START_TYPE.WINCH_LAUNCH);
      form.controls.glider.updateValueAndValidity();
      expect(form.controls.glider.hasError('winchOperatorRequired')).toBe(true);
      expect(form.invalid).toBe(true);

      form.controls.glider.controls.winchOperatorPersonId.setValue('pe-winch');
      expect(form.controls.glider.hasError('winchOperatorRequired')).toBe(false);
      expect(form.valid).toBe(true);
    });

    it('does NOT require the conditional TOW sub-group for the extended set', () => {
      const form = buildFlightForm(fb);
      fillMinimalValid(form);
      expect(form.controls.tow.controls.flightTypeId.hasError('required')).toBe(false);
      expect(form.controls.tow.controls.startLocationId.hasError('required')).toBe(false);
      expect(form.valid).toBe(true);
    });

    it('reopens a fully-populated saved flight VALID after hydrate revalidate (#229b)', () => {
      const form = buildFlightForm(fb);
      const snap = flightDetailToFormSnapshot(
        gliderDetail({
          aircraftId: 'ac-1',
          flightTypeId: 'ft-1',
          startTypeId: START_TYPE.SELF_START,
          startLocationId: 'loc-start',
          ldgLocationId: 'loc-ldg',
          startDateTime: '2026-05-25T10:00:00Z',
          ldgDateTime: '2026-05-25T11:30:00Z',
          nrOfLdgs: 1,
          crew: [{ personId: 'pe-1', flightCrewTypeId: FLIGHT_CREW_TYPE_PILOT }],
        }),
        undefined,
      );
      form.patchValue(
        {
          flightDate: snap.flightDate,
          startTypeId: snap.startTypeId,
        },
        { emitEvent: false },
      );
      form.controls.glider.patchValue(snap.glider, { emitEvent: false });

      revalidateTree(form);
      expect(form.controls.glider.controls.aircraftId.hasError('required')).toBe(false);
      expect(form.controls.glider.controls.pilotPersonId.hasError('required')).toBe(false);
      expect(form.controls.glider.controls.flightTypeId.hasError('required')).toBe(false);
      expect(form.valid).toBe(true);
    });

    it('reopens an INCOMPLETE saved flight INVALID (Save stays gated)', () => {
      const form = buildFlightForm(fb);
      const incomplete = gliderDetail({
        aircraftId: 'ac-1',
        flightTypeId: 'ft-1',
        startTypeId: START_TYPE.SELF_START,
        startLocationId: 'loc-start',
        startDateTime: '2026-05-25T10:00:00Z',
        ldgDateTime: '2026-05-25T11:30:00Z',
        nrOfLdgs: 1,
        crew: [{ personId: 'pe-1', flightCrewTypeId: FLIGHT_CREW_TYPE_PILOT }],
      });
      delete (incomplete as { ldgLocationId?: string }).ldgLocationId; // missing landing location
      const snap = flightDetailToFormSnapshot(incomplete, undefined);
      form.patchValue(
        { flightDate: snap.flightDate, startTypeId: snap.startTypeId },
        { emitEvent: false },
      );
      form.controls.glider.patchValue(snap.glider, { emitEvent: false });

      revalidateTree(form);
      expect(form.controls.glider.controls.ldgLocationId.hasError('required')).toBe(true);
      expect(form.invalid).toBe(true);
    });
  });

  describe('isFlightSaveable — Save gate (legacy-client parity)', () => {
    function setGate(
      form: ReturnType<typeof buildFlightForm>,
      date: string | null,
      aircraft: string | null,
      pilot: string | null,
    ): void {
      form.controls.flightDate.setValue(date);
      form.controls.glider.controls.aircraftId.setValue(aircraft);
      form.controls.glider.controls.pilotPersonId.setValue(pilot);
    }

    it('enables Save once date + glider aircraft + pilot are present (rest of the set still empty)', () => {
      const form = buildFlightForm(fb);
      setGate(form, '2026-07-01', 'ac-1', 'pe-1');
      // start/ldg time, locations, flight type, landings all still empty → the
      // full form is invalid, yet Save is enabled (incomplete flight, saved Invalid).
      expect(form.invalid).toBe(true);
      expect(isFlightSaveable(form)).toBe(true);
    });

    it('keeps Save enabled for an incomplete flight that meets only the gate', () => {
      const form = buildFlightForm(fb);
      setGate(form, '2026-07-01', 'ac-1', 'pe-1');
      form.controls.glider.controls.flightTypeId.setValue('ft-1');
      form.controls.glider.controls.startLocationId.setValue('loc-start');
      // landing location / times / landings still missing — incomplete but saveable.
      expect(form.invalid).toBe(true);
      expect(isFlightSaveable(form)).toBe(true);
    });

    it('blocks Save when the flight date is missing', () => {
      const form = buildFlightForm(fb);
      setGate(form, null, 'ac-1', 'pe-1');
      expect(isFlightSaveable(form)).toBe(false);
    });

    it('blocks Save when the glider aircraft is missing', () => {
      const form = buildFlightForm(fb);
      setGate(form, '2026-07-01', null, 'pe-1');
      expect(isFlightSaveable(form)).toBe(false);
    });

    it('blocks Save when the pilot is missing', () => {
      const form = buildFlightForm(fb);
      setGate(form, '2026-07-01', 'ac-1', null);
      expect(isFlightSaveable(form)).toBe(false);
    });
  });

  describe('needsTowplane', () => {
    it('returns true for the aerotow start-type id', () => {
      expect(needsTowplane(TOWING)).toBe(true);
    });

    it('returns false for other start-types', () => {
      expect(needsTowplane(NON_TOWING)).toBe(false);
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
            { personId: 'pn-pilot-1', flightCrewTypeId: FLIGHT_CREW_TYPE_PILOT },
            { personId: 'pn-instr-1', flightCrewTypeId: FLIGHT_CREW_TYPE_FLIGHT_INSTRUCTOR },
            { personId: 'pn-pax-1', flightCrewTypeId: FLIGHT_CREW_TYPE_PASSENGER },
            {
              personId: 'pn-invoice-1',
              flightCrewTypeId: FLIGHT_CREW_TYPE_FLIGHT_COST_INVOICE_RECIPIENT,
            },
          ],
        }),
        undefined,
      );
      expect(snap.glider.pilotPersonId).toBe('pn-pilot-1');
      expect(snap.glider.instructorPersonId).toBe('pn-instr-1');
      expect(snap.glider.passengerPersonId).toBe('pn-pax-1');
      expect(snap.glider.invoiceRecipientPersonId).toBe('pn-invoice-1');
      expect(snap.glider.coPilotPersonId).toBeNull();
      expect(snap.glider.observerPersonId).toBeNull();
    });

    it('drops empty-Guid personIds from the crew[] collection', () => {
      const snap = flightDetailToFormSnapshot(
        gliderDetail({
          crew: [
            { personId: 'pn-pilot-1', flightCrewTypeId: FLIGHT_CREW_TYPE_PILOT },
            { personId: EMPTY_GUID, flightCrewTypeId: FLIGHT_CREW_TYPE_CO_PILOT },
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
      expect(snap.tow.pilotPersonId).toBeNull();
      expect(snap.tow.flightTypeId).toBeNull();
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
      const result = snapshotToCreateRequests(snap(NON_TOWING, 'ac-tow'));
      expect(result.glider.aircraftId).toBe('ac-glider');
      expect(result.tow).toBeUndefined();
    });

    it('returns glider+tow when start-type Towing AND tow aircraft picked', () => {
      const result = snapshotToCreateRequests(snap(TOWING, 'ac-tow'));
      expect(result.glider.aircraftId).toBe('ac-glider');
      expect(result.tow?.aircraftId).toBe('ac-tow');
    });

    it('drops tow when start-type Towing but tow aircraft NOT picked (parity discard)', () => {
      const result = snapshotToCreateRequests(snap(TOWING, null));
      expect(result.tow).toBeUndefined();
    });

    it('mirrors glider startLocationId / startTime / outboundRoute onto the tow request', () => {
      const result = snapshotToCreateRequests(snap(TOWING, 'ac-tow'));
      expect(result.tow?.startLocationId).toBe('loc-home');
      expect(result.tow?.startDateTime).toBe('2026-05-25T10:00:00Z');
      expect(result.tow?.outboundRoute).toBe('home->A');
    });

    it('discriminates GLIDER vs TOW flightAircraftType', () => {
      const result = snapshotToCreateRequests(snap(TOWING, 'ac-tow'));
      expect(result.glider.flightAircraftType).toBe('GLIDER');
      expect(result.tow?.flightAircraftType).toBe('TOW');
    });

    it('packs crew slots into the server crew[] collection by flightCrewTypeId', () => {
      const form = buildFlightForm(fb);
      form.patchValue({
        flightDate: '2026-05-25',
        startTypeId: NON_TOWING,
        glider: {
          aircraftId: 'ac-glider',
          pilotPersonId: 'pn-pilot',
          coPilotPersonId: 'pn-copilot',
          winchOperatorPersonId: 'pn-winch',
          invoiceRecipientPersonId: 'pn-invoice',
        },
      });
      const s = form.getRawValue() as Parameters<typeof snapshotToCreateRequests>[0];
      const r = snapshotToCreateRequests(s).glider;
      expect(r.crew).toEqual([
        { personId: 'pn-pilot', flightCrewTypeId: FLIGHT_CREW_TYPE_PILOT },
        { personId: 'pn-copilot', flightCrewTypeId: FLIGHT_CREW_TYPE_CO_PILOT },
        { personId: 'pn-winch', flightCrewTypeId: FLIGHT_CREW_TYPE_WINCH_OPERATOR },
        {
          personId: 'pn-invoice',
          flightCrewTypeId: FLIGHT_CREW_TYPE_FLIGHT_COST_INVOICE_RECIPIENT,
        },
      ]);
    });
  });

  describe('snapshotToUpdateRequest', () => {
    function basicSnap() {
      const form = buildFlightForm(fb);
      form.patchValue({
        flightId: 'fl-existing',
        flightDate: '2026-05-25',
        startTypeId: TOWING,
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

  describe('mapper round-trip: every editable attribute survives snapshot→request→echo', () => {
    // Fully-populated CrewSnapshot — every field non-null, every flag non-default.
    // The two side-mirrored fields (startLocationId, startTime, outboundRoute) are
    // populated identically on glider + tow so the submit-time glider→tow sync
    // doesn't flip them.
    const FULL_GLIDER: CrewSnapshot = {
      aircraftId: 'ac-glider',
      flightTypeId: 'ft-glider',
      pilotPersonId: 'pn-pilot',
      coPilotPersonId: 'pn-copilot',
      instructorPersonId: 'pn-instr',
      observerPersonId: 'pn-obs',
      passengerPersonId: 'pn-pax',
      winchOperatorPersonId: 'pn-winch',
      startLocationId: 'loc-start',
      ldgLocationId: 'loc-ldg',
      outboundRoute: 'OUT-1',
      inboundRoute: 'IN-1',
      startTime: '10:00',
      ldgTime: '11:30',
      duration: null,
      noStartTimeInformation: true,
      noLdgTimeInformation: true,
      nrOfLdgs: 3,
      engineStartOperatingCounterInSeconds: 600,
      engineEndOperatingCounterInSeconds: 1800,
      flightCostBalanceTypeId: 'fcb-pilot',
      invoiceRecipientPersonId: 'pn-invoice',
      couponNumber: 'C-12345',
      flightComment: 'round-trip test',
      isSoloFlight: true,
    };

    const FULL_TOW: CrewSnapshot = {
      ...FULL_GLIDER,
      aircraftId: 'ac-tow',
      flightTypeId: 'ft-tow',
      pilotPersonId: 'pn-tow-pilot',
      coPilotPersonId: null, // tow has no coPilot slot in legacy
      instructorPersonId: null,
      observerPersonId: null,
      passengerPersonId: null,
      winchOperatorPersonId: null,
      isSoloFlight: false,
      flightComment: 'tow leg',
    };

    function fullSnapshot(): FlightFormSnapshot {
      return {
        flightId: null,
        flightDate: '2026-05-25',
        startTypeId: TOWING,
        canUpdateRecord: true,
        canDeleteRecord: true,
        glider: { ...FULL_GLIDER },
        tow: { ...FULL_TOW },
      };
    }

    /** Simulates the server echoing a create request back as a detail. */
    function echoAsDetail(
      req: FlightCreateRequest,
      id: string,
      isSoloFlight: boolean,
    ): FlightDetail {
      return {
        id,
        flightAircraftType: req.flightAircraftType,
        aircraftId: req.aircraftId,
        flightDate: req.flightDate,
        startDateTime: req.startDateTime,
        ldgDateTime: req.ldgDateTime,
        startLocationId: req.startLocationId,
        ldgLocationId: req.ldgLocationId,
        outboundRoute: req.outboundRoute,
        inboundRoute: req.inboundRoute,
        flightTypeId: req.flightTypeId,
        startTypeId: req.startTypeId,
        nrOfLdgs: req.nrOfLdgs,
        noStartTimeInformation: req.noStartTimeInformation,
        noLdgTimeInformation: req.noLdgTimeInformation,
        engineStartOperatingCounterInSeconds: req.engineStartOperatingCounterInSeconds,
        engineEndOperatingCounterInSeconds: req.engineEndOperatingCounterInSeconds,
        comment: req.comment,
        couponNumber: req.couponNumber,
        flightCostBalanceTypeId: req.flightCostBalanceTypeId,
        isSoloFlight,
        airState: 'OnGround',
        processStateId: 'ps-new',
        version: 0,
        crew: req.crew ?? [],
      } as unknown as FlightDetail;
    }

    function assertCrewIdentity(out: CrewSnapshot, original: CrewSnapshot): void {
      expect(out.aircraftId).toBe(original.aircraftId);
      expect(out.flightTypeId).toBe(original.flightTypeId);
      expect(out.pilotPersonId).toBe(original.pilotPersonId);
      expect(out.coPilotPersonId).toBe(original.coPilotPersonId);
      expect(out.instructorPersonId).toBe(original.instructorPersonId);
      expect(out.observerPersonId).toBe(original.observerPersonId);
      expect(out.passengerPersonId).toBe(original.passengerPersonId);
      expect(out.winchOperatorPersonId).toBe(original.winchOperatorPersonId);
      expect(out.invoiceRecipientPersonId).toBe(original.invoiceRecipientPersonId);
      expect(out.startLocationId).toBe(original.startLocationId);
      expect(out.ldgLocationId).toBe(original.ldgLocationId);
      expect(out.outboundRoute).toBe(original.outboundRoute);
      expect(out.inboundRoute).toBe(original.inboundRoute);
      expect(out.startTime).toBe(original.startTime);
      expect(out.ldgTime).toBe(original.ldgTime);
      expect(out.nrOfLdgs).toBe(original.nrOfLdgs);
      expect(out.engineStartOperatingCounterInSeconds).toBe(
        original.engineStartOperatingCounterInSeconds,
      );
      expect(out.engineEndOperatingCounterInSeconds).toBe(
        original.engineEndOperatingCounterInSeconds,
      );
      expect(out.flightCostBalanceTypeId).toBe(original.flightCostBalanceTypeId);
      expect(out.couponNumber).toBe(original.couponNumber);
      expect(out.flightComment).toBe(original.flightComment);
      expect(out.noStartTimeInformation).toBe(original.noStartTimeInformation);
      expect(out.noLdgTimeInformation).toBe(original.noLdgTimeInformation);
      expect(out.isSoloFlight).toBe(original.isSoloFlight);
    }

    it('every editable glider attribute round-trips byte-identical', () => {
      const original = fullSnapshot();
      const requests = snapshotToCreateRequests(original);
      const gliderEcho = echoAsDetail(requests.glider, 'fl-g', original.glider.isSoloFlight);
      const towEcho = requests.tow
        ? echoAsDetail(requests.tow, 'fl-t', original.tow.isSoloFlight)
        : undefined;
      const reloaded = flightDetailToFormSnapshot(gliderEcho, towEcho);

      expect(reloaded.flightDate).toBe(original.flightDate);
      expect(reloaded.startTypeId).toBe(original.startTypeId);
      assertCrewIdentity(reloaded.glider, original.glider);
    });

    it('every editable tow attribute round-trips byte-identical', () => {
      const original = fullSnapshot();
      const requests = snapshotToCreateRequests(original);
      expect(requests.tow).toBeDefined();
      const gliderEcho = echoAsDetail(requests.glider, 'fl-g', original.glider.isSoloFlight);
      const towEcho = echoAsDetail(requests.tow!, 'fl-t', original.tow.isSoloFlight);
      const reloaded = flightDetailToFormSnapshot(gliderEcho, towEcho);

      assertCrewIdentity(reloaded.tow, original.tow);
    });

    it('invoiceRecipientPersonId serializes as a FLIGHT_COST_INVOICE_RECIPIENT crew row', () => {
      const original = fullSnapshot();
      const requests = snapshotToCreateRequests(original);
      const invoiceRow = requests.glider.crew?.find(
        (c) => c.flightCrewTypeId === FLIGHT_CREW_TYPE_FLIGHT_COST_INVOICE_RECIPIENT,
      );
      expect(invoiceRow).toBeDefined();
      expect(invoiceRow!.personId).toBe('pn-invoice');
    });
  });
});
