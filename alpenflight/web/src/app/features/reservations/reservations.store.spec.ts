import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AircraftReservationTypesService } from '@api/generated/aircraft-reservation-types/aircraft-reservation-types.service';
import { AircraftReservationsService } from '@api/generated/aircraft-reservations/aircraft-reservations.service';
import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import { LocationsService } from '@api/generated/locations/locations.service';
import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  AircraftPickerItem,
  AircraftReservationListItem,
  AircraftReservationPage,
  AircraftReservationTypeListItem,
  AircraftReservationValidateRequest,
  LocationListItem,
  PersonListItem,
  ReservationValidationResult,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { ReservationsStore, overlapResultToErrors } from './reservations.store';

const AC_ID = 'ac-019e30c3-2c00-7001-8000-00000000a001';
const PILOT_ID = 'pn-019e30c3-2c00-7001-8000-0000000000p1';
const LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-00000000l001';
const RES_ID = 'res-019e30c3-2c00-7001-8000-000000000001';

const seedRow: AircraftReservationListItem = {
  id: RES_ID,
  aircraftId: AC_ID,
  start: '2026-07-01T10:00:00Z',
  end: '2026-07-01T11:00:00Z',
  isAllDay: false,
  pilotPersonId: PILOT_ID,
  locationId: LOCATION_ID,
  reservationTypeName: 'Flight',
};

const seedPage: AircraftReservationPage = {
  items: [seedRow],
  pageStart: 0,
  pageSize: 20,
  totalRows: 1,
};

const types: AircraftReservationTypeListItem[] = [
  { id: 'rt-1', name: 'Flight', active: true, instructorRequired: false },
];

const aircraftPicker: AircraftPickerItem[] = [
  {
    id: AC_ID,
    immatriculation: 'HB-SAME',
    aircraftTypeId: 'gt-1',
    isTowingAircraft: false,
    nrOfSeats: 1,
  },
];

const persons: PersonListItem[] = [
  {
    id: PILOT_ID,
    firstname: 'Anna',
    lastname: 'Pilot',
    isActive: true,
    isMotorPilot: false,
    isTowPilot: false,
    isGliderInstructor: false,
    isGliderPilot: true,
    isGliderTrainee: false,
    isWinchOperator: false,
    isMotorInstructor: false,
  },
];

const locations: LocationListItem[] = [
  {
    id: LOCATION_ID,
    locationName: 'Bern-Belp',
    isAirfield: true,
    isFastEntryRecord: false,
  },
];

interface ApiStubs {
  page: (start: number, size: number) => Observable<AircraftReservationPage>;
  remove: (id: string) => Observable<void>;
  validate: (req: AircraftReservationValidateRequest) => Observable<ReservationValidationResult>;
}

function reservationsServiceStub(stubs: Partial<ApiStubs>): AircraftReservationsService {
  const api = {
    pageAircraftReservations: ((start: number, size: number) =>
      (stubs.page ?? (() => of(seedPage)))(start, size)) as never,
    deleteAircraftReservation: ((id: string) =>
      (stubs.remove ?? (() => of(undefined as unknown as void)))(id)) as never,
    validateAircraftReservationOverlap: ((req: AircraftReservationValidateRequest) =>
      (stubs.validate ?? (() => of({ valid: true } as ReservationValidationResult)))(req)) as never,
  };
  return api as unknown as AircraftReservationsService;
}

const validateProbe: AircraftReservationValidateRequest = {
  aircraftId: AC_ID,
  start: '2026-07-01T10:00:00Z',
  end: '2026-07-01T11:00:00Z',
  isAllDay: false,
};

function configure(reservations: AircraftReservationsService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: AircraftReservationsService, useValue: reservations },
      {
        provide: AircraftReservationTypesService,
        useValue: { listAircraftReservationTypes: () => of(types) } as never,
      },
      {
        provide: AircraftService,
        useValue: { listAircraftForPicker: () => of(aircraftPicker) } as never,
      },
      { provide: PersonsService, useValue: { listPersons: () => of(persons) } as never },
      { provide: LocationsService, useValue: { listLocations: () => of(locations) } as never },
    ],
  });
  return bus;
}

describe('ReservationsStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads the first page + decoration maps on construct', () => {
    configure(reservationsServiceStub({}));
    const store = TestBed.inject(ReservationsStore);
    expect(store.entities()).toEqual([seedRow]);
    expect(store.total()).toBe(1);
    expect(store.isLoading()).toBe(false);
    expect(store.isEmpty()).toBe(false);
    expect(store.immatById()[AC_ID]).toBe('HB-SAME');
    expect(store.pilotNameById()[PILOT_ID]).toBe('Anna Pilot');
    expect(store.locationNameById()[LOCATION_ID]).toBe('Bern-Belp');
  });

  it('surfaces a load error message on page failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(reservationsServiceStub({ page: () => throwError(() => err) }));
    const store = TestBed.inject(ReservationsStore);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).not.toBeNull();
    expect(store.hasError()).toBe(true);
  });

  it('goToPage requests the correct zero-based offset', () => {
    const offsets: number[] = [];
    configure(
      reservationsServiceStub({
        page: (start) => {
          offsets.push(start);
          return of(seedPage);
        },
      }),
    );
    const store = TestBed.inject(ReservationsStore);
    store.goToPage(3);
    // page 1 → offset 0 (init), page 3 → offset 40.
    expect(offsets).toContain(40);
  });

  it('delete emits reservation.deleted on the bus and refreshes', () => {
    const events: MutationEvent[] = [];
    const offsets: number[] = [];
    const bus = configure(
      reservationsServiceStub({
        page: (start) => {
          offsets.push(start);
          return of(seedPage);
        },
        remove: () => of(undefined as unknown as void),
      }),
    );
    bus.subscribe((e) => events.push(e));
    const store = TestBed.inject(ReservationsStore);
    const callsBefore = offsets.length;
    store.delete(RES_ID);
    expect(events).toContainEqual({ kind: 'reservation.deleted', reservationId: RES_ID });
    // refreshed: one extra page read after the delete.
    expect(offsets.length).toBeGreaterThan(callsBefore);
  });

  it('refetches the list when a reservation.created event fires on the bus (J-5 T-27)', () => {
    // The root-scoped store does NOT re-init when the edit form navigates back
    // to /reservations after a create, so it must refetch on the mutation-bus
    // event — otherwise the just-created row never renders (the genuine app gap
    // that red-ed the real-idp create spec). Mirrors the J-2 flight store.
    const offsets: number[] = [];
    const bus = configure(
      reservationsServiceStub({
        page: (start) => {
          offsets.push(start);
          return of(seedPage);
        },
      }),
    );
    // Instantiate the store so its onInit bus subscription is wired (the return
    // value is unused — the assertion reads `offsets` via the service stub).
    TestBed.inject(ReservationsStore);
    const callsBefore = offsets.length;
    bus.next({ kind: 'reservation.created', reservationId: RES_ID });
    expect(offsets.length).toBeGreaterThan(callsBefore);
  });

  it('refetches the list when a reservation.updated event fires on the bus (J-5 T-27)', () => {
    const offsets: number[] = [];
    const bus = configure(
      reservationsServiceStub({
        page: (start) => {
          offsets.push(start);
          return of(seedPage);
        },
      }),
    );
    // Instantiate the store so its onInit bus subscription is wired (the return
    // value is unused — the assertion reads `offsets` via the service stub).
    TestBed.inject(ReservationsStore);
    const callsBefore = offsets.length;
    bus.next({ kind: 'reservation.updated', reservationId: RES_ID });
    expect(offsets.length).toBeGreaterThan(callsBefore);
  });

  it('validateOverlap surfaces a failing result on the inline overlap selectors (T-06)', async () => {
    vi.useFakeTimers();
    try {
      const fail: ReservationValidationResult = {
        valid: false,
        field: 'start',
        message: 'aircraft.reservation.overlap',
      };
      configure(reservationsServiceStub({ validate: () => of(fail) }));
      const store = TestBed.inject(ReservationsStore);
      store.validateOverlap(validateProbe);
      // The store debounces the probe ~200ms before calling the endpoint.
      await vi.advanceTimersByTimeAsync(250);
      expect(store.overlapValidating()).toBe(false);
      expect(store.overlapMessage()).toBe('aircraft.reservation.overlap');
      expect(store.overlapErrors()).toEqual({ overlap: 'aircraft.reservation.overlap' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('validateOverlap clears the inline error on a passing result (T-06)', async () => {
    vi.useFakeTimers();
    try {
      configure(reservationsServiceStub({ validate: () => of({ valid: true }) }));
      const store = TestBed.inject(ReservationsStore);
      store.validateOverlap(validateProbe);
      await vi.advanceTimersByTimeAsync(250);
      expect(store.overlapMessage()).toBeNull();
      expect(store.overlapErrors()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('overlapResultToErrors maps only a failing result to an inline slot', () => {
    expect(overlapResultToErrors(null)).toBeNull();
    expect(overlapResultToErrors({ valid: true })).toBeNull();
    expect(overlapResultToErrors({ valid: false, field: 'start', message: 'msg' })).toEqual({
      overlap: 'msg',
    });
    // A failing result with no message still flags the field (truthy slot).
    expect(overlapResultToErrors({ valid: false })).toEqual({ overlap: true });
  });

  it('clears entities on session.logout', () => {
    const bus = configure(reservationsServiceStub({}));
    const store = TestBed.inject(ReservationsStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities().length).toBe(0);
    expect(store.total()).toBe(0);
  });
});
