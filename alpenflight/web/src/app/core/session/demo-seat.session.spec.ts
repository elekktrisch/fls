import { HttpErrorResponse } from '@angular/common/http';

import {
  applyClaimsToSession,
  applyClaimsUnlessADemoSeatOwnsTheSession,
  type SessionPort,
} from '../auth/oidc-session-bridge';

import {
  claimsOfAccessToken,
  seatBearerRidesThisRequest,
  seatBusyProblemOf,
} from './demo-seat.session';

const SEAT_CLUB_ID_V62_BUILDS_FOR_SEAT_ONE = '019e30c3-2c00-7001-8000-0000000de001';

const SEAT_TOKEN_CLAIMS = {
  sub: 'e2f3a0c0-a001-4a2e-9c6e-22f3a0c0a001',
  preferred_username: 'demo1',
  email: 'demo1@example.com',
  given_name: 'Demo',
  family_name: 'Éins',
  clubId: SEAT_CLUB_ID_V62_BUILDS_FOR_SEAT_ONE,
  realm_access: { roles: ['CLUB_ADMINISTRATOR'] },
};

function base64Url(value: object): string {
  const utf8 = new TextEncoder().encode(JSON.stringify(value));
  const binary = String.fromCharCode(...utf8);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function seatTokenShapedLikeADirectGrant(claims: object): string {
  return ['eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9', base64Url(claims), 'signature'].join('.');
}

function fakeSession(authenticated: boolean): SessionPort & { logoutCalls: number } {
  const port = {
    logoutCalls: 0,
    login: () => undefined,
    logout() {
      port.logoutCalls += 1;
    },
    markUnauthenticated: () => undefined,
    bootstrapPrefetch: () => undefined,
    loadMe: () => undefined,
    isAuthenticated: () => authenticated,
    isLoadingSession: () => false,
  };
  return port;
}

describe('claimsOfAccessToken', () => {
  it('reads the seat club and the realm roles out of the leased access token', () => {
    const claims = claimsOfAccessToken(seatTokenShapedLikeADirectGrant(SEAT_TOKEN_CLAIMS));

    expect(claims).toEqual(SEAT_TOKEN_CLAIMS);
  });

  it('keeps a non-ASCII name intact, so a decode by character code cannot corrupt it', () => {
    const claims = claimsOfAccessToken(seatTokenShapedLikeADirectGrant(SEAT_TOKEN_CLAIMS)) as {
      family_name: string;
    };

    expect(claims.family_name).toBe('Éins');
  });

  it('returns null for a value that is not a JSON web token', () => {
    expect(claimsOfAccessToken('not-a-token')).toBeNull();
    expect(claimsOfAccessToken('header.not-base64-json.signature')).toBeNull();
  });
});

describe('seatBearerRidesThisRequest', () => {
  it('rides an authenticated API path', () => {
    expect(seatBearerRidesThisRequest('/api/v1/flights?page=1')).toBe(true);
  });

  it('never rides the anonymous public segment, because an expired seat token 401s a permitAll path', () => {
    expect(seatBearerRidesThisRequest('/api/v1/public/demo-session')).toBe(false);
  });

  it('never rides a path outside the API', () => {
    expect(seatBearerRidesThisRequest('/Token')).toBe(false);
    expect(seatBearerRidesThisRequest('http://localhost:8090/realms/alpenflight')).toBe(false);
  });
});

describe('seatBusyProblemOf', () => {
  it('reads the readable reason out of a parsed problem detail body', () => {
    const failure = new HttpErrorResponse({
      status: 503,
      error: {
        type: 'urn:alpenflight:problem:demo-pool-exhausted',
        detail: 'All seats are in use.',
      },
    });

    expect(seatBusyProblemOf(failure)?.detail).toBe('All seats are in use.');
  });

  it('reads the readable reason out of an unparsed problem detail body', () => {
    const failure = new HttpErrorResponse({
      status: 503,
      error: JSON.stringify({ detail: 'All seats are in use.' }),
    });

    expect(seatBusyProblemOf(failure)?.detail).toBe('All seats are in use.');
  });

  it('reports no seat-busy state for another status or another failure kind', () => {
    expect(seatBusyProblemOf(new HttpErrorResponse({ status: 500, error: {} }))).toBeNull();
    expect(seatBusyProblemOf(new Error('offline'))).toBeNull();
  });
});

describe('applyClaimsUnlessADemoSeatOwnsTheSession', () => {
  it('keeps the session a live demo seat owns, because the seat holds no OIDC user data', () => {
    const session = fakeSession(true);

    applyClaimsUnlessADemoSeatOwnsTheSession(null, session, true);

    expect(session.logoutCalls).toBe(0);
  });

  it('still evicts an authenticated session that no demo seat owns', () => {
    const session = fakeSession(true);

    applyClaimsUnlessADemoSeatOwnsTheSession(null, session, false);

    expect(session.logoutCalls).toBe(1);
  });

  it('scores the unguarded call, which evicts the demo seat on the next OIDC read', () => {
    const session = fakeSession(true);

    applyClaimsToSession(null, session);

    expect(session.logoutCalls).toBe(1);
  });
});
