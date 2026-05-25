import { DestroyRef, Injector, runInInjectionContext } from '@angular/core';
import { NonNullableFormBuilder } from '@angular/forms';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { FlightFormCoordinator, type CoordinatorMetadata } from './flight-form.coordinator';
import { buildFlightForm, type FlightForm } from './flight-form.model';

interface MetadataStub {
  aircraft: Record<string, { nrOfSeats: number; hasEngine: boolean }>;
  flightType: Record<
    string,
    {
      isSoloFlight: boolean;
      isPassengerFlight: boolean;
      instructorRequired: boolean;
      isFlightCostBalanceSelectable: boolean;
    }
  >;
  flightCostBalanceType: Record<string, { personForInvoiceRequired: boolean }>;
  location: Record<string, { isOutboundRouteRequired: boolean; isInboundRouteRequired: boolean }>;
  clubDefaults: {
    homebaseLocationId: string | null;
    defaultTowFlightTypeId: string | null;
    resetEngineOperatingCounters: boolean;
  };
}

function stubMetadata(stub: MetadataStub): CoordinatorMetadata {
  return {
    aircraft: (id) => (id ? (stub.aircraft[id] ?? null) : null),
    flightType: (id) => (id ? (stub.flightType[id] ?? null) : null),
    flightCostBalanceType: (id) => (id ? (stub.flightCostBalanceType[id] ?? null) : null),
    location: (id) => (id ? (stub.location[id] ?? null) : null),
    clubDefaults: () => stub.clubDefaults,
  };
}

describe('FlightFormCoordinator', () => {
  let injector: Injector;
  let destroyRef: DestroyRef;
  let form: FlightForm;
  let coordinator: FlightFormCoordinator;
  let metadata: MetadataStub;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    injector = TestBed.inject(Injector);
    destroyRef = runInInjectionContext(injector, () => TestBed.inject(DestroyRef));
    const fb = new NonNullableFormBuilder();
    form = buildFlightForm(fb);
    metadata = {
      aircraft: {
        'ac-single-seat': { nrOfSeats: 1, hasEngine: false },
        'ac-two-seat': { nrOfSeats: 2, hasEngine: true },
        'ac-tow': { nrOfSeats: 2, hasEngine: true },
      },
      flightType: {
        'ft-solo': {
          isSoloFlight: true,
          isPassengerFlight: false,
          instructorRequired: false,
          isFlightCostBalanceSelectable: true,
        },
        'ft-passenger': {
          isSoloFlight: false,
          isPassengerFlight: true,
          instructorRequired: false,
          isFlightCostBalanceSelectable: true,
        },
        'ft-training': {
          isSoloFlight: false,
          isPassengerFlight: false,
          instructorRequired: true,
          isFlightCostBalanceSelectable: true,
        },
      },
      flightCostBalanceType: {
        'fcb-club': { personForInvoiceRequired: false },
        'fcb-member': { personForInvoiceRequired: true },
      },
      location: {
        'loc-home': { isOutboundRouteRequired: false, isInboundRouteRequired: false },
        'loc-other': { isOutboundRouteRequired: true, isInboundRouteRequired: true },
      },
      clubDefaults: {
        homebaseLocationId: 'loc-home',
        defaultTowFlightTypeId: 'ft-tow-default',
        resetEngineOperatingCounters: true,
      },
    };
    coordinator = new FlightFormCoordinator();
    runInInjectionContext(injector, () => {
      coordinator.attach(form, stubMetadata(metadata), destroyRef);
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  describe('isSoloFlight derivation by flight-type tri-state', () => {
    it('forces solo=true and clears co-pilot when flight-type IsSoloFlight', () => {
      form.controls.glider.controls.coPilotPersonId.setValue('pn-copilot');
      form.controls.glider.controls.flightTypeId.setValue('ft-solo');
      expect(form.controls.glider.controls.isSoloFlight.value).toBe(true);
      expect(form.controls.glider.controls.coPilotPersonId.value).toBeNull();
    });

    it('forces solo=false and clears co-pilot when flight-type IsPassengerFlight', () => {
      form.controls.glider.controls.isSoloFlight.setValue(true);
      form.controls.glider.controls.coPilotPersonId.setValue('pn-copilot');
      form.controls.glider.controls.flightTypeId.setValue('ft-passenger');
      expect(form.controls.glider.controls.isSoloFlight.value).toBe(false);
      expect(form.controls.glider.controls.coPilotPersonId.value).toBeNull();
    });

    it('leaves solo+coPilot untouched when flight-type is neither (training case)', () => {
      form.controls.glider.controls.coPilotPersonId.setValue('pn-copilot');
      form.controls.glider.controls.isSoloFlight.setValue(false);
      form.controls.glider.controls.flightTypeId.setValue('ft-training');
      expect(form.controls.glider.controls.isSoloFlight.value).toBe(false);
      expect(form.controls.glider.controls.coPilotPersonId.value).toBe('pn-copilot');
    });
  });

  describe('isSoloFlight toggle → clear co-pilot', () => {
    it('clears coPilotPersonId when isSoloFlight goes true', () => {
      form.controls.glider.controls.coPilotPersonId.setValue('pn-copilot');
      form.controls.glider.controls.isSoloFlight.setValue(true);
      expect(form.controls.glider.controls.coPilotPersonId.value).toBeNull();
    });

    it('does NOT clear coPilotPersonId when isSoloFlight goes false', () => {
      form.controls.glider.controls.coPilotPersonId.setValue('pn-copilot');
      form.controls.glider.controls.isSoloFlight.setValue(false);
      expect(form.controls.glider.controls.coPilotPersonId.value).toBe('pn-copilot');
    });
  });

  describe('single-seat aircraft → force solo', () => {
    it('forces isSoloFlight=true when picking a 1-seat aircraft and current is false', () => {
      form.controls.glider.controls.isSoloFlight.setValue(false);
      form.controls.glider.controls.coPilotPersonId.setValue('pn-copilot');
      form.controls.glider.controls.aircraftId.setValue('ac-single-seat');
      expect(form.controls.glider.controls.isSoloFlight.value).toBe(true);
      expect(form.controls.glider.controls.coPilotPersonId.value).toBeNull();
    });

    it('leaves solo untouched when picking a 2-seat aircraft', () => {
      form.controls.glider.controls.isSoloFlight.setValue(false);
      form.controls.glider.controls.aircraftId.setValue('ac-two-seat');
      expect(form.controls.glider.controls.isSoloFlight.value).toBe(false);
    });
  });

  describe('engine-counter reset on aircraft change (club-configurable)', () => {
    it('clears engine counters when club.resetEngineOperatingCounters=true', () => {
      form.controls.glider.controls.engineStartOperatingCounterInSeconds.setValue(1000);
      form.controls.glider.controls.engineEndOperatingCounterInSeconds.setValue(2000);
      form.controls.glider.controls.aircraftId.setValue('ac-two-seat');
      expect(form.controls.glider.controls.engineStartOperatingCounterInSeconds.value).toBeNull();
      expect(form.controls.glider.controls.engineEndOperatingCounterInSeconds.value).toBeNull();
    });

    it('preserves engine counters when club.resetEngineOperatingCounters=false', () => {
      metadata.clubDefaults.resetEngineOperatingCounters = false;
      // Re-attach with the updated stub.
      const fb = new NonNullableFormBuilder();
      form = buildFlightForm(fb);
      coordinator = new FlightFormCoordinator();
      runInInjectionContext(injector, () => {
        coordinator.attach(form, stubMetadata(metadata), destroyRef);
      });
      form.controls.glider.controls.engineStartOperatingCounterInSeconds.setValue(1000);
      form.controls.glider.controls.aircraftId.setValue('ac-two-seat');
      expect(form.controls.glider.controls.engineStartOperatingCounterInSeconds.value).toBe(1000);
    });
  });

  describe('invoice-recipient clear when not required', () => {
    it('clears invoiceRecipientPersonId when fcb personForInvoiceRequired flips false', () => {
      form.controls.glider.controls.invoiceRecipientPersonId.setValue('pn-recipient');
      form.controls.glider.controls.flightCostBalanceTypeId.setValue('fcb-club');
      expect(form.controls.glider.controls.invoiceRecipientPersonId.value).toBeNull();
    });

    it('preserves invoiceRecipientPersonId when fcb personForInvoiceRequired is true', () => {
      form.controls.glider.controls.invoiceRecipientPersonId.setValue('pn-recipient');
      form.controls.glider.controls.flightCostBalanceTypeId.setValue('fcb-member');
      expect(form.controls.glider.controls.invoiceRecipientPersonId.value).toBe('pn-recipient');
    });
  });

  describe('glider startLocation → mirror to ldgLocation + tow start/ldg', () => {
    it('overwrites ldgLocation on the glider AND mirrors to tow start+ldg', () => {
      form.controls.glider.controls.ldgLocationId.setValue('loc-other'); // pre-existing user pick
      form.controls.glider.controls.startLocationId.setValue('loc-home');
      expect(form.controls.glider.controls.ldgLocationId.value).toBe('loc-home');
      expect(form.controls.tow.controls.startLocationId.value).toBe('loc-home');
      expect(form.controls.tow.controls.ldgLocationId.value).toBe('loc-home');
    });
  });

  describe('noStartTimeInformation asymmetric propagation', () => {
    it('clears startTime AND propagates to tow when glider flag goes true', () => {
      form.controls.glider.controls.startTime.setValue('10:00');
      form.controls.glider.controls.noStartTimeInformation.setValue(true);
      expect(form.controls.glider.controls.startTime.value).toBeNull();
      expect(form.controls.tow.controls.noStartTimeInformation.value).toBe(true);
    });

    it('does NOT propagate the landing flag to tow (asymmetric by legacy design)', () => {
      form.controls.glider.controls.noLdgTimeInformation.setValue(true);
      expect(form.controls.tow.controls.noLdgTimeInformation.value).toBe(false);
    });
  });

  describe('tow aircraft pick → resetTowFlightDefaults (fill empties only)', () => {
    it('fills tow start/ldg location + flight-type + nrOfLdgs when all empty', () => {
      form.controls.tow.controls.aircraftId.setValue('ac-tow');
      expect(form.controls.tow.controls.startLocationId.value).toBe('loc-home');
      expect(form.controls.tow.controls.ldgLocationId.value).toBe('loc-home');
      expect(form.controls.tow.controls.flightTypeId.value).toBe('ft-tow-default');
      expect(form.controls.tow.controls.nrOfLdgs.value).toBe(1);
    });

    it('does NOT overwrite tow.startLocationId when user already picked one (mirror via glider only)', () => {
      // Pre-set tow.startLocationId via glider mirror; tow-aircraft pick should NOT overwrite.
      form.controls.glider.controls.startLocationId.setValue('loc-other');
      form.controls.tow.controls.aircraftId.setValue('ac-tow');
      expect(form.controls.tow.controls.startLocationId.value).toBe('loc-other');
    });
  });

  describe('ldgTime first-set defaults nrOfLdgs=1', () => {
    it('sets nrOfLdgs=1 when previously null and ldgTime gets a value', () => {
      expect(form.controls.glider.controls.nrOfLdgs.value).toBeNull();
      form.controls.glider.controls.ldgTime.setValue('11:30');
      expect(form.controls.glider.controls.nrOfLdgs.value).toBe(1);
    });

    it('preserves a user-set nrOfLdgs across ldgTime updates', () => {
      form.controls.glider.controls.nrOfLdgs.setValue(3);
      form.controls.glider.controls.ldgTime.setValue('11:30');
      expect(form.controls.glider.controls.nrOfLdgs.value).toBe(3);
    });

    it('applies the same rule to tow.ldgTime → tow.nrOfLdgs', () => {
      form.controls.tow.controls.ldgTime.setValue('11:30');
      expect(form.controls.tow.controls.nrOfLdgs.value).toBe(1);
    });
  });

  describe('coordinator does not deadlock when patching with emitEvent:false', () => {
    it('chained rules (flight-type → solo → coPilot clear) all settle in one tick', () => {
      // If any rule used `setValue` without `{emitEvent:false}`, this would
      // trigger valueChanges → re-fire the rule → infinite loop (Vitest hang).
      form.controls.glider.controls.coPilotPersonId.setValue('pn-copilot');
      form.controls.glider.controls.flightTypeId.setValue('ft-solo');
      // Reaching this line at all means no deadlock.
      expect(form.controls.glider.controls.isSoloFlight.value).toBe(true);
    });
  });
});
