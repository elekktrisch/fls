import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import { FlightsService } from '@api/generated/flights/flights.service';
import type { FlightListItem, FlightListResponse, ListParams } from '@api/generated/model';
import {
  FlightListItemAirState,
  FlightListItemFlightAircraftType,
  FlightListItemProcessState,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { FlightStore } from './flight.store';

const FLIGHT_A: FlightListItem = {
  id: 'fl-019e30c3-2c00-7001-8000-000000000001',
  flightAircraftType: FlightListItemFlightAircraftType.GLIDER,
  flightDate: '2026-05-20',
  startDateTime: '2026-05-20T10:00:00Z',
  ldgDateTime: '2026-05-20T11:00:00Z',
  aircraftId: 'ac-019e30c3-2c00-7001-8000-000000000a01',
  processStateId: '019e2e15-2c00-7100-8000-000000007002',
  processState: FlightListItemProcessState.VALID,
  airState: FlightListItemAirState.LANDED,
};

const FLIGHT_B: FlightListItem = {
  id: 'fl-019e30c3-2c00-7001-8000-000000000002',
  flightAircraftType: FlightListItemFlightAircraftType.TOW,
  flightDate: '2026-05-20',
  startDateTime: '2026-05-20T10:00:00Z',
  ldgDateTime: '2026-05-20T10:08:00Z',
  aircraftId: 'ac-019e30c3-2c00-7001-8000-000000000a02',
  processStateId: '019e2e15-2c00-7100-8000-000000007002',
  processState: FlightListItemProcessState.VALID,
  airState: FlightListItemAirState.STARTED,
};

type StubbedApi = Pick<FlightsService, 'list'>;

interface ApiStubs {
  list: (params?: ListParams) => Observable<FlightListResponse>;
}

function flightsServiceStub(stubs: Partial<ApiStubs> = {}): FlightsService {
  const api: StubbedApi = {
    list: ((params?: ListParams, options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of<FlightListResponse>({ items: [] })))(params);
    }) as FlightsService['list'],
  };
  return api as unknown as FlightsService;
}

function configure(api: FlightsService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: FlightsService, useValue: api },
    ],
  });
  return bus;
}

describe('FlightStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty and loads on construct', () => {
    configure(flightsServiceStub({ list: () => of({ items: [FLIGHT_A, FLIGHT_B] }) }));
    const store = TestBed.inject(FlightStore);

    expect(store.entities()).toEqual([FLIGHT_A, FLIGHT_B]);
    expect(store.isLoading()).toBe(false);
    expect(store.isEmpty()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('loadAll sets loadError on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(flightsServiceStub({ list: () => throwError(() => err) }));
    const store = TestBed.inject(FlightStore);

    expect(store.loadError()).not.toBeNull();
    expect(store.isLoading()).toBe(false);
  });

  it('setDateRange forwards from/to to the server and resets entities', () => {
    let lastParams: ListParams | undefined;
    configure(
      flightsServiceStub({
        list: (params) => {
          lastParams = params;
          return of({ items: [FLIGHT_A] });
        },
      }),
    );
    const store = TestBed.inject(FlightStore);
    store.setDateRange({ from: '2026-05-01', to: '2026-05-31' });

    expect(lastParams).toEqual({ from: '2026-05-01', to: '2026-05-31', limit: 50 });
    expect(store.dateFrom()).toBe('2026-05-01');
    expect(store.dateTo()).toBe('2026-05-31');
    expect(store.entities()).toEqual([FLIGHT_A]);
  });

  it('clientFilter narrows visibleEntities without re-querying the server', () => {
    let calls = 0;
    configure(
      flightsServiceStub({
        list: () => {
          calls++;
          return of({ items: [FLIGHT_A, FLIGHT_B] });
        },
      }),
    );
    const store = TestBed.inject(FlightStore);
    expect(calls).toBe(1);
    expect(store.visibleEntities()).toHaveLength(2);

    store.setClientFilter({ airStates: [FlightListItemAirState.LANDED] });

    expect(calls).toBe(1);
    expect(store.visibleEntities()).toEqual([FLIGHT_A]);
  });

  it('clientFilter narrows by aircraft type', () => {
    configure(flightsServiceStub({ list: () => of({ items: [FLIGHT_A, FLIGHT_B] }) }));
    const store = TestBed.inject(FlightStore);

    store.setClientFilter({ aircraftTypes: [FlightListItemFlightAircraftType.TOW] });

    expect(store.visibleEntities()).toEqual([FLIGHT_B]);
  });

  it('refresh re-fetches the current page without clearing entities first', () => {
    let calls = 0;
    configure(
      flightsServiceStub({
        list: () => {
          calls++;
          return of({ items: calls === 1 ? [FLIGHT_A] : [FLIGHT_A, FLIGHT_B] });
        },
      }),
    );
    const store = TestBed.inject(FlightStore);
    expect(store.entities()).toEqual([FLIGHT_A]);

    store.refresh();

    expect(calls).toBe(2);
    expect(store.entities()).toEqual([FLIGHT_A, FLIGHT_B]);
  });

  it('reloads on flight.booked mutation', () => {
    let calls = 0;
    const bus = configure(
      flightsServiceStub({
        list: () => {
          calls++;
          return of({ items: [FLIGHT_A] });
        },
      }),
    );
    TestBed.inject(FlightStore);
    expect(calls).toBe(1);

    bus.next({ kind: 'flight.booked', flightId: FLIGHT_A.id });

    expect(calls).toBe(2);
  });

  it('wipes entities synchronously and reloads on session.tenantSwitch', () => {
    let calls = 0;
    const seen: number[] = [];
    const ref: { store: InstanceType<typeof FlightStore> | null } = { store: null };
    const bus = configure(
      flightsServiceStub({
        list: () => {
          calls++;
          seen.push(ref.store ? ref.store.entities().length : -1);
          return of({ items: [FLIGHT_B] });
        },
      }),
    );
    ref.store = TestBed.inject(FlightStore);
    expect(ref.store.entities()).toEqual([FLIGHT_B]);

    bus.next({ kind: 'session.tenantSwitch', clubId: 'clb-019e30c3-2c00-7001-8000-000000000999' });

    expect(calls).toBe(2);
    expect(seen[1]).toBe(0);
  });

  it('clears entities on session.logout (no reload)', () => {
    let calls = 0;
    const bus = configure(
      flightsServiceStub({
        list: () => {
          calls++;
          return of({ items: [FLIGHT_A] });
        },
      }),
    );
    const store = TestBed.inject(FlightStore);
    expect(store.entities()).toHaveLength(1);

    bus.next({ kind: 'session.logout' });

    expect(store.entities()).toEqual([]);
    expect(calls).toBe(1);
  });
});
