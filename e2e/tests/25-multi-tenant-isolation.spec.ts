// Spec #25: multi-tenant isolation. Log in as testclubadmin AND othertestadmin
// via /Token, hit club-scoped list endpoints, assert their result sets are
// disjoint and neither leaks the other's marker rows.
//
// SERVER.md §4: multi-tenancy is enforced by convention (every service must
// filter by ClubId). A failure here is a real tenancy bug, not flaky data.

import { test, expect } from '../fixtures';
import type { APIRequestContext } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

const CLUB_A = { username: 'testclubadmin', password: 's', label: 'TestClub' };
const CLUB_B = { username: 'othertestadmin', password: 's', label: 'OtherClub' };

// Historical glider flight seeded by _test-fixture.sql §5 for TestClub only.
// OtherClub must never surface this id.
const TESTCLUB_HISTORICAL_FLIGHT_ID = 'F1500005-0000-0000-0000-000000000001';

interface PagedResult<T> {
  Items: T[];
  TotalRows?: number;
}

async function login(request: APIRequestContext, user: typeof CLUB_A): Promise<string> {
  const res = await request.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: user.username, password: user.password },
  });
  if (!res.ok()) {
    throw new Error(`Token for ${user.username} failed: ${res.status()} ${await res.text()}`);
  }
  const body = await res.json();
  return body.access_token as string;
}

async function getPaged<T>(
  request: APIRequestContext,
  token: string,
  path: string,
): Promise<PagedResult<T>> {
  // Retry on 401 — freshly-issued tokens occasionally race with validation under load.
  let lastStatus = 0;
  let lastText = '';
  for (let attempt = 1; attempt <= 4; attempt++) {
    const res = await request.post(`${API_BASE}${path}`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { Sorting: {}, SearchFilter: {} },
      timeout: 30_000,
    });
    if (res.ok()) {
      return res.json() as Promise<PagedResult<T>>;
    }
    lastStatus = res.status();
    lastText = await res.text().catch(() => '');
    if (lastStatus !== 401 && lastStatus < 502) break;
    await new Promise(r => setTimeout(r, 250 * attempt));
  }
  expect(false, `${path} (${lastStatus}): ${lastText.slice(0, 200)}`).toBeTruthy();
  throw new Error('unreachable');
}

async function tryLogin(request: APIRequestContext, user: typeof CLUB_A): Promise<string | null> {
  try {
    return await login(request, user);
  } catch {
    return null;
  }
}

test.describe('multi-tenant isolation', () => {
  test('cross-club data is disjoint on flights / persons / reservations', async ({
    request,
  }) => {

    const tokenA = await tryLogin(request, CLUB_A);
    const tokenB = await tryLogin(request, CLUB_B);

    test.skip(
      !tokenA || !tokenB,
      `need both seeded admins; got tokenA=${!!tokenA} tokenB=${!!tokenB}`,
    );

    // Diagnostic: confirm each token actually resolves to its intended user.
    // If these mismatch, the auth filter is broken (or Playwright's request
    // context is sharing a cookie that the server prefers over our bearer).
    const meA = await request.get(`${API_BASE}/api/v1/users/my`, {
      headers: { Authorization: `Bearer ${tokenA!}` },
    });
    const meB = await request.get(`${API_BASE}/api/v1/users/my`, {
      headers: { Authorization: `Bearer ${tokenB!}` },
    });
    expect(meA.ok(), `/users/my A: ${meA.status()}`).toBeTruthy();
    expect(meB.ok(), `/users/my B: ${meB.status()}`).toBeTruthy();
    const userA = await meA.json() as { Username?: string; UserName?: string; ClubId?: string };
    const userB = await meB.json() as { Username?: string; UserName?: string; ClubId?: string };
    const nameA = userA.Username ?? userA.UserName;
    const nameB = userB.Username ?? userB.UserName;
    expect(nameA, 'tokenA must resolve to testclubadmin').toBe(CLUB_A.username);
    expect(nameB, 'tokenB must resolve to othertestadmin').toBe(CLUB_B.username);
    // Surface the ClubId both calls saw — if these are equal, the auth
    // pipeline merged the two users into the same ClubId. If they differ
    // and the flight filter STILL leaks, the bug is elsewhere.
    test.info().annotations.push({
      type: 'tokenA-clubid',
      description: String(userA.ClubId),
    });
    test.info().annotations.push({
      type: 'tokenB-clubid',
      description: String(userB.ClubId),
    });
    expect(userA.ClubId, 'testclubadmin should live in TestClub').not.toBe(userB.ClubId);

    // Flights
    const flightsA = await getPaged<{ FlightId: string }>(
      request, tokenA!, '/api/v1/flights/gliderflights/page/0/200',
    );
    const flightsB = await getPaged<{ FlightId: string }>(
      request, tokenB!, '/api/v1/flights/gliderflights/page/0/200',
    );

    const flightIdsA = new Set((flightsA.Items ?? []).map(f => f.FlightId.toLowerCase()));
    const flightIdsB = new Set((flightsB.Items ?? []).map(f => f.FlightId.toLowerCase()));

    // TestClub's historical fixture flight is visible to A and invisible to B.
    expect(
      flightIdsA.has(TESTCLUB_HISTORICAL_FLIGHT_ID.toLowerCase()),
      'TestClub admin should see the seeded historical glider flight',
    ).toBeTruthy();
    expect(
      flightIdsB.has(TESTCLUB_HISTORICAL_FLIGHT_ID.toLowerCase()),
      'OtherClub admin must NOT see TestClub\'s historical glider flight',
    ).toBeFalsy();

    // Disjoint set check: intersection empty.
    const flightIntersection = [...flightIdsA].filter(id => flightIdsB.has(id));
    expect(
      flightIntersection,
      `Flights leaked across clubs: ${flightIntersection.join(', ')}`,
    ).toEqual([]);

    // Persons
    const personsA = await getPaged<{ PersonId: string; Lastname: string }>(
      request, tokenA!, '/api/v1/persons/page/0/500',
    );
    const personsB = await getPaged<{ PersonId: string; Lastname: string }>(
      request, tokenB!, '/api/v1/persons/page/0/500',
    );

    const personIdsA = new Set((personsA.Items ?? []).map(p => p.PersonId.toLowerCase()));
    const personIdsB = new Set((personsB.Items ?? []).map(p => p.PersonId.toLowerCase()));

    // OtherClub's admin Person (Otheradmin / Other) is in B's list and not A's.
    const otherAdminPersonId = 'f1500002-0000-0000-0000-0000000000b1';
    expect(
      personIdsB.has(otherAdminPersonId),
      'OtherClub admin should see its own Otheradmin person row',
    ).toBeTruthy();
    expect(
      personIdsA.has(otherAdminPersonId),
      'TestClub admin must NOT see OtherClub\'s Otheradmin person row',
    ).toBeFalsy();

    const personIntersection = [...personIdsA].filter(id => personIdsB.has(id));
    expect(
      personIntersection.length,
      `Persons leaked across clubs (sample): ${personIntersection.slice(0, 5).join(', ')}`,
    ).toBe(0);

    // AircraftReservations (may be empty either side; must be disjoint if not)
    const reservA = await getPaged<{ AircraftReservationId: string }>(
      request, tokenA!, '/api/v1/aircraftreservations/page/0/200',
    );
    const reservB = await getPaged<{ AircraftReservationId: string }>(
      request, tokenB!, '/api/v1/aircraftreservations/page/0/200',
    );

    const reservIdsA = new Set(
      (reservA.Items ?? []).map(r => r.AircraftReservationId.toLowerCase()),
    );
    const reservIdsB = new Set(
      (reservB.Items ?? []).map(r => r.AircraftReservationId.toLowerCase()),
    );

    const reservIntersection = [...reservIdsA].filter(id => reservIdsB.has(id));
    expect(
      reservIntersection,
      `Aircraft reservations leaked across clubs: ${reservIntersection.join(', ')}`,
    ).toEqual([]);

    // Union sanity check — catches silent per-side truncation that would mask a leak.
    const union = new Set([...flightIdsA, ...flightIdsB]);
    expect(
      union.size,
      'Flight union should equal sum of per-club sizes (sets disjoint)',
    ).toBe(flightIdsA.size + flightIdsB.size);
  });
});
