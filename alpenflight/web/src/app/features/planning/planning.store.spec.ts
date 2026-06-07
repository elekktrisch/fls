import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { LocationsService } from '@api/generated/locations/locations.service';
import { PersonsService } from '@api/generated/persons/persons.service';
import { PlanningDaysService } from '@api/generated/planning-days/planning-days.service';
import type { LocationListItem, PersonListItem, PlanningDayDetail } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { PlanningStore } from './planning.store';

const DAY_ID = '019e30c3-2c00-7001-8000-000000000e01';
const LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
const INSTRUCTOR_ID = 'pn-019e30c3-2c00-7001-8000-0000000000b1';

const seedDay: PlanningDayDetail = {
  id: DAY_ID,
  operatingClubId: '019e30c3-2c00-7001-8000-000000000001',
  planningDate: '2026-07-04',
  locationId: LOCATION_ID,
  instructorPersonId: INSTRUCTOR_ID,
  numberOfAircraftReservations: 1,
  canUpdateRecord: true,
  canDeleteRecord: true,
};

const locations: LocationListItem[] = [
  { id: LOCATION_ID, locationName: 'Bern-Belp', isAirfield: true, isFastEntryRecord: false },
];

const persons: PersonListItem[] = [
  {
    id: INSTRUCTOR_ID,
    firstname: 'Iris',
    lastname: 'Instructor',
    isActive: true,
    isMotorPilot: false,
    isTowPilot: false,
    isGliderInstructor: true,
    isGliderPilot: true,
    isGliderTrainee: false,
    isWinchOperator: false,
    isMotorInstructor: false,
  },
];

interface ApiStubs {
  future: () => Observable<PlanningDayDetail[]>;
  remove: (id: string) => Observable<void>;
}

function planningServiceStub(stubs: Partial<ApiStubs>): PlanningDaysService {
  const api = {
    listFuturePlanningDays: (() => (stubs.future ?? (() => of([seedDay])))()) as never,
    deletePlanningDay: ((id: string) =>
      (stubs.remove ?? (() => of(undefined as unknown as void)))(id)) as never,
  };
  return api as unknown as PlanningDaysService;
}

function configure(planning: PlanningDaysService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: PlanningDaysService, useValue: planning },
      { provide: LocationsService, useValue: { listLocations: () => of(locations) } as never },
      { provide: PersonsService, useValue: { listPersons: () => of(persons) } as never },
    ],
  });
  return bus;
}

describe('PlanningStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads future days + decoration maps on construct', () => {
    configure(planningServiceStub({}));
    const store = TestBed.inject(PlanningStore);
    expect(store.entities()).toEqual([seedDay]);
    expect(store.isEmpty()).toBe(false);
    expect(store.isLoading()).toBe(false);
    expect(store.locationNameById()[LOCATION_ID]).toBe('Bern-Belp');
    expect(store.personNameById()[INSTRUCTOR_ID]).toBe('Iris Instructor');
  });

  it('surfaces a load error message on future-days failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(planningServiceStub({ future: () => throwError(() => err) }));
    const store = TestBed.inject(PlanningStore);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).not.toBeNull();
    expect(store.hasError()).toBe(true);
  });

  it('delete emits planningDay.deleted on the bus and refreshes', () => {
    const events: MutationEvent[] = [];
    let futureCalls = 0;
    const bus = configure(
      planningServiceStub({
        future: () => {
          futureCalls += 1;
          return of([seedDay]);
        },
        remove: () => of(undefined as unknown as void),
      }),
    );
    bus.subscribe((e) => events.push(e));
    const store = TestBed.inject(PlanningStore);
    const callsBefore = futureCalls;
    store.delete(DAY_ID);
    expect(events).toContainEqual({ kind: 'planningDay.deleted', id: DAY_ID });
    // refreshed: one extra future-days read after the delete.
    expect(futureCalls).toBeGreaterThan(callsBefore);
  });

  it('surfaces an inline delete error on failure (shared mapApiSaveError, not errorPatch)', () => {
    const err = new HttpErrorResponse({
      status: 403,
      statusText: 'Forbidden',
      error: { message: 'Not allowed.' },
    });
    configure(planningServiceStub({ remove: () => throwError(() => err) }));
    const store = TestBed.inject(PlanningStore);
    store.delete(DAY_ID);
    expect(store.deleteError()).toBe('Not allowed.');
  });

  it('refetches the list when planningDay.created fires on the bus', () => {
    let futureCalls = 0;
    const bus = configure(
      planningServiceStub({
        future: () => {
          futureCalls += 1;
          return of([seedDay]);
        },
      }),
    );
    TestBed.inject(PlanningStore);
    const callsBefore = futureCalls;
    bus.next({ kind: 'planningDay.created', id: DAY_ID });
    expect(futureCalls).toBeGreaterThan(callsBefore);
  });

  it('clears entities on session.logout', () => {
    const bus = configure(planningServiceStub({}));
    const store = TestBed.inject(PlanningStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities().length).toBe(0);
  });
});
