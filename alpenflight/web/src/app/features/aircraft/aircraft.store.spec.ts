import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import type {
  AircraftCreateRequest,
  AircraftDetail,
  AircraftListItem,
  AircraftUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { AircraftStore } from './aircraft.store';

const SEED_TYPE_GLIDER = '019e2e15-2c00-7af9-8000-000000002af9';
const SEED_TYPE_MOTOR = '019e2e15-2c00-7afc-8000-000000002afc';

const gliderItem: AircraftListItem = {
  id: 'ac-019e30c3-2c00-7001-8000-000000000001',
  immatriculation: 'HB-ABC',
  aircraftTypeId: SEED_TYPE_GLIDER,
  aircraftTypeCode: 'GLIDER',
  hasEngine: false,
  isTowingAircraft: false,
};

const motorItem: AircraftListItem = {
  id: 'ac-019e30c3-2c00-7001-8000-000000000002',
  immatriculation: 'HB-XYZ',
  aircraftTypeId: SEED_TYPE_MOTOR,
  aircraftTypeCode: 'MOTOR_AIRCRAFT',
  hasEngine: true,
  isTowingAircraft: false,
};

const towingItem: AircraftListItem = {
  id: 'ac-019e30c3-2c00-7001-8000-000000000003',
  immatriculation: 'HB-TOW',
  aircraftTypeId: SEED_TYPE_MOTOR,
  aircraftTypeCode: 'MOTOR_AIRCRAFT',
  hasEngine: true,
  isTowingAircraft: true,
};

const sampleDetail: AircraftDetail = {
  id: gliderItem.id!,
  aircraftTypeId: SEED_TYPE_GLIDER,
  immatriculation: 'HB-ABC',
  manufacturerName: 'Schleicher',
  aircraftModel: 'ASK-21',
  isTowingOrWinchRequired: true,
  isTowingStartAllowed: true,
  isWinchStartAllowed: true,
  isTowingAircraft: false,
  isFastEntryRecord: false,
};

interface ApiStubs {
  list: () => Observable<AircraftListItem[]>;
  get: (id: string) => Observable<AircraftDetail>;
  create: (req: AircraftCreateRequest) => Observable<AircraftDetail>;
  update: (id: string, req: AircraftUpdateRequest) => Observable<AircraftDetail>;
  remove: (id: string) => Observable<void>;
}

function aircraftServiceStub(stubs: Partial<ApiStubs>): AircraftService {
  const api = {
    listAircraft: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([])))();
    }) as AircraftService['listAircraft'],
    getAircraft: ((id: string, options?: unknown) => {
      void options;
      return (stubs.get ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id);
    }) as AircraftService['getAircraft'],
    registerAircraft: ((req: AircraftCreateRequest, options?: unknown) => {
      void options;
      return (stubs.create ?? (() => of(sampleDetail)))(req);
    }) as AircraftService['registerAircraft'],
    updateAircraft: ((id: string, req: AircraftUpdateRequest, options?: unknown) => {
      void options;
      return (stubs.update ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id, req);
    }) as AircraftService['updateAircraft'],
    deleteAircraft: ((id: string, options?: unknown) => {
      void options;
      return (stubs.remove ?? (() => of(undefined as unknown as void)))(id);
    }) as AircraftService['deleteAircraft'],
  };
  return api as unknown as AircraftService;
}

function configure(api: AircraftService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: AircraftService, useValue: api },
    ],
  });
  return bus;
}

const baseCreateReq: AircraftCreateRequest = {
  aircraftTypeId: SEED_TYPE_GLIDER,
  immatriculation: 'HB-NEW',
};

const baseUpdateReq: AircraftUpdateRequest = {
  aircraftTypeId: SEED_TYPE_GLIDER,
  immatriculation: 'HB-ABC',
};

describe('AircraftStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty and loads on construct', () => {
    configure(aircraftServiceStub({ list: () => of([gliderItem]) }));
    const store = TestBed.inject(AircraftStore);
    expect(store.entities()).toEqual([gliderItem]);
    expect(store.isLoading()).toBe(false);
    expect(store.isEmpty()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('loadAll surfaces an error message on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(aircraftServiceStub({ list: () => throwError(() => err) }));
    const store = TestBed.inject(AircraftStore);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).not.toBeNull();
    expect(store.hasError()).toBe(true);
  });

  it('loadOne populates selectedAircraft', () => {
    configure(
      aircraftServiceStub({
        list: () => of([gliderItem]),
        get: () => of(sampleDetail),
      }),
    );
    const store = TestBed.inject(AircraftStore);
    store.loadOne(sampleDetail.id!);
    expect(store.selectedAircraft()?.id).toBe(sampleDetail.id);
    expect(store.selectedAircraft()?.immatriculation).toBe('HB-ABC');
  });

  it('typeFilter ALL returns every entity', () => {
    configure(aircraftServiceStub({ list: () => of([gliderItem, motorItem, towingItem]) }));
    const store = TestBed.inject(AircraftStore);
    expect(store.filteredAircraft().length).toBe(3);
  });

  it('typeFilter GLIDER slices to hasEngine=false rows', () => {
    configure(aircraftServiceStub({ list: () => of([gliderItem, motorItem, towingItem]) }));
    const store = TestBed.inject(AircraftStore);
    store.setTypeFilter('GLIDER');
    expect(store.filteredAircraft().map((a) => a.id)).toEqual([gliderItem.id]);
  });

  it('typeFilter MOTOR slices to hasEngine=true rows', () => {
    configure(aircraftServiceStub({ list: () => of([gliderItem, motorItem, towingItem]) }));
    const store = TestBed.inject(AircraftStore);
    store.setTypeFilter('MOTOR');
    expect(
      store
        .filteredAircraft()
        .map((a) => a.id)
        .sort(),
    ).toEqual([motorItem.id, towingItem.id].sort());
  });

  it('typeFilter TOWING slices to isTowingAircraft=true rows', () => {
    configure(aircraftServiceStub({ list: () => of([gliderItem, motorItem, towingItem]) }));
    const store = TestBed.inject(AircraftStore);
    store.setTypeFilter('TOWING');
    expect(store.filteredAircraft().map((a) => a.id)).toEqual([towingItem.id]);
  });

  it('create adds a list entity, sets selectedAircraft, and emits aircraft.created', () => {
    const created: AircraftDetail = {
      ...sampleDetail,
      id: 'ac-019e30c3-2c00-7001-8000-00000000000a',
      immatriculation: 'HB-NEW',
    };
    let listState: AircraftListItem[] = [gliderItem];
    const bus = configure(
      aircraftServiceStub({
        list: () => of(listState),
        create: () => {
          listState = [gliderItem, { ...gliderItem, id: created.id!, immatriculation: 'HB-NEW' }];
          return of(created);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(AircraftStore);
    store.create({ ...baseCreateReq, immatriculation: 'HB-NEW' });

    expect(store.entities().some((a) => a.id === created.id)).toBe(true);
    expect(store.selectedAircraft()?.id).toBe(created.id);
    expect(events).toEqual([{ kind: 'aircraft.created', aircraftId: created.id }]);
  });

  it('create surfaces 409 with the immatriculation in the saveError + immatriculation-duplicate kind', () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    configure(
      aircraftServiceStub({
        list: () => of([gliderItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(AircraftStore);
    store.create({ ...baseCreateReq, immatriculation: 'HB-DUP' });
    expect(store.saveError()).toContain('HB-DUP');
    expect(store.saveError()).toContain('already in use');
    expect(store.saveErrorKind()).toBe('immatriculation-duplicate');
  });

  it('create surfaces 403 as the forbidden kind', () => {
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    configure(
      aircraftServiceStub({
        list: () => of([gliderItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(AircraftStore);
    store.create({ ...baseCreateReq });
    expect(store.saveErrorKind()).toBe('forbidden');
  });

  it('update patches the matching entity and emits aircraft.updated', () => {
    const renamed: AircraftDetail = { ...sampleDetail, immatriculation: 'HB-REN' };
    const renamedListItem: AircraftListItem = { ...gliderItem, immatriculation: 'HB-REN' };
    let listState: AircraftListItem[] = [gliderItem];
    const bus = configure(
      aircraftServiceStub({
        list: () => of(listState),
        update: () => {
          listState = [renamedListItem];
          return of(renamed);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(AircraftStore);
    store.update({ id: sampleDetail.id!, req: { ...baseUpdateReq, immatriculation: 'HB-REN' } });

    expect(store.entities()[0]?.immatriculation).toBe('HB-REN');
    expect(events).toEqual([{ kind: 'aircraft.updated', aircraftId: sampleDetail.id }]);
  });

  it('delete removes the entity and emits aircraft.deleted', () => {
    const bus = configure(
      aircraftServiceStub({
        list: () => of([gliderItem]),
        remove: () => of(undefined as unknown as void),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(AircraftStore);
    store.delete(gliderItem.id!);
    expect(store.entities()).toEqual([]);
    expect(events).toEqual([{ kind: 'aircraft.deleted', aircraftId: gliderItem.id }]);
  });

  it('clears entities on session.logout via MUTATION_BUS', () => {
    const bus = configure(aircraftServiceStub({ list: () => of([gliderItem]) }));
    const store = TestBed.inject(AircraftStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities()).toEqual([]);
  });

  it('clears entities on session.tenantSwitch via MUTATION_BUS', () => {
    const bus = configure(aircraftServiceStub({ list: () => of([gliderItem]) }));
    const store = TestBed.inject(AircraftStore);
    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-2' });
    expect(store.entities()).toEqual([]);
  });

  it('select with null clears selectedAircraft', () => {
    configure(aircraftServiceStub({ list: () => of([gliderItem]) }));
    const store = TestBed.inject(AircraftStore);
    store.loadOne(sampleDetail.id!);
    expect(store.selectedAircraft()).not.toBeNull();
    store.select(null);
    expect(store.selectedAircraft()).toBeNull();
  });
});
