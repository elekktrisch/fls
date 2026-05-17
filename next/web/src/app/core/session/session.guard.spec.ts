import {
  EnvironmentInjector,
  provideZonelessChangeDetection,
  runInInjectionContext,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  provideRouter,
  Router,
  UrlTree,
  type ActivatedRouteSnapshot,
  type RouterStateSnapshot,
} from '@angular/router';
import { patchState } from '@ngrx/signals';
import { Subject } from 'rxjs';

import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { authGuard } from './session.guard';
import { SessionStore, type User } from './session.store';

const sampleUser: User = {
  id: 'u-1',
  username: 'alice',
  email: 'alice@example.test',
  firstName: 'Alice',
  lastName: 'Doe',
  clubId: 'club-1',
  roles: ['MEMBER'],
};

function runGuard(data: Record<string, unknown> = {}, url = '/protected') {
  const route = { data } as unknown as ActivatedRouteSnapshot;
  const state = { url } as RouterStateSnapshot;
  return runInInjectionContext(TestBed.inject(EnvironmentInjector), () =>
    authGuard(route, state),
  );
}

describe('authGuard', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
      ],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('returns true for routes flagged publicAccess regardless of session status', () => {
    const store = TestBed.inject(SessionStore);
    patchState(store, { sessionStatus: 'unauthenticated' });

    expect(runGuard({ publicAccess: true })).toBe(true);
  });

  it('returns false (defer) when sessionStatus is idle', () => {
    expect(runGuard({})).toBe(false);
  });

  it('returns false (defer) when sessionStatus is loading', () => {
    const store = TestBed.inject(SessionStore);
    patchState(store, { sessionStatus: 'loading' });

    expect(runGuard({})).toBe(false);
  });

  it('returns true when authenticated and route is private', () => {
    const store = TestBed.inject(SessionStore);
    store.login(sampleUser, 'club-1');

    expect(runGuard({})).toBe(true);
  });

  it('redirects to /login when unauthenticated and route is private', () => {
    const store = TestBed.inject(SessionStore);
    patchState(store, { sessionStatus: 'unauthenticated' });

    const result = runGuard({});

    expect(result).toBeInstanceOf(UrlTree);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/login');
  });
});
