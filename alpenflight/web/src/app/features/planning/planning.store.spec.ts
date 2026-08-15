import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AircraftReservationsService } from '@api/generated/aircraft-reservations/aircraft-reservations.service';
import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import { LocationsService } from '@api/generated/locations/locations.service';
import { PersonsService } from '@api/generated/persons/persons.service';
import { PlanningDaysService } from '@api/generated/planning-days/planning-days.service';
import type {
  AircraftReservationListItem,
  LocationListItem,
  PersonListItem,
  PlanningDayCreateRequest,
  PlanningDayDetail,
  PlanningDayRuleRequest,
  PlanningDayValidateRequest,
  PlanningDayValidationResult,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { PlanningStore, uniquenessResultToErrors } from './planning.store';

const DAY_ID = '019e30c3-2c00-7001-8000-000000000e01';
const LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';
const INSTRUCTOR_ID = 'pn-019e30c3-2c00-7001-8000-0000000000b1';

const PAST_VALIDATE_DEBOUNCE_MS = 250;

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
  detail: (id: string) => Observable<PlanningDayDetail>;
  create: (req: PlanningDayCreateRequest) => Observable<PlanningDayDetail>;
  update: (id: string, req: PlanningDayCreateRequest) => Observable<PlanningDayDetail>;
  bulk: (req: PlanningDayRuleRequest) => Observable<PlanningDayDetail[]>;
  dayReservations: (date: string) => Observable<AircraftReservationListItem[]>;
  validate: (req: PlanningDayValidateRequest) => Observable<PlanningDayValidationResult>;
}

function planningServiceStub(stubs: Partial<ApiStubs>): PlanningDaysService {
  const api = {
    listFuturePlanningDays: (() => (stubs.future ?? (() => of([seedDay])))()) as never,
    getPlanningDay: ((id: string) => (stubs.detail ?? (() => of(seedDay)))(id)) as never,
    createPlanningDay: ((req: PlanningDayCreateRequest) =>
      (stubs.create ?? (() => of(seedDay)))(req)) as never,
    updatePlanningDay: ((id: string, req: PlanningDayCreateRequest) =>
      (stubs.update ?? (() => of(seedDay)))(id, req)) as never,
    deletePlanningDay: ((id: string) =>
      (stubs.remove ?? (() => of(undefined as unknown as void)))(id)) as never,
    bulkCreatePlanningDays: ((req: PlanningDayRuleRequest) =>
      (stubs.bulk ?? (() => of([seedDay])))(req)) as never,
    validatePlanningDayUniqueness: ((req: PlanningDayValidateRequest) =>
      (stubs.validate ?? (() => of({ valid: true })))(req)) as never,
  };
  return api as unknown as PlanningDaysService;
}

function configure(
  planning: PlanningDaysService,
  stubs: Partial<ApiStubs> = {},
): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: PlanningDaysService, useValue: planning },
      { provide: LocationsService, useValue: { listLocations: () => of(locations) } as never },
      { provide: PersonsService, useValue: { listPersons: () => of(persons) } as never },
      {
        provide: AircraftService,
        useValue: { listAircraftForPicker: () => of([]) } as never,
      },
      {
        provide: AircraftReservationsService,
        useValue: {
          listAircraftReservationsForDay: ((date: string) =>
            (stubs.dayReservations ?? (() => of([])))(date)) as never,
        } as never,
      },
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

  it('loadDetail populates selectedDetail for the edit form', () => {
    configure(planningServiceStub({ detail: () => of(seedDay) }));
    const store = TestBed.inject(PlanningStore);
    store.loadDetail(DAY_ID);
    expect(store.selectedDetail()).toEqual(seedDay);
    expect(store.isLoadingDetail()).toBe(false);
  });

  it('selectNew clears the detail + save error for a blank create form', () => {
    configure(planningServiceStub({}));
    const store = TestBed.inject(PlanningStore);
    store.loadDetail(DAY_ID);
    expect(store.selectedDetail()).not.toBeNull();
    store.selectNew();
    expect(store.selectedDetail()).toBeNull();
    expect(store.saveError()).toBeNull();
  });

  it('create emits planningDay.created on the bus and refetches', () => {
    const events: MutationEvent[] = [];
    let futureCalls = 0;
    const bus = configure(
      planningServiceStub({
        future: () => {
          futureCalls += 1;
          return of([seedDay]);
        },
        create: () => of(seedDay),
      }),
    );
    bus.subscribe((e) => events.push(e));
    const store = TestBed.inject(PlanningStore);
    const before = futureCalls;
    store.create({ planningDate: '2026-07-05', locationId: LOCATION_ID });
    expect(events).toContainEqual({ kind: 'planningDay.created', id: DAY_ID });
    expect(futureCalls).toBeGreaterThan(before);
  });

  it('update emits planningDay.updated on the bus and refetches', () => {
    const events: MutationEvent[] = [];
    const bus = configure(planningServiceStub({ update: () => of(seedDay) }));
    bus.subscribe((e) => events.push(e));
    const store = TestBed.inject(PlanningStore);
    store.update({ id: DAY_ID, req: { planningDate: '2026-07-05', locationId: LOCATION_ID } });
    expect(events).toContainEqual({ kind: 'planningDay.updated', id: DAY_ID });
  });

  it('surfaces the duplicate-(date,location) 409 inline via the shared key map', () => {
    const err = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { key: 'planning.day.duplicate' },
    });
    configure(planningServiceStub({ create: () => throwError(() => err) }));
    const store = TestBed.inject(PlanningStore);
    store.create({ planningDate: '2026-07-05', locationId: LOCATION_ID });
    expect(store.saveError()).toBe('A planning day already exists for this date and location.');
  });

  it('bulkCreate emits planningDay.bulkCreated with the created count and refetches', () => {
    const events: MutationEvent[] = [];
    let futureCalls = 0;
    const bus = configure(
      planningServiceStub({
        future: () => {
          futureCalls += 1;
          return of([seedDay]);
        },
        bulk: () => of([seedDay, { ...seedDay, id: 'd2' }]),
      }),
    );
    bus.subscribe((e) => events.push(e));
    const store = TestBed.inject(PlanningStore);
    const before = futureCalls;
    store.bulkCreate({
      startDate: '2026-07-01',
      endDate: '2026-07-21',
      locationId: LOCATION_ID,
      everySaturday: true,
      everySunday: true,
    });
    expect(events).toContainEqual({ kind: 'planningDay.bulkCreated', count: 2 });
    expect(futureCalls).toBeGreaterThan(before);
  });

  it('bulkCreate reports a zero count for an empty rule result (no error)', () => {
    const events: MutationEvent[] = [];
    const bus = configure(planningServiceStub({ bulk: () => of([]) }));
    bus.subscribe((e) => events.push(e));
    const store = TestBed.inject(PlanningStore);
    store.bulkCreate({ startDate: '2026-07-01', endDate: '2026-07-21', locationId: LOCATION_ID });
    expect(events).toContainEqual({ kind: 'planningDay.bulkCreated', count: 0 });
    expect(store.saveError()).toBeNull();
  });

  it('bulkCreate surfaces a 422 range error inline (shared mapApiSaveError)', () => {
    const err = new HttpErrorResponse({
      status: 422,
      statusText: 'Unprocessable Entity',
      error: { message: 'Range too large.' },
    });
    configure(planningServiceStub({ bulk: () => throwError(() => err) }));
    const store = TestBed.inject(PlanningStore);
    store.bulkCreate({ startDate: '2026-07-01', endDate: '2030-07-21', locationId: LOCATION_ID });
    expect(store.saveError()).toBe('Range too large.');
  });

  it('loadDayReservations filters the J-5 day read to the day location', () => {
    const here: AircraftReservationListItem = {
      id: 'r1',
      aircraftId: 'ac-1',
      start: '2026-07-04T10:00:00Z',
      end: '2026-07-04T11:00:00Z',
      isAllDay: false,
      pilotPersonId: INSTRUCTOR_ID,
      locationId: LOCATION_ID,
    };
    const elsewhere: AircraftReservationListItem = { ...here, id: 'r2', locationId: 'loc-other' };
    configure(planningServiceStub({}), { dayReservations: () => of([here, elsewhere]) });
    const store = TestBed.inject(PlanningStore);
    store.loadDayReservations({ date: '2026-07-04', locationId: LOCATION_ID });
    expect(store.dayReservations().map((r) => r.id)).toEqual(['r1']);
  });

  it('validateUniqueness (debounced) surfaces a duplicate inline on a valid:false result', () => {
    vi.useFakeTimers();
    try {
      const dup: PlanningDayValidationResult = {
        valid: false,
        field: 'planningDate',
        message: 'A planning day already exists for this date and location.',
      };
      configure(planningServiceStub({ validate: () => of(dup) }));
      const store = TestBed.inject(PlanningStore);
      store.validateUniqueness({ planningDate: '2026-07-04', locationId: LOCATION_ID });
      vi.advanceTimersByTime(PAST_VALIDATE_DEBOUNCE_MS);
      expect(store.uniquenessValidating()).toBe(false);
      expect(store.uniquenessMessage()).toBe(
        'A planning day already exists for this date and location.',
      );
      expect(store.uniquenessErrors()).toEqual({
        duplicate: 'A planning day already exists for this date and location.',
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('validateUniqueness clears the inline message on a valid:true result', () => {
    vi.useFakeTimers();
    try {
      configure(planningServiceStub({ validate: () => of({ valid: true }) }));
      const store = TestBed.inject(PlanningStore);
      store.validateUniqueness({ planningDate: '2026-07-05', locationId: LOCATION_ID });
      vi.advanceTimersByTime(PAST_VALIDATE_DEBOUNCE_MS);
      expect(store.uniquenessMessage()).toBeNull();
      expect(store.uniquenessErrors()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('validateUniqueness clears inline state on a probe error (save-path 409 is the backstop)', () => {
    vi.useFakeTimers();
    try {
      const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
      configure(planningServiceStub({ validate: () => throwError(() => err) }));
      const store = TestBed.inject(PlanningStore);
      store.validateUniqueness({ planningDate: '2026-07-05', locationId: LOCATION_ID });
      vi.advanceTimersByTime(PAST_VALIDATE_DEBOUNCE_MS);
      expect(store.uniquenessValidating()).toBe(false);
      expect(store.uniquenessMessage()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('clearUniquenessValidation resets the inline state (date/location cleared)', () => {
    configure(planningServiceStub({}));
    const store = TestBed.inject(PlanningStore);
    store.clearUniquenessValidation();
    expect(store.uniquenessValidating()).toBe(false);
    expect(store.uniquenessResult()).toBeNull();
    expect(store.uniquenessMessage()).toBeNull();
  });
});

describe('uniquenessResultToErrors (T-07 pure mapper)', () => {
  it('returns null for an absent or passing result', () => {
    expect(uniquenessResultToErrors(null)).toBeNull();
    expect(uniquenessResultToErrors(undefined)).toBeNull();
    expect(uniquenessResultToErrors({ valid: true })).toBeNull();
  });

  it('keys a duplicate error (with the message) for a failing result', () => {
    expect(uniquenessResultToErrors({ valid: false, message: 'dup' })).toEqual({
      duplicate: 'dup',
    });
  });

  it('falls back to a truthy slot when a failing result carries no message', () => {
    expect(uniquenessResultToErrors({ valid: false })).toEqual({ duplicate: true });
  });
});
