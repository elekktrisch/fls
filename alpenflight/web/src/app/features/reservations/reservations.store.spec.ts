import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

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
  LocationListItem,
  PersonListItem,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { ReservationsStore } from './reservations.store';

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

const types: AircraftReservationTypeListItem[] = [{ id: 'rt-1', name: 'Flight', active: true }];

const aircraftPicker: AircraftPickerItem[] = [
  { id: AC_ID, immatriculation: 'HB-SAME', aircraftTypeId: 'gt-1', isTowingAircraft: false },
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
}

function reservationsServiceStub(stubs: Partial<ApiStubs>): AircraftReservationsService {
  const api = {
    pageAircraftReservations: ((start: number, size: number) =>
      (stubs.page ?? (() => of(seedPage)))(start, size)) as never,
    deleteAircraftReservation: ((id: string) =>
      (stubs.remove ?? (() => of(undefined as unknown as void)))(id)) as never,
  };
  return api as unknown as AircraftReservationsService;
}

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

  it('clears entities on session.logout', () => {
    const bus = configure(reservationsServiceStub({}));
    const store = TestBed.inject(ReservationsStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities().length).toBe(0);
    expect(store.total()).toBe(0);
  });
});
