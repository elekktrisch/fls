import {
  EnvironmentInjector,
  provideZonelessChangeDetection,
  runInInjectionContext,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  provideRouter,
  type ActivatedRouteSnapshot,
  type RouterStateSnapshot,
} from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { patchState } from '@ngrx/signals';
import { unprotected } from '@ngrx/signals/testing';
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
  roles: ['PILOT'],
};

function runGuard(data: Record<string, unknown> = {}, url = '/protected') {
  const route = { data } as unknown as ActivatedRouteSnapshot;
  const state = { url } as RouterStateSnapshot;
  return runInInjectionContext(TestBed.inject(EnvironmentInjector), () => authGuard(route, state));
}

describe('authGuard', () => {
  let authorizeCalls: number;
  let oidcStub: Pick<OidcSecurityService, 'authorize'>;

  beforeEach(() => {
    authorizeCalls = 0;
    oidcStub = {
      authorize: () => {
        authorizeCalls += 1;
      },
    } as Pick<OidcSecurityService, 'authorize'>;

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
        { provide: OidcSecurityService, useValue: oidcStub },
      ],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('returns true for routes flagged publicAccess regardless of session status', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), { sessionStatus: 'unauthenticated' });

    expect(runGuard({ publicAccess: true })).toBe(true);
  });

  it('returns false (defer) when sessionStatus is idle', () => {
    expect(runGuard({})).toBe(false);
  });

  it('returns false (defer) when sessionStatus is loading', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), { sessionStatus: 'loading' });

    expect(runGuard({})).toBe(false);
  });

  it('returns true when authenticated and route is private', () => {
    const store = TestBed.inject(SessionStore);
    store.login(sampleUser, 'club-1');

    expect(runGuard({})).toBe(true);
  });

  it('triggers oidc.authorize() and returns false when unauthenticated on a private route', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), { sessionStatus: 'unauthenticated' });

    const result = runGuard({});

    expect(result).toBe(false);
    expect(authorizeCalls).toBe(1);
  });
});
