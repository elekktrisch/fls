import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import { ClubsService } from '@api/generated/clubs/clubs.service';
import { DiscoveryFlightDaysService } from '@api/generated/discovery-flight-days/discovery-flight-days.service';
import { FlightTypesService } from '@api/generated/flight-types/flight-types.service';
import type {
  ClubCreateRequest,
  ClubResponse,
  ClubUpdateRequest,
  DiscoveryFlightDayCreateRequest,
  DiscoveryFlightDayResponse,
  FlightTypeListItem,
} from '@api/generated/model';

import { SessionStore, type AppRole } from '@core/session/session.store';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { ClubsStore } from './clubs.store';

const sampleClub: ClubResponse = {
  id: '019e30c3-2c00-7001-8000-000000000001',
  name: 'Seed Club',
  slug: 'seed-club-1',
  clubKey: 'SEED',
  publicRegistrationEnabled: false,
  countryId: '019e2e15-2c00-74be-8000-0000000004be',
  clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
};

type StubbedApi = Pick<
  ClubsService,
  'listClubs' | 'getClub' | 'createClub' | 'updateClub' | 'deleteClub'
>;

interface ApiStubs {
  list: () => Observable<ClubResponse[]>;
  get: (id: string) => Observable<ClubResponse>;
  create: (req: ClubCreateRequest) => Observable<ClubResponse>;
  update: (id: string, req: ClubUpdateRequest) => Observable<ClubResponse>;
  remove: (id: string) => Observable<void>;
}

function clubsServiceStub(stubs: Partial<ApiStubs>): ClubsService {
  const api: StubbedApi = {
    listClubs: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([])))();
    }) as ClubsService['listClubs'],
    getClub: ((id: string, options?: unknown) => {
      void options;
      return (stubs.get ?? (() => of(sampleClub)))(id);
    }) as ClubsService['getClub'],
    createClub: ((req: ClubCreateRequest, options?: unknown) => {
      void options;
      return (stubs.create ?? (() => of(sampleClub)))(req);
    }) as ClubsService['createClub'],
    updateClub: ((id: string, req: ClubUpdateRequest, options?: unknown) => {
      void options;
      return (stubs.update ?? (() => of(sampleClub)))(id, req);
    }) as ClubsService['updateClub'],
    deleteClub: ((id: string, options?: unknown) => {
      void options;
      return (stubs.remove ?? (() => of(undefined as unknown as void)))(id);
    }) as ClubsService['deleteClub'],
  };
  return api as unknown as ClubsService;
}

interface DayStubs {
  list: () => Observable<DiscoveryFlightDayResponse[]>;
  publish: (req: DiscoveryFlightDayCreateRequest) => Observable<DiscoveryFlightDayResponse>;
  withdraw: (id: string) => Observable<void>;
}

const sampleDay: DiscoveryFlightDayResponse = { id: 'dfd-1', eventDate: '2026-09-12' };

function daysServiceStub(stubs: Partial<DayStubs> = {}): DiscoveryFlightDaysService {
  const api = {
    listDiscoveryFlightDays: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([])))();
    }) as DiscoveryFlightDaysService['listDiscoveryFlightDays'],
    publishDiscoveryFlightDay: ((req: DiscoveryFlightDayCreateRequest, options?: unknown) => {
      void options;
      return (stubs.publish ?? (() => of(sampleDay)))(req);
    }) as DiscoveryFlightDaysService['publishDiscoveryFlightDay'],
    withdrawDiscoveryFlightDay: ((id: string, options?: unknown) => {
      void options;
      return (stubs.withdraw ?? (() => of(undefined as unknown as void)))(id);
    }) as DiscoveryFlightDaysService['withdrawDiscoveryFlightDay'],
  };
  return api as unknown as DiscoveryFlightDaysService;
}

function flightTypesServiceStub(
  list: () => Observable<FlightTypeListItem[]> = () => of([]),
): FlightTypesService {
  const api = {
    listFlightTypes: ((options?: unknown) => {
      void options;
      return list();
    }) as FlightTypesService['listFlightTypes'],
  };
  return api as unknown as FlightTypesService;
}

function sessionStub(roles: readonly AppRole[]): SessionStoreInstance {
  return {
    isSystemAdmin: () => roles.includes('SYSTEM_ADMINISTRATOR'),
    isFlightOperator: () => roles.includes('FLIGHT_OPERATOR'),
  } as unknown as SessionStoreInstance;
}

type SessionStoreInstance = InstanceType<typeof SessionStore>;

function configure(
  api: ClubsService,
  days: DiscoveryFlightDaysService = daysServiceStub(),
  flightTypes: FlightTypesService = flightTypesServiceStub(),
  roles: readonly AppRole[] = ['SYSTEM_ADMINISTRATOR'],
): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: ClubsService, useValue: api },
      { provide: DiscoveryFlightDaysService, useValue: days },
      { provide: FlightTypesService, useValue: flightTypes },
      { provide: SessionStore, useValue: sessionStub(roles) },
    ],
  });
  return bus;
}

describe('ClubsStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty and loads on construct', () => {
    configure(clubsServiceStub({ list: () => of([sampleClub]) }));
    const store = TestBed.inject(ClubsStore);

    expect(store.entities()).toEqual([sampleClub]);
    expect(store.isLoading()).toBe(false);
    expect(store.isEmpty()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('loadAll sets loadError on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(clubsServiceStub({ list: () => throwError(() => err) }));
    const store = TestBed.inject(ClubsStore);

    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).not.toBeNull();
    expect(store.hasError()).toBe(true);
  });

  it('create adds the new entity to the store and emits club.created', () => {
    const created: ClubResponse = { ...sampleClub, id: 'new', slug: 'new-club', name: 'New' };
    const bus = configure(
      clubsServiceStub({
        list: () => of([sampleClub]),
        create: () => of(created),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(ClubsStore);
    store.create({
      name: 'New',
      slug: 'new-club',
      clubKey: 'NEW',
      publicRegistrationEnabled: false,
      countryId: '019e2e15-2c00-74be-8000-0000000004be',
      clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
    });

    expect(store.entities().some((c) => c.id === 'new')).toBe(true);
    expect(events).toEqual([{ kind: 'club.created', id: 'new' }]);
  });

  it('create surfaces 409 as a friendly saveError', () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    configure(
      clubsServiceStub({
        list: () => of([sampleClub]),
        create: () => throwError(() => err),
      }),
    );

    const store = TestBed.inject(ClubsStore);
    store.create({
      name: 'Dup',
      slug: 'seed-club-1',
      clubKey: 'DUP',
      publicRegistrationEnabled: false,
      countryId: '019e2e15-2c00-74be-8000-0000000004be',
      clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
    });

    expect(store.saveError()).toContain('seed-club-1');
    expect(store.saveError()).toContain('already in use');
    expect(store.saveErrorKind()).toBe('slug-duplicate');
  });

  it('create routes a 409 with field=clubKey to the club-key error shape (J-26 T-07)', () => {
    const err = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { field: 'clubKey', title: 'Club key already in use' },
    });
    configure(
      clubsServiceStub({
        list: () => of([sampleClub]),
        create: () => throwError(() => err),
      }),
    );

    const store = TestBed.inject(ClubsStore);
    store.create({
      name: 'Dup Key',
      slug: 'unique-slug',
      clubKey: 'SEED',
      publicRegistrationEnabled: false,
      countryId: '019e2e15-2c00-74be-8000-0000000004be',
      clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
    });

    expect(store.saveError()).toContain('SEED');
    expect(store.saveError()).toContain('Club key');
    expect(store.saveError()).not.toContain('unique-slug');
    expect(store.saveErrorKind()).toBe('club-key-duplicate');
  });

  it('update patches the matching entity and emits club.updated', () => {
    const renamed: ClubResponse = { ...sampleClub, name: 'Renamed Seed' };
    const bus = configure(
      clubsServiceStub({
        list: () => of([sampleClub]),
        update: () => of(renamed),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(ClubsStore);
    store.update({
      id: sampleClub.id!,
      req: {
        name: 'Renamed Seed',
        slug: 'seed-club-1',
        publicRegistrationEnabled: false,
        countryId: '019e2e15-2c00-74be-8000-0000000004be',
        clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
      },
    });

    expect(store.entities()[0]?.name).toBe('Renamed Seed');
    expect(events).toEqual([{ kind: 'club.updated', id: sampleClub.id }]);
  });

  it('delete removes the entity and emits club.deleted', () => {
    const bus = configure(
      clubsServiceStub({
        list: () => of([sampleClub]),
        remove: () => of(undefined as unknown as void),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(ClubsStore);
    store.delete(sampleClub.id!);

    expect(store.entities()).toEqual([]);
    expect(events).toEqual([{ kind: 'club.deleted', id: sampleClub.id }]);
  });

  it('clears entities on session.logout via MUTATION_BUS', () => {
    const bus = configure(clubsServiceStub({ list: () => of([sampleClub]) }));
    const store = TestBed.inject(ClubsStore);

    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities()).toEqual([]);
  });

  it('clears entities on session.tenantSwitch via MUTATION_BUS', () => {
    const bus = configure(clubsServiceStub({ list: () => of([sampleClub]) }));
    const store = TestBed.inject(ClubsStore);

    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-2' });
    expect(store.entities()).toEqual([]);
  });

  it('select stores the selected id and selectedClub returns the entity', () => {
    configure(clubsServiceStub({ list: () => of([sampleClub]) }));
    const store = TestBed.inject(ClubsStore);

    expect(store.selectedClub()).toBeNull();
    store.select(sampleClub.id!);
    expect(store.selectedClub()).toEqual(sampleClub);
  });
});

describe('ClubsStore — own-club read', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('does not call the cross-tenant catalog for a club administrator', () => {
    let listed = 0;
    configure(
      clubsServiceStub({
        list: () => {
          listed += 1;
          return of([sampleClub]);
        },
      }),
      daysServiceStub(),
      flightTypesServiceStub(),
      ['CLUB_ADMINISTRATOR'],
    );
    TestBed.inject(ClubsStore);

    expect(listed).toBe(0);
  });

  it('still loads the catalog for a flight operator', () => {
    let listed = 0;
    configure(
      clubsServiceStub({
        list: () => {
          listed += 1;
          return of([sampleClub]);
        },
      }),
      daysServiceStub(),
      flightTypesServiceStub(),
      ['FLIGHT_OPERATOR'],
    );
    TestBed.inject(ClubsStore);

    expect(listed).toBe(1);
  });

  it('loadOne makes the club available to the edit screen without the catalog', () => {
    configure(
      clubsServiceStub({ list: () => of([]), get: () => of(sampleClub) }),
      daysServiceStub(),
      flightTypesServiceStub(),
      ['CLUB_ADMINISTRATOR'],
    );
    const store = TestBed.inject(ClubsStore);

    store.loadOne(sampleClub.id!);
    store.select(sampleClub.id!);

    expect(store.selectedClub()).toEqual(sampleClub);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('loadOne refreshes an entity the catalog already supplied', () => {
    const renamed: ClubResponse = { ...sampleClub, name: 'Renamed Club' };
    configure(clubsServiceStub({ list: () => of([sampleClub]), get: () => of(renamed) }));
    const store = TestBed.inject(ClubsStore);

    store.loadOne(sampleClub.id!);

    expect(store.entities()).toEqual([renamed]);
  });

  it('loadOne surfaces a denied read instead of leaving the form blank', () => {
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    configure(
      clubsServiceStub({ list: () => of([]), get: () => throwError(() => err) }),
      daysServiceStub(),
      flightTypesServiceStub(),
      ['CLUB_ADMINISTRATOR'],
    );
    const store = TestBed.inject(ClubsStore);

    store.loadOne('clb-019e30c3-2c00-7001-8000-000000000002');

    expect(store.loadError()).toBe('You are not allowed to view this club.');
    expect(store.isLoading()).toBe(false);
    expect(store.entities()).toEqual([]);
  });
});

describe('ClubsStore — discovery-flight days', () => {
  const earlier: DiscoveryFlightDayResponse = { id: 'dfd-2', eventDate: '2026-08-30' };

  afterEach(() => TestBed.resetTestingModule());

  it('loads the club days sorted by event date', () => {
    configure(
      clubsServiceStub({ list: () => of([sampleClub]) }),
      daysServiceStub({ list: () => of([sampleDay, earlier]) }),
    );
    const store = TestBed.inject(ClubsStore);
    store.loadDiscoveryFlightDays();

    expect(store.discoveryFlightDays().map((d) => d.eventDate)).toEqual([
      '2026-08-30',
      '2026-09-12',
    ]);
    expect(store.discoveryDayError()).toBeNull();
  });

  it('publishing appends the new day in date order', () => {
    configure(
      clubsServiceStub({ list: () => of([sampleClub]) }),
      daysServiceStub({ list: () => of([sampleDay]), publish: () => of(earlier) }),
    );
    const store = TestBed.inject(ClubsStore);
    store.loadDiscoveryFlightDays();
    store.publishDiscoveryFlightDay('2026-08-30');

    expect(store.discoveryFlightDays().map((d) => d.eventDate)).toEqual([
      '2026-08-30',
      '2026-09-12',
    ]);
  });

  it('a duplicate live date surfaces the 409 without touching the list', () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    configure(
      clubsServiceStub({ list: () => of([sampleClub]) }),
      daysServiceStub({ list: () => of([sampleDay]), publish: () => throwError(() => err) }),
    );
    const store = TestBed.inject(ClubsStore);
    store.loadDiscoveryFlightDays();
    store.publishDiscoveryFlightDay('2026-09-12');

    expect(store.discoveryDayError()).toContain('already offered');
    expect(store.discoveryFlightDays()).toHaveLength(1);
  });

  it('a rejected past date surfaces the server message', () => {
    const err = new HttpErrorResponse({
      status: 400,
      statusText: 'Bad Request',
      error: { field: 'eventDate', message: 'Event date must not be in the past.' },
    });
    configure(
      clubsServiceStub({ list: () => of([sampleClub]) }),
      daysServiceStub({ publish: () => throwError(() => err) }),
    );
    const store = TestBed.inject(ClubsStore);
    store.publishDiscoveryFlightDay('2020-01-01');

    expect(store.discoveryDayError()).toBe('Event date must not be in the past.');
  });

  it('withdrawing removes just that day', () => {
    configure(
      clubsServiceStub({ list: () => of([sampleClub]) }),
      daysServiceStub({ list: () => of([sampleDay, earlier]) }),
    );
    const store = TestBed.inject(ClubsStore);
    store.loadDiscoveryFlightDays();
    store.withdrawDiscoveryFlightDay('dfd-1');

    expect(store.discoveryFlightDays().map((d) => d.id)).toEqual(['dfd-2']);
  });

  it('clears the own-club slices on session.tenantSwitch', () => {
    const bus = configure(
      clubsServiceStub({ list: () => of([sampleClub]) }),
      daysServiceStub({ list: () => of([sampleDay]) }),
      flightTypesServiceStub(() =>
        of([
          {
            id: 'ft-1',
            flightTypeName: 'Schnupperflug',
            isForGliderFlights: true,
            isForTowFlights: false,
            isForMotorFlights: false,
            isFlightCostBalanceSelectable: false,
          },
        ]),
      ),
    );
    const store = TestBed.inject(ClubsStore);
    store.loadDiscoveryFlightDays();
    store.loadFlightTypes();
    expect(store.discoveryFlightDays()).toHaveLength(1);
    expect(store.flightTypes()).toHaveLength(1);

    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-2' });

    expect(store.discoveryFlightDays()).toEqual([]);
    expect(store.flightTypes()).toEqual([]);
  });
});
