import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { FlightTypesService } from '@api/generated/flight-types/flight-types.service';
import type {
  FlightTypeCreateRequest,
  FlightTypeDetail,
  FlightTypeListItem,
  FlightTypeUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { FlightTypesStore } from './flight-types.store';

const sampleItem: FlightTypeListItem = {
  id: 'ft-019e30c3-2c00-7001-8000-000000000001',
  flightTypeName: 'Schulflug',
  isForGliderFlights: true,
  isForTowFlights: false,
  isForMotorFlights: false,
  isFlightCostBalanceSelectable: false,
};

const sampleDetail: FlightTypeDetail = {
  id: sampleItem.id!,
  flightTypeName: 'Schulflug',
  isInstructorRequired: true,
  isObserverPilotOrInstructorRequired: false,
  isCheckFlight: false,
  isPassengerFlight: false,
  isSoloFlight: false,
  isForGliderFlights: true,
  isForTowFlights: false,
  isForMotorFlights: false,
  isFlightCostBalanceSelectable: false,
  isCouponNumberRequired: false,
  isForAircraftReservationType: false,
};

interface ApiStubs {
  list: () => Observable<FlightTypeListItem[]>;
  get: (id: string) => Observable<FlightTypeDetail>;
  create: (req: FlightTypeCreateRequest) => Observable<FlightTypeDetail>;
  update: (id: string, req: FlightTypeUpdateRequest) => Observable<FlightTypeDetail>;
  remove: (id: string) => Observable<void>;
}

function apiStub(stubs: Partial<ApiStubs>): FlightTypesService {
  const api = {
    listFlightTypes: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([])))();
    }) as FlightTypesService['listFlightTypes'],
    getFlightType: ((id: string, options?: unknown) => {
      void options;
      return (stubs.get ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id);
    }) as FlightTypesService['getFlightType'],
    registerFlightType: ((req: FlightTypeCreateRequest, options?: unknown) => {
      void options;
      return (stubs.create ?? (() => of(sampleDetail)))(req);
    }) as FlightTypesService['registerFlightType'],
    updateFlightType: ((id: string, req: FlightTypeUpdateRequest, options?: unknown) => {
      void options;
      return (stubs.update ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id, req);
    }) as FlightTypesService['updateFlightType'],
    deleteFlightType: ((id: string, options?: unknown) => {
      void options;
      return (stubs.remove ?? (() => of(undefined as unknown as void)))(id);
    }) as FlightTypesService['deleteFlightType'],
  };
  return api as unknown as FlightTypesService;
}

function configure(api: FlightTypesService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: FlightTypesService, useValue: api },
    ],
  });
  return bus;
}

const baseCreateReq: FlightTypeCreateRequest = {
  flightTypeName: 'NEW',
  isInstructorRequired: false,
  isObserverPilotOrInstructorRequired: false,
  isCheckFlight: false,
  isPassengerFlight: false,
  isSoloFlight: false,
  isForGliderFlights: true,
  isForTowFlights: false,
  isForMotorFlights: false,
  isFlightCostBalanceSelectable: false,
  isCouponNumberRequired: false,
  isForAircraftReservationType: false,
};

const baseUpdateReq: FlightTypeUpdateRequest = { ...baseCreateReq, flightTypeName: 'Schulflug' };

describe('FlightTypesStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty and loads on construct', () => {
    configure(apiStub({ list: () => of([sampleItem]) }));
    const store = TestBed.inject(FlightTypesStore);
    expect(store.entities()).toEqual([sampleItem]);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('loadAll surfaces an error on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(apiStub({ list: () => throwError(() => err) }));
    const store = TestBed.inject(FlightTypesStore);
    expect(store.loadError()).not.toBeNull();
    expect(store.hasError()).toBe(true);
  });

  it('create adds a list entity and emits flightType.created', () => {
    const created: FlightTypeDetail = {
      ...sampleDetail,
      id: 'ft-019e30c3-2c00-7001-8000-00000000000a',
      flightTypeName: 'NEW',
    };
    let listState: FlightTypeListItem[] = [sampleItem];
    const bus = configure(
      apiStub({
        list: () => of(listState),
        create: () => {
          listState = [sampleItem, { ...sampleItem, id: created.id!, flightTypeName: 'NEW' }];
          return of(created);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(FlightTypesStore);
    store.create({ ...baseCreateReq });
    expect(store.entities().some((ft) => ft.id === created.id)).toBe(true);
    expect(store.selectedFlightType()?.id).toBe(created.id);
    expect(events).toEqual([{ kind: 'flightType.created', id: created.id }]);
  });

  it('create surfaces 409 as name-duplicate with name in saveError', () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    configure(
      apiStub({
        list: () => of([sampleItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(FlightTypesStore);
    store.create({ ...baseCreateReq, flightTypeName: 'DUP' });
    expect(store.saveError()).toContain('DUP');
    expect(store.saveError()).toContain('already in use');
    expect(store.saveErrorKind()).toBe('name-duplicate');
  });

  it('create routes a 409 with field=flightCode to code-duplicate (not name-duplicate)', () => {
    const err = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { field: 'flightCode', message: 'common.errors.duplicate' },
    });
    configure(
      apiStub({
        list: () => of([sampleItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(FlightTypesStore);
    store.create({ ...baseCreateReq, flightCode: 'S' });
    expect(store.saveError()).toContain('"S"');
    expect(store.saveError()).toContain('code');
    expect(store.saveErrorKind()).toBe('code-duplicate');
  });

  it('update routes a 409 with field=flightCode to code-duplicate', () => {
    const err = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { field: 'flightCode' },
    });
    configure(
      apiStub({
        list: () => of([sampleItem]),
        update: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(FlightTypesStore);
    store.update({ id: sampleItem.id!, req: { ...baseUpdateReq, flightCode: 'S' } });
    expect(store.saveErrorKind()).toBe('code-duplicate');
  });

  it('create surfaces 403 as forbidden', () => {
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    configure(
      apiStub({
        list: () => of([sampleItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(FlightTypesStore);
    store.create({ ...baseCreateReq });
    expect(store.saveErrorKind()).toBe('forbidden');
  });

  it('update patches matching entity and emits flightType.updated', () => {
    const renamed: FlightTypeDetail = { ...sampleDetail, flightTypeName: 'Renamed' };
    let listState: FlightTypeListItem[] = [sampleItem];
    const bus = configure(
      apiStub({
        list: () => of(listState),
        update: () => {
          listState = [{ ...sampleItem, flightTypeName: 'Renamed' }];
          return of(renamed);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(FlightTypesStore);
    store.update({ id: sampleDetail.id!, req: { ...baseUpdateReq, flightTypeName: 'Renamed' } });
    expect(store.entities()[0]?.flightTypeName).toBe('Renamed');
    expect(events).toEqual([{ kind: 'flightType.updated', id: sampleDetail.id }]);
  });

  it('delete removes entity and emits flightType.deleted', () => {
    const bus = configure(
      apiStub({
        list: () => of([sampleItem]),
        remove: () => of(undefined as unknown as void),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(FlightTypesStore);
    store.delete(sampleItem.id!);
    expect(store.entities()).toEqual([]);
    expect(events).toEqual([{ kind: 'flightType.deleted', id: sampleItem.id }]);
  });

  it('clears entities on session.logout / session.tenantSwitch', () => {
    const bus = configure(apiStub({ list: () => of([sampleItem]) }));
    const store = TestBed.inject(FlightTypesStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities()).toEqual([]);
  });
});
