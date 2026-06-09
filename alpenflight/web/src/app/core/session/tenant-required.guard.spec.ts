import {
  EnvironmentInjector,
  provideZonelessChangeDetection,
  runInInjectionContext,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  Router,
  type ActivatedRouteSnapshot,
  type RouterStateSnapshot,
  type UrlTree,
} from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { patchState } from '@ngrx/signals';
import { unprotected } from '@ngrx/signals/testing';
import { Subject, isObservable } from 'rxjs';

import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { tenantRequiredGuard } from './tenant-required.guard';
import { SessionStore, type User } from './session.store';

const tenantedUser: User = {
  id: 'u-1',
  username: 'alice',
  email: 'alice@example.test',
  firstName: 'Alice',
  lastName: 'Doe',
  clubId: 'club-1',
  personId: null,
  homebaseLocationId: null,
  roles: ['PILOT'],
};

const tenantlessSysadmin: User = {
  id: 'u-2',
  username: 'root',
  email: 'root@example.test',
  firstName: 'Root',
  lastName: 'Admin',
  clubId: null,
  personId: null,
  homebaseLocationId: null,
  roles: ['SYSTEM_ADMINISTRATOR'],
};

function runGuard(data: Record<string, unknown> = {}, url = '/flights') {
  const route = { data } as unknown as ActivatedRouteSnapshot;
  const state = { url } as RouterStateSnapshot;
  return runInInjectionContext(TestBed.inject(EnvironmentInjector), () =>
    tenantRequiredGuard(route, state),
  );
}

function collect(result: ReturnType<typeof tenantRequiredGuard>): {
  emissions: (boolean | UrlTree)[];
} {
  const emissions: (boolean | UrlTree)[] = [];
  if (isObservable(result)) {
    result.subscribe((v) => emissions.push(v as boolean | UrlTree));
  } else {
    emissions.push(result as boolean | UrlTree);
  }
  return { emissions };
}

function isStartUrl(value: boolean | UrlTree | undefined): boolean {
  return value !== undefined && typeof value !== 'boolean' && value.toString() === '/start';
}

describe('tenantRequiredGuard', () => {
  let authorizeCalls: number;

  beforeEach(() => {
    authorizeCalls = 0;
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouterStub(),
        { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
        {
          provide: OidcSecurityService,
          useValue: {
            authorize: () => {
              authorizeCalls += 1;
            },
          } as Pick<OidcSecurityService, 'authorize'>,
        },
      ],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('passes synchronously when settled authenticated with a tenant', () => {
    const store = TestBed.inject(SessionStore);
    store.login(tenantedUser, 'club-1');

    expect(runGuard()).toBe(true);
  });

  it('bounces to /start synchronously when settled authenticated but tenant-less', () => {
    const store = TestBed.inject(SessionStore);
    store.login(tenantlessSysadmin, null);

    const result = runGuard();
    expect(isStartUrl(result as boolean | UrlTree)).toBe(true);
  });

  it('redirects to Keycloak when settled unauthenticated (ADR 0007 hard-401 preserved)', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), { sessionStatus: 'unauthenticated' });

    const result = runGuard();
    // authGuard short-circuits with `false`; the tenant check never runs.
    expect(result).toBe(false);
    expect(authorizeCalls).toBe(1);
  });

  it('WAITS while loading, then PASSES once the session settles with a tenant', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), { sessionStatus: 'loading' });

    const { emissions } = collect(runGuard());
    TestBed.tick();
    expect(emissions).toEqual([]);

    // Settles with a club id (loadMe populated currentClubId). Must NOT have
    // bounced to /start on the transient loading-null clubId.
    store.login(tenantedUser, 'club-1');
    TestBed.tick();

    expect(emissions).toEqual([true]);
    expect(authorizeCalls).toBe(0);
  });

  it('WAITS while loading, then bounces to /start once settled tenant-less', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), { sessionStatus: 'loading' });

    const { emissions } = collect(runGuard());
    TestBed.tick();
    expect(emissions).toEqual([]);

    store.login(tenantlessSysadmin, null);
    TestBed.tick();

    expect(emissions.length).toBe(1);
    expect(isStartUrl(emissions[0])).toBe(true);
  });
});

// Minimal Router providing only parseUrl — provideRouter([]) would also work
// but pulls the full router; the guard only needs parseUrl for the bounce.
function provideRouterStub() {
  return {
    provide: Router,
    useValue: {
      parseUrl: (url: string) => ({ toString: () => url }) as unknown as UrlTree,
    } as Pick<Router, 'parseUrl'>,
  };
}
