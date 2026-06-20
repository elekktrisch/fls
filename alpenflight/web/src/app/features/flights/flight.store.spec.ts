import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import { FlightsService } from '@api/generated/flights/flights.service';
import type {
  FlightDetail,
  FlightListItem,
  FlightListResponse,
  ListParams,
} from '@api/generated/model';
import {
  FlightDetailAirState,
  FlightDetailFlightAircraftType,
  FlightListItemAirState,
  FlightListItemFlightAircraftType,
  FlightListItemProcessState,
} from '@api/generated/model';

import { isoDateFromLocal } from '@shared/util/date';

import type { FlightFormSnapshot } from './edit/flight-form.model';

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
  version: 1,
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
  version: 1,
};

interface ApiStubs {
  list: (params?: ListParams) => Observable<FlightListResponse>;
  update: (id: string) => Observable<FlightDetail>;
  get: (id: string) => Observable<FlightDetail>;
  create: (body: unknown) => Observable<FlightDetail>;
}

function flightsServiceStub(stubs: Partial<ApiStubs> = {}): FlightsService {
  const api: Pick<FlightsService, 'list' | 'update' | 'get' | 'create'> = {
    list: ((params?: ListParams, options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of<FlightListResponse>({ items: [] })))(params);
    }) as FlightsService['list'],
    update: ((id: string) =>
      (stubs.update ?? (() => throwError(() => new Error('update not stubbed'))))(
        id,
      )) as unknown as FlightsService['update'],
    get: ((id: string) =>
      (stubs.get ?? (() => throwError(() => new Error('get not stubbed'))))(
        id,
      )) as unknown as FlightsService['get'],
    create: ((body: unknown) =>
      (stubs.create ?? (() => throwError(() => new Error('create not stubbed'))))(
        body,
      )) as unknown as FlightsService['create'],
  };
  return api as unknown as FlightsService;
}

const SERVER_DETAIL: FlightDetail = {
  id: 'fl-019e30c3-2c00-7001-8000-000000000001',
  flightAircraftType: FlightDetailFlightAircraftType.GLIDER,
  aircraftId: 'ac-019e30c3-2c00-7001-8000-0000000000a9',
  flightDate: '2026-05-20',
  nrOfLdgs: 5,
  isSoloFlight: false,
  noStartTimeInformation: false,
  noLdgTimeInformation: false,
  airState: FlightDetailAirState.LANDED,
  processStateId: '019e2e15-2c00-7100-8000-000000007002',
  version: 9,
  crew: [],
};

function editSnapshot(): FlightFormSnapshot {
  return {
    flightId: SERVER_DETAIL.id,
    flightDate: '2026-05-20',
    startTypeId: null,
    canUpdateRecord: true,
    canDeleteRecord: true,
    glider: {
      aircraftId: 'ac-019e30c3-2c00-7001-8000-0000000000b1',
      flightTypeId: null,
      pilotPersonId: null,
      coPilotPersonId: null,
      instructorPersonId: null,
      observerPersonId: null,
      passengerPersonId: null,
      winchOperatorPersonId: null,
      startLocationId: null,
      ldgLocationId: null,
      outboundRoute: null,
      inboundRoute: null,
      startTime: null,
      ldgTime: null,
      duration: null,
      noStartTimeInformation: false,
      noLdgTimeInformation: false,
      nrOfLdgs: 2,
      engineStartOperatingCounterInSeconds: null,
      engineEndOperatingCounterInSeconds: null,
      flightCostBalanceTypeId: null,
      invoiceRecipientPersonId: null,
      couponNumber: null,
      flightComment: null,
      isSoloFlight: false,
    },
    tow: {
      aircraftId: null,
      flightTypeId: null,
      pilotPersonId: null,
      coPilotPersonId: null,
      instructorPersonId: null,
      observerPersonId: null,
      passengerPersonId: null,
      winchOperatorPersonId: null,
      startLocationId: null,
      ldgLocationId: null,
      outboundRoute: null,
      inboundRoute: null,
      startTime: null,
      ldgTime: null,
      duration: null,
      noStartTimeInformation: false,
      noLdgTimeInformation: false,
      nrOfLdgs: null,
      engineStartOperatingCounterInSeconds: null,
      engineEndOperatingCounterInSeconds: null,
      flightCostBalanceTypeId: null,
      invoiceRecipientPersonId: null,
      couponNumber: null,
      flightComment: null,
      isSoloFlight: false,
    },
  };
}

function createSnapshot(flightDate: string): FlightFormSnapshot {
  const base = editSnapshot();
  return { ...base, flightId: null, flightDate, startTypeId: null };
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

  it('defaults the list range to today..today (legacy parity)', () => {
    let lastParams: ListParams | undefined;
    configure(
      flightsServiceStub({
        list: (params) => {
          lastParams = params;
          return of({ items: [] });
        },
      }),
    );
    const store = TestBed.inject(FlightStore);
    const today = isoDateFromLocal(new Date());

    expect(store.dateFrom()).toBe(today);
    expect(store.dateTo()).toBe(today);
    expect(lastParams).toEqual({ from: today, to: today, limit: 50 });
  });

  it('surfaces an off-range post-save jump when a created flight is dated outside the active range', async () => {
    const today = isoDateFromLocal(new Date());
    const nextWeek = isoDateFromLocal(new Date(Date.now() + 7 * 86_400_000));
    configure(
      flightsServiceStub({
        list: () => of({ items: [] }),
        create: () => of({ ...SERVER_DETAIL, id: 'fl-new', version: 1 }),
      }),
    );
    const store = TestBed.inject(FlightStore);

    await store.savePair(createSnapshot(nextWeek));

    expect(store.hasOffRangeSaved()).toBe(true);
    expect(store.offRangeSaved()).toEqual({ id: 'fl-new', date: nextWeek });
    // The active range stays today-only until the user takes the action.
    expect(store.dateFrom()).toBe(today);
  });

  it('does not surface the jump when a created flight falls within the active range', async () => {
    const today = isoDateFromLocal(new Date());
    configure(
      flightsServiceStub({
        list: () => of({ items: [] }),
        create: () => of({ ...SERVER_DETAIL, id: 'fl-new', version: 1 }),
      }),
    );
    const store = TestBed.inject(FlightStore);

    await store.savePair(createSnapshot(today));

    expect(store.hasOffRangeSaved()).toBe(false);
    expect(store.offRangeSaved()).toBeNull();
  });

  it('viewOffRangeSaved widens the range to include the saved date and clears the jump', async () => {
    const nextWeek = isoDateFromLocal(new Date(Date.now() + 7 * 86_400_000));
    let lastParams: ListParams | undefined;
    configure(
      flightsServiceStub({
        list: (params) => {
          lastParams = params;
          return of({ items: [] });
        },
        create: () => of({ ...SERVER_DETAIL, id: 'fl-new', version: 1 }),
      }),
    );
    const store = TestBed.inject(FlightStore);
    await store.savePair(createSnapshot(nextWeek));
    expect(store.hasOffRangeSaved()).toBe(true);

    store.viewOffRangeSaved();

    expect(lastParams).toEqual({ from: nextWeek, to: nextWeek, limit: 50 });
    expect(store.dateTo()).toBe(nextWeek);
    expect(store.hasOffRangeSaved()).toBe(false);
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

  it('clearing the range (from/to null) drops the date params and refetches unfiltered', () => {
    // T-13: clearing the flights-list range picker restores the unfiltered list.
    // `paramsOf` omits from/to when null, so the refetch carries only `limit`.
    const calls: (ListParams | undefined)[] = [];
    configure(
      flightsServiceStub({
        list: (params) => {
          calls.push(params);
          return of({ items: [FLIGHT_A] });
        },
      }),
    );
    const store = TestBed.inject(FlightStore);
    store.setDateRange({ from: '2026-05-01', to: '2026-05-31' });
    store.setDateRange({ from: null, to: null });

    // Last query carries no date filter — only the page limit.
    expect(calls.at(-1)).toEqual({ limit: 50 });
    expect(calls.at(-1)).not.toHaveProperty('from');
    expect(calls.at(-1)).not.toHaveProperty('to');
    expect(store.dateFrom()).toBeNull();
    expect(store.dateTo()).toBeNull();
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

  it('412 stale If-Match opens the inline conflict diff (re-GET, no auto-retry)', async () => {
    let updateCalls = 0;
    const err = new HttpErrorResponse({
      status: 412,
      statusText: 'Precondition Failed',
      error: { serverVersion: 9 },
    });
    configure(
      flightsServiceStub({
        list: () => of({ items: [] }),
        update: () => {
          updateCalls++;
          return throwError(() => err);
        },
        // 412 body has no field values → the store re-GETs the server detail.
        get: () => of(SERVER_DETAIL),
      }),
    );
    const store = TestBed.inject(FlightStore);

    await expect(store.updatePair(editSnapshot(), { glider: 1, tow: null })).rejects.toBeTruthy();

    // DATA conflict → inline diff state set, reload toast NOT set.
    expect(store.hasSaveConflict()).toBe(true);
    expect(store.hasReloadConflict()).toBe(false);
    const conflict = store.saveConflict();
    expect(conflict?.serverVersion).toBe(9);
    // aircraftId differs (mine b1 vs theirs a9) → a per-field diff row.
    expect(conflict?.fields.some((f) => f.name === 'aircraftId')).toBe(true);
    // NO auto-retry: exactly one PUT was attempted.
    expect(updateCalls).toBe(1);
  });

  it('409 state-gate reject shows the reload toast, never the inline diff', async () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    configure(
      flightsServiceStub({
        list: () => of({ items: [] }),
        update: () => throwError(() => err),
        get: () => of(SERVER_DETAIL),
      }),
    );
    const store = TestBed.inject(FlightStore);

    await expect(store.updatePair(editSnapshot(), { glider: 1, tow: null })).rejects.toBeTruthy();

    // POLICY/STATE conflict → reload toast, NOT the inline diff dialog.
    expect(store.hasReloadConflict()).toBe(true);
    expect(store.hasSaveConflict()).toBe(false);
    expect(store.saveConflict()).toBeNull();
  });

  it('dismissConflict clears both the diff and the reload toast', async () => {
    const err = new HttpErrorResponse({
      status: 412,
      statusText: 'Precondition Failed',
      error: { serverVersion: 9 },
    });
    configure(
      flightsServiceStub({
        list: () => of({ items: [] }),
        update: () => throwError(() => err),
        get: () => of(SERVER_DETAIL),
      }),
    );
    const store = TestBed.inject(FlightStore);
    await expect(store.updatePair(editSnapshot(), { glider: 1, tow: null })).rejects.toBeTruthy();
    expect(store.hasSaveConflict()).toBe(true);

    store.dismissConflict();

    expect(store.hasSaveConflict()).toBe(false);
    expect(store.hasReloadConflict()).toBe(false);
  });
});
