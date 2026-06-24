import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { PendingJoinRequestResponse } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { JoinRequestsStore } from './join-requests.store';

const REQUEST_A = 'jr-019e30c3-2c00-7100-8000-000000000001';
const REQUEST_B = 'jr-019e30c3-2c00-7100-8000-000000000002';

const pendingA: PendingJoinRequestResponse = {
  id: REQUEST_A,
  clubId: '019e30c3-2c00-7001-8000-000000000001',
  email: 'fresh.pilot@example.test',
  friendlyName: 'Fresh Pilot',
  note: 'Keen to start gliding.',
  createdOn: '2026-06-23T10:00:00Z',
};

const pendingB: PendingJoinRequestResponse = {
  id: REQUEST_B,
  clubId: '019e30c3-2c00-7001-8000-000000000001',
  email: 'second.pilot@example.test',
  friendlyName: 'Second Pilot',
  createdOn: '2026-06-23T11:00:00Z',
};

function serviceStub(list: () => Observable<PendingJoinRequestResponse[]>): JoinRequestsService {
  const api = {
    listPending: ((params?: unknown, options?: unknown) => {
      void params;
      void options;
      return list();
    }) as JoinRequestsService['listPending'],
  };
  return api as unknown as JoinRequestsService;
}

function configure(api: JoinRequestsService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: JoinRequestsService, useValue: api },
    ],
  });
  return bus;
}

describe('JoinRequestsStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads pending requests on construct', () => {
    configure(serviceStub(() => of([pendingA, pendingB])));
    const store = TestBed.inject(JoinRequestsStore);
    expect(store.entities().map((r) => r.id)).toEqual([REQUEST_A, REQUEST_B]);
    expect(store.pendingCount()).toBe(2);
    expect(store.isEmpty()).toBe(false);
    expect(store.isLoading()).toBe(false);
  });

  it('reports empty when there are no pending requests', () => {
    configure(serviceStub(() => of([])));
    const store = TestBed.inject(JoinRequestsStore);
    expect(store.isEmpty()).toBe(true);
    expect(store.pendingCount()).toBe(0);
  });

  it('surfaces a load error on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(serviceStub(() => throwError(() => err)));
    const store = TestBed.inject(JoinRequestsStore);
    expect(store.loadError()).not.toBeNull();
    expect(store.isLoading()).toBe(false);
  });

  it('removeOne drops a row from the list', () => {
    configure(serviceStub(() => of([pendingA, pendingB])));
    const store = TestBed.inject(JoinRequestsStore);
    store.removeOne(REQUEST_A);
    expect(store.entities().map((r) => r.id)).toEqual([REQUEST_B]);
    expect(store.pendingCount()).toBe(1);
  });

  it('clears the list on logout', () => {
    const bus = configure(serviceStub(() => of([pendingA, pendingB])));
    const store = TestBed.inject(JoinRequestsStore);
    bus.next({ kind: 'session.logout' });
    expect(store.entities()).toEqual([]);
  });

  it('clears the list on tenant switch', () => {
    const bus = configure(serviceStub(() => of([pendingA, pendingB])));
    const store = TestBed.inject(JoinRequestsStore);
    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-2' });
    expect(store.entities()).toEqual([]);
  });

  it('surfaces a load error when a pending request is missing an id', () => {
    const malformed = { email: 'x@example.test' } as PendingJoinRequestResponse;
    configure(serviceStub(() => of([malformed])));
    const store = TestBed.inject(JoinRequestsStore);
    expect(store.loadError()).toMatch(/without id/);
    expect(store.entities()).toEqual([]);
  });
});
