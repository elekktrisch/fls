import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { patchState } from '@ngrx/signals';
import { unprotected } from '@ngrx/signals/testing';
import { Subject } from 'rxjs';

import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { SessionStore, type User } from '../session/session.store';

import {
  applyClaimsToSession,
  handleSilentRenewFailed,
  type SessionPort,
} from './oidc-session-bridge';

const sampleClaims = {
  sub: 'b9c0e2a5-1d3f-4a2e-9c6e-22f3a0c0a001',
  preferred_username: 'clubadmin1',
  email: 'clubadmin1@example.com',
  given_name: 'Carla',
  family_name: 'Admin',
  clubId: '019e30c3-2c00-7001-8000-000000000001',
  realm_access: { roles: ['CLUB_ADMINISTRATOR'] },
};

function fakeSession(opts: { authenticated?: boolean; loading?: boolean } = {}): SessionPort & {
  loginCalls: { user: User; clubId: string | null }[];
  logoutCalls: number;
  markUnauthenticatedCalls: number;
} {
  const port = {
    loginCalls: [] as { user: User; clubId: string | null }[],
    logoutCalls: 0,
    markUnauthenticatedCalls: 0,
    login(user: User, clubId: string | null) {
      port.loginCalls.push({ user, clubId });
    },
    logout() {
      port.logoutCalls += 1;
    },
    markUnauthenticated() {
      port.markUnauthenticatedCalls += 1;
    },
    isAuthenticated: () => opts.authenticated ?? false,
    isLoadingSession: () => opts.loading ?? false,
  };
  return port;
}

describe('applyClaimsToSession', () => {
  it('calls session.login with the mapped user when claims are valid', () => {
    const session = fakeSession();

    applyClaimsToSession(sampleClaims, session);

    expect(session.loginCalls).toHaveLength(1);
    const call = session.loginCalls[0]!;
    expect(call.user.id).toBe(sampleClaims.sub);
    expect(call.clubId).toBe(sampleClaims.clubId);
    expect(session.logoutCalls).toBe(0);
    expect(session.markUnauthenticatedCalls).toBe(0);
  });

  it('passes clubId === null through to login when the claim is absent', () => {
    const session = fakeSession();
    const { clubId: _strip, ...rest } = sampleClaims;
    void _strip;

    applyClaimsToSession(rest, session);

    expect(session.loginCalls[0]!.clubId).toBeNull();
  });

  it('calls session.logout when claims are null AND the session is currently authenticated', () => {
    const session = fakeSession({ authenticated: true });

    applyClaimsToSession(null, session);

    expect(session.logoutCalls).toBe(1);
    expect(session.loginCalls).toHaveLength(0);
    expect(session.markUnauthenticatedCalls).toBe(0);
  });

  it('settles to unauthenticated (without firing bus) when claims are null on cold-start (loading)', () => {
    // Cold-start path: withAppInitializerAuthCheck completes with no
    // principal; userData() emits null; SessionStore is still 'idle'.
    // markUnauthenticated lets the route guard fall through to
    // oidcSecurity.authorize() instead of deferring forever.
    const session = fakeSession({ authenticated: false, loading: true });

    applyClaimsToSession(null, session);

    expect(session.markUnauthenticatedCalls).toBe(1);
    expect(session.logoutCalls).toBe(0);
    expect(session.loginCalls).toHaveLength(0);
  });

  it('no-op when claims are null AND status is already settled-unauthenticated', () => {
    // Steady state after the cold-start transition above. Subsequent
    // userData() emissions of null must NOT keep calling markUnauthenticated.
    const session = fakeSession({ authenticated: false, loading: false });

    applyClaimsToSession(null, session);

    expect(session.markUnauthenticatedCalls).toBe(0);
    expect(session.logoutCalls).toBe(0);
    expect(session.loginCalls).toHaveLength(0);
  });
});

describe('handleSilentRenewFailed', () => {
  it('clears the session FIRST, then triggers re-authorization (no stale-session race)', () => {
    const order: string[] = [];
    const session: SessionPort = {
      login: () => undefined,
      logout: () => order.push('session.logout'),
      markUnauthenticated: () => undefined,
      isAuthenticated: () => true,
      isLoadingSession: () => false,
    };
    const reauthorize = () => order.push('oidc.authorize');

    handleSilentRenewFailed(session, reauthorize);

    expect(order).toEqual(['session.logout', 'oidc.authorize']);
  });
});

describe('SessionStore widening (S-021)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
      ],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('accepts clubId === null on login (federated user with no clubId claim)', () => {
    const store = TestBed.inject(SessionStore);
    const userWithoutClub: User = {
      id: 'u-1',
      username: 'federated',
      email: 'fed@example.test',
      firstName: '',
      lastName: '',
      clubId: null,
      roles: ['CLUB_ADMINISTRATOR'],
    };

    store.login(userWithoutClub, null);

    expect(store.isAuthenticated()).toBe(true);
    expect(store.authenticatedUser()?.clubId).toBeNull();
    expect(store.currentClubId()).toBeNull();
  });

  it('still tracks CLUB_ADMINISTRATOR / SYSTEM_ADMINISTRATOR predicates', () => {
    const store = TestBed.inject(SessionStore);
    patchState(unprotected(store), {
      authenticatedUser: {
        id: 'u-2',
        username: 'sys',
        email: 'sys@example.test',
        firstName: '',
        lastName: '',
        clubId: null,
        roles: ['SYSTEM_ADMINISTRATOR'],
      },
      sessionStatus: 'authenticated',
    });

    expect(store.isSystemAdmin()).toBe(true);
    expect(store.isClubAdmin()).toBe(false);
  });
});
