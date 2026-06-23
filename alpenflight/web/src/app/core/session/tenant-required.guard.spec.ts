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
import { Observable, Subject, isObservable, of } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { JoinRequestResponse } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { onboardingRedirect, tenantRequiredGuard } from './tenant-required.guard';
import { SessionStore, type User } from './session.store';

// @mocked: http — guard unit test stubs the join-request probe

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

const onboardingPilot: User = {
  id: 'u-3',
  username: 'newbie',
  email: 'newbie@example.test',
  firstName: 'New',
  lastName: 'Bie',
  clubId: null,
  personId: null,
  homebaseLocationId: null,
  roles: ['PILOT'],
};

const livePending: JoinRequestResponse = { id: 'jr-1', clubId: 'club-1', status: 'PENDING' };

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

function urlOf(value: boolean | UrlTree | undefined): string | null {
  return value !== undefined && typeof value !== 'boolean' ? value.toString() : null;
}

describe('tenantRequiredGuard', () => {
  let authorizeCalls: number;
  let myJoinRequestResult: Observable<JoinRequestResponse | null>;

  beforeEach(() => {
    authorizeCalls = 0;
    myJoinRequestResult = of(null);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouterStub(),
        { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
        {
          provide: JoinRequestsService,
          useValue: {
            myJoinRequest: () => myJoinRequestResult,
          } as Pick<JoinRequestsService, 'myJoinRequest'>,
        },
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

  it('bounces a tenant-less SYSTEM_ADMINISTRATOR to /start (club-less is legitimate)', () => {
    const store = TestBed.inject(SessionStore);
    store.login(tenantlessSysadmin, null);

    const { emissions } = collect(runGuard());
    expect(urlOf(emissions[0])).toBe('/start');
  });

  it('routes a tenant-less onboarding pilot with a live request to /join/pending', () => {
    const store = TestBed.inject(SessionStore);
    store.login(onboardingPilot, null);
    myJoinRequestResult = of(livePending);

    const { emissions } = collect(runGuard());
    expect(urlOf(emissions[0])).toBe('/join/pending');
  });

  it('routes a tenant-less onboarding pilot with no live request to /join', () => {
    const store = TestBed.inject(SessionStore);
    store.login(onboardingPilot, null);
    myJoinRequestResult = of(null);

    const { emissions } = collect(runGuard());
    expect(urlOf(emissions[0])).toBe('/join');
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
    // bounced on the transient loading-null clubId.
    store.login(tenantedUser, 'club-1');
    TestBed.tick();

    expect(emissions).toEqual([true]);
    expect(authorizeCalls).toBe(0);
  });

  it('WAITS while loading, then routes to /join once settled tenant-less with no request', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), { sessionStatus: 'loading' });

    const { emissions } = collect(runGuard());
    TestBed.tick();
    expect(emissions).toEqual([]);

    store.login(onboardingPilot, null);
    TestBed.tick();

    expect(emissions.length).toBe(1);
    expect(urlOf(emissions[0])).toBe('/join');
  });
});

describe('onboardingRedirect', () => {
  it('routes to /join/pending when a live request is waiting', () => {
    expect(onboardingRedirect(livePending)).toBe('/join/pending');
  });

  it('routes to /join with no request or an already-final request', () => {
    expect(onboardingRedirect(null)).toBe('/join');
    expect(onboardingRedirect({ ...livePending, status: 'DENIED' })).toBe('/join');
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
