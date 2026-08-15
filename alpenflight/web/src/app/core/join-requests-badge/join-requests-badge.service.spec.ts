import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { patchState } from '@ngrx/signals';
import { unprotected } from '@ngrx/signals/testing';
import { Observable, Subject, of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { PendingJoinRequestResponse } from '@api/generated/model';

import { MeEventsService } from '../events';
import { type MeEvent } from '../events/me-events.core';
import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { SessionStore, type User } from '../session/session.store';

import { JoinRequestsBadgeService } from './join-requests-badge.service';
import { decrementClampedAtZero, isPendingSubmit } from './join-requests-badge.logic';

const ADMIN: User = {
  id: 'a1',
  username: 'admin1',
  email: 'admin1@example.com',
  firstName: 'Ada',
  lastName: 'Admin',
  clubId: 'club-1',
  personId: null,
  homebaseLocationId: null,
  roles: ['CLUB_ADMINISTRATOR'],
};

const PILOT: User = { ...ADMIN, roles: ['PILOT'] };

function pending(n: number): PendingJoinRequestResponse[] {
  return Array.from({ length: n }, (_, i) => ({ id: `r${i}` }) as PendingJoinRequestResponse);
}

function statusFrame(status: string): MeEvent {
  return { kind: 'join-request.status-changed', data: JSON.stringify({ status }), id: null };
}

function setup(seed: number) {
  const frames = new Subject<MeEvent>();
  const bus = new Subject<MutationEvent>();
  const meEvents: Pick<MeEventsService, 'on'> = {
    on: (kind: string): Observable<MeEvent> =>
      kind === 'join-request.status-changed' ? frames.asObservable() : new Subject<MeEvent>(),
  };
  const api = {
    listPending: (): Observable<PendingJoinRequestResponse[]> => of(pending(seed)),
  };
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: MeEventsService, useValue: meEvents },
      { provide: JoinRequestsService, useValue: api },
    ],
  });
  const store = TestBed.inject(SessionStore);
  return { frames, bus, store };
}

function asAdmin(store: InstanceType<typeof SessionStore>): void {
  patchState(unprotected(store), { authenticatedUser: ADMIN, sessionStatus: 'authenticated' });
  TestBed.tick();
}

afterEach(() => TestBed.resetTestingModule());

describe('join-requests-badge.logic', () => {
  it('isPendingSubmit: only a PENDING frame grows the queue', () => {
    expect(isPendingSubmit(JSON.stringify({ status: 'PENDING' }))).toBe(true);
    expect(isPendingSubmit(JSON.stringify({ status: 'APPROVED' }))).toBe(false);
    expect(isPendingSubmit(JSON.stringify({ status: 'DENIED' }))).toBe(false);
    expect(isPendingSubmit('not-json')).toBe(false);
    expect(isPendingSubmit(JSON.stringify({}))).toBe(false);
  });

  it('decrementClampedAtZero clamps at zero', () => {
    expect(decrementClampedAtZero(2)).toBe(1);
    expect(decrementClampedAtZero(1)).toBe(0);
    expect(decrementClampedAtZero(0)).toBe(0);
  });
});

describe('JoinRequestsBadgeService', () => {
  it('stays at zero for a non-admin (never seeds — listPending is admin-only)', () => {
    const { store } = setup(3);
    const badge = TestBed.inject(JoinRequestsBadgeService);
    patchState(unprotected(store), { authenticatedUser: PILOT, sessionStatus: 'authenticated' });
    TestBed.tick();
    expect(badge.count()).toBe(0);
  });

  it('seeds the count from listPending once the principal is a club admin', () => {
    const { store } = setup(2);
    const badge = TestBed.inject(JoinRequestsBadgeService);
    asAdmin(store);
    expect(badge.count()).toBe(2);
  });

  it('increments on a PENDING status-changed SSE frame; ignores terminal frames', () => {
    const { frames, store } = setup(1);
    const badge = TestBed.inject(JoinRequestsBadgeService);
    asAdmin(store);
    expect(badge.count()).toBe(1);

    frames.next(statusFrame('PENDING'));
    TestBed.tick();
    expect(badge.count()).toBe(2);

    frames.next(statusFrame('APPROVED'));
    TestBed.tick();
    expect(badge.count()).toBe(2);
  });

  it('decrements on a join-request.decided bus event (admin approve / deny)', () => {
    const { bus, store } = setup(2);
    const badge = TestBed.inject(JoinRequestsBadgeService);
    asAdmin(store);
    expect(badge.count()).toBe(2);

    bus.next({ kind: 'join-request.decided', id: 'r0' });
    TestBed.tick();
    expect(badge.count()).toBe(1);

    bus.next({ kind: 'join-request.decided', id: 'r1' });
    TestBed.tick();
    expect(badge.count()).toBe(0);
  });

  it('resets to zero on logout', () => {
    const { bus, store } = setup(3);
    const badge = TestBed.inject(JoinRequestsBadgeService);
    asAdmin(store);
    expect(badge.count()).toBe(3);

    bus.next({ kind: 'session.logout' });
    TestBed.tick();
    expect(badge.count()).toBe(0);
  });
});
