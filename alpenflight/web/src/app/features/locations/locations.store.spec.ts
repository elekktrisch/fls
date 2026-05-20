import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { LocationsService } from '@api/generated/locations/locations.service';
import type {
  LocationCreateRequest,
  LocationDetail,
  LocationListItem,
  LocationUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { LocationsStore } from './locations.store';

const sampleListItem: LocationListItem = {
  id: 'loc-019e30c3-2c00-7001-8000-000000000001',
  locationName: 'Birrfeld',
  locationShortName: 'BIR',
  icaoCode: 'LSZF',
  locationTypeCode: 'AIRPORT',
  isAirfield: true,
  isFastEntryRecord: false,
};

const sampleDetail: LocationDetail = {
  id: sampleListItem.id!,
  locationName: 'Birrfeld',
  locationShortName: 'BIR',
  countryId: '019e2e15-2c00-74be-8000-0000000004be',
  locationTypeId: '019e2e15-2c00-7110-8000-000000001110',
  icaoCode: 'LSZF',
  latitude: '47.4435',
  longitude: '8.2336',
  description: 'Seed airport',
  isInboundRouteRequired: false,
  isOutboundRouteRequired: false,
  isFastEntryRecord: false,
  inOutboundPoints: [],
};

interface ApiStubs {
  list: () => Observable<LocationListItem[]>;
  get: (id: string) => Observable<LocationDetail>;
  create: (req: LocationCreateRequest) => Observable<LocationDetail>;
  update: (id: string, req: LocationUpdateRequest) => Observable<LocationDetail>;
  remove: (id: string) => Observable<void>;
}

function locationsServiceStub(stubs: Partial<ApiStubs>): LocationsService {
  const api = {
    listLocations: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([])))();
    }) as LocationsService['listLocations'],
    getLocation: ((id: string, options?: unknown) => {
      void options;
      return (stubs.get ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id);
    }) as LocationsService['getLocation'],
    createLocation: ((req: LocationCreateRequest, options?: unknown) => {
      void options;
      return (stubs.create ?? (() => of(sampleDetail)))(req);
    }) as LocationsService['createLocation'],
    updateLocation: ((id: string, req: LocationUpdateRequest, options?: unknown) => {
      void options;
      return (stubs.update ?? ((iid: string) => of({ ...sampleDetail, id: iid })))(id, req);
    }) as LocationsService['updateLocation'],
    deleteLocation: ((id: string, options?: unknown) => {
      void options;
      return (stubs.remove ?? (() => of(undefined as unknown as void)))(id);
    }) as LocationsService['deleteLocation'],
  };
  return api as unknown as LocationsService;
}

function configure(api: LocationsService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: LocationsService, useValue: api },
    ],
  });
  return bus;
}

const baseCreateReq: LocationCreateRequest = {
  locationName: sampleDetail.locationName,
  countryId: sampleDetail.countryId,
  locationTypeId: sampleDetail.locationTypeId,
  isInboundRouteRequired: false,
  isOutboundRouteRequired: false,
  isFastEntryRecord: false,
};

const baseUpdateReq: LocationUpdateRequest = baseCreateReq;

describe('LocationsStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty and loads on construct', () => {
    configure(locationsServiceStub({ list: () => of([sampleListItem]) }));
    const store = TestBed.inject(LocationsStore);
    expect(store.entities()).toEqual([sampleListItem]);
    expect(store.isLoading()).toBe(false);
    expect(store.isEmpty()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('loadAll surfaces an error message on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(locationsServiceStub({ list: () => throwError(() => err) }));
    const store = TestBed.inject(LocationsStore);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).not.toBeNull();
    expect(store.hasError()).toBe(true);
  });

  it('loadOne populates selectedLocation', () => {
    configure(
      locationsServiceStub({
        list: () => of([sampleListItem]),
        get: () => of(sampleDetail),
      }),
    );
    const store = TestBed.inject(LocationsStore);
    store.loadOne(sampleDetail.id!);
    expect(store.selectedLocation()?.id).toBe(sampleDetail.id);
    expect(store.selectedLocation()?.locationName).toBe('Birrfeld');
  });

  it('create adds a list entity, sets selectedLocation, and emits location.created', () => {
    const created: LocationDetail = {
      ...sampleDetail,
      id: 'loc-019e30c3-2c00-7001-8000-000000000002',
      locationName: 'Schaenis',
      icaoCode: 'LSZX',
    };
    const newListItem: LocationListItem = {
      id: created.id!,
      locationName: 'Schaenis',
      icaoCode: 'LSZX',
      locationTypeCode: 'AIRPORT',
      isAirfield: true,
      isFastEntryRecord: false,
    };
    // Server reflects the new row on the next listLocations() so the
    // post-mutation refresh resolves with both seed + created. Without this
    // the post-mutation `loadAll` resets entities back to the seed only.
    let listState: LocationListItem[] = [sampleListItem];
    const bus = configure(
      locationsServiceStub({
        list: () => of(listState),
        create: () => {
          listState = [sampleListItem, newListItem];
          return of(created);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(LocationsStore);
    store.create({ ...baseCreateReq, locationName: 'Schaenis', icaoCode: 'LSZX' });

    expect(store.entities().some((l) => l.id === created.id)).toBe(true);
    expect(store.selectedLocation()?.id).toBe(created.id);
    expect(events).toEqual([{ kind: 'location.created', id: created.id }]);
  });

  it('create surfaces 409 with the ICAO code in the saveError + icao-duplicate kind', () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    configure(
      locationsServiceStub({
        list: () => of([sampleListItem]),
        create: () => throwError(() => err),
      }),
    );
    const store = TestBed.inject(LocationsStore);
    store.create({ ...baseCreateReq, icaoCode: 'LSZF' });
    expect(store.saveError()).toContain('LSZF');
    expect(store.saveError()).toContain('already in use');
    expect(store.saveErrorKind()).toBe('icao-duplicate');
  });

  it('update patches the matching entity and emits location.updated', () => {
    const renamed: LocationDetail = { ...sampleDetail, locationName: 'Renamed' };
    const renamedListItem: LocationListItem = { ...sampleListItem, locationName: 'Renamed' };
    let listState: LocationListItem[] = [sampleListItem];
    const bus = configure(
      locationsServiceStub({
        list: () => of(listState),
        update: () => {
          listState = [renamedListItem];
          return of(renamed);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(LocationsStore);
    store.update({ id: sampleDetail.id!, req: { ...baseUpdateReq, locationName: 'Renamed' } });

    expect(store.entities()[0]?.locationName).toBe('Renamed');
    expect(events).toEqual([{ kind: 'location.updated', id: sampleDetail.id }]);
  });

  it('delete removes the entity and emits location.deleted', () => {
    const bus = configure(
      locationsServiceStub({
        list: () => of([sampleListItem]),
        remove: () => of(undefined as unknown as void),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(LocationsStore);
    store.delete(sampleListItem.id!);
    expect(store.entities()).toEqual([]);
    expect(events).toEqual([{ kind: 'location.deleted', id: sampleListItem.id }]);
  });

  it('clears entities on session.logout via MUTATION_BUS', () => {
    const bus = configure(locationsServiceStub({ list: () => of([sampleListItem]) }));
    const store = TestBed.inject(LocationsStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities()).toEqual([]);
  });

  it('clears entities on session.tenantSwitch via MUTATION_BUS', () => {
    const bus = configure(locationsServiceStub({ list: () => of([sampleListItem]) }));
    const store = TestBed.inject(LocationsStore);
    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-2' });
    expect(store.entities()).toEqual([]);
  });

  it('select with null clears selectedLocation', () => {
    configure(locationsServiceStub({ list: () => of([sampleListItem]) }));
    const store = TestBed.inject(LocationsStore);
    store.loadOne(sampleDetail.id!);
    expect(store.selectedLocation()).not.toBeNull();
    store.select(null);
    expect(store.selectedLocation()).toBeNull();
  });
});
