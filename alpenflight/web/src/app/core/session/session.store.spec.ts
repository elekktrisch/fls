import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { patchState } from '@ngrx/signals';
import { unprotected } from '@ngrx/signals/testing';
import { Subject } from 'rxjs';

import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { SessionStore, type User } from './session.store';

const sampleUser: User = {
  id: 'u-1',
  username: 'alice',
  email: 'alice@example.test',
  firstName: 'Alice',
  lastName: 'Doe',
  clubId: 'club-1',
  roles: ['CLUB_ADMINISTRATOR'],
};

describe('SessionStore', () => {
  let bus: Subject<MutationEvent>;

  beforeEach(() => {
    bus = new Subject<MutationEvent>();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: MUTATION_BUS, useValue: bus }],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('initialises with sessionStatus idle and no user', () => {
    const store = TestBed.inject(SessionStore);

    expect(store.authenticatedUser()).toBeNull();
    expect(store.currentClubId()).toBeNull();
    expect(store.sessionStatus()).toBe('idle');
    expect(store.bootstrapStartedAt()).toBeNull();
    expect(store.isAuthenticated()).toBe(false);
    expect(store.isLoadingSession()).toBe(true);
    expect(store.isClubAdmin()).toBe(false);
    expect(store.isSystemAdmin()).toBe(false);
  });

  it('login() promotes status to authenticated and binds the user + club', () => {
    const store = TestBed.inject(SessionStore);

    store.login(sampleUser, 'club-1');

    expect(store.authenticatedUser()).toEqual(sampleUser);
    expect(store.currentClubId()).toBe('club-1');
    expect(store.sessionStatus()).toBe('authenticated');
    expect(store.isAuthenticated()).toBe(true);
    expect(store.isLoadingSession()).toBe(false);
    expect(store.isClubAdmin()).toBe(true);
    expect(store.isSystemAdmin()).toBe(false);
  });

  it('logout() resets state to unauthenticated and emits session.logout on the bus', () => {
    const store = TestBed.inject(SessionStore);
    store.login(sampleUser, 'club-1');

    const received: MutationEvent[] = [];
    bus.subscribe((e) => received.push(e));

    store.logout();

    expect(store.authenticatedUser()).toBeNull();
    expect(store.currentClubId()).toBeNull();
    expect(store.sessionStatus()).toBe('unauthenticated');
    expect(store.isAuthenticated()).toBe(false);
    expect(store.isLoadingSession()).toBe(false);
    expect(received).toEqual([{ kind: 'session.logout' }]);
  });

  it('isSystemAdmin is true when SYSTEM_ADMINISTRATOR is in roles', () => {
    const store = TestBed.inject(SessionStore);

    patchState(unprotected(store), {
      authenticatedUser: { ...sampleUser, roles: ['SYSTEM_ADMINISTRATOR'] },
      sessionStatus: 'authenticated',
    });

    expect(store.isSystemAdmin()).toBe(true);
    expect(store.isClubAdmin()).toBe(false);
  });

  it('bootstrapPrefetch() is a no-op until the user is authenticated', () => {
    const store = TestBed.inject(SessionStore);

    store.bootstrapPrefetch();

    expect(store.bootstrapStartedAt()).toBeNull();
  });

  it('bootstrapPrefetch() stamps bootstrapStartedAt once authenticated', () => {
    const store = TestBed.inject(SessionStore);
    store.login(sampleUser, 'club-1');

    const before = Date.now();
    store.bootstrapPrefetch();
    const after = Date.now();

    const stamp = store.bootstrapStartedAt();
    expect(stamp).not.toBeNull();
    expect(stamp!).toBeGreaterThanOrEqual(before);
    expect(stamp!).toBeLessThanOrEqual(after);
  });
});
