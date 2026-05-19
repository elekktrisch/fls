import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import { ClubsService } from '@api/generated/clubs/clubs.service';
import type { ClubCreateRequest, ClubResponse, ClubUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { ClubsStore } from './clubs.store';

const sampleClub: ClubResponse = {
  id: '019e30c3-2c00-7001-8000-000000000001',
  name: 'Seed Club',
  slug: 'seed-club-1',
  clubKey: 'SEED',
  publicRegistrationEnabled: false,
};

type StubbedApi = Pick<ClubsService, 'listClubs' | 'createClub' | 'updateClub' | 'deleteClub'>;

interface ApiStubs {
  list: () => Observable<ClubResponse[]>;
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

function configure(api: ClubsService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: ClubsService, useValue: api },
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
