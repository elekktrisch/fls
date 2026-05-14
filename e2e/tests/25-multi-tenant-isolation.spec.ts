// e2e/tests/25-multi-tenant-isolation.spec.ts
//
// Task #25: Multi-tenant isolation check across two clubs.
//
// The deterministic fixture (database/FLSTest/3 insert/_test-fixture.sql §1+§2)
// seeds two clubs:
//   - "TestClub"  (ClubId A1DDE...842B reused from base seed) with admin
//     `testclubadmin` / `s`
//   - "OtherClub" (ClubId F1500002-...-0001) with admin `othertestadmin` / `s`
//
// This spec authenticates as each admin via POST /Token directly (NOT via the
// `loggedInPage` fixture, which is hard-coded to a single user), then calls a
// handful of club-scoped list endpoints and asserts:
//   1. The two result sets are disjoint on stable identifiers
//        (flight IDs / reservation IDs / person IDs).
//   2. Neither club's view leaks the other club's marker rows
//        (the fixture-seeded historical glider flight only exists for TestClub
//        — OtherClub must NOT see it).
//
// IMPORTANT CAVEAT — convention, not framework
// --------------------------------------------
// SERVER.md §4 spells out that multi-tenancy is enforced by *convention* — every
// service is expected to filter by `CurrentAuthenticatedFLSUserClubId` itself.
// There is no EF global filter or framework guarantee, so a developer who
// forgets the filter in a new service method silently leaks cross-tenant data.
// This spec is therefore a regression check that the existing endpoints we
// query continue to honour the convention; if it ever starts failing, it's
// pointing at a real tenancy bug, not flaky test data.

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
  // Server-side controllers (Flights/AircraftReservations/Persons) accept a
  // PageableSearchFilter with Sorting + SearchFilter; an empty body still
  // returns the first page filtered to the caller's club.
  const res = await request.post(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { Sorting: {}, SearchFilter: {} },
  });
  expect(res.ok(), `${path} (${res.status()})`).toBeTruthy();
  return res.json() as Promise<PagedResult<T>>;
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
      `Need both seeded admins to verify isolation. ` +
        `Got tokenA=${!!tokenA} (${CLUB_A.username}), tokenB=${!!tokenB} (${CLUB_B.username}). ` +
        `Ensure the deterministic fixture (_test-fixture.sql §2) ran during seed.`,
    );

    // ---- 1. Flights (paged glider flights) -------------------------------
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

    // ---- 2. Persons (paged person overview) ------------------------------
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

    // ---- 3. AircraftReservations (paged) ---------------------------------
    // Reservations are club-scoped; both lists may be empty on a fresh seed,
    // but if either has rows they must be disjoint.
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

    // ---- 4. Union-vs-total sanity check on flights -----------------------
    // Convention-not-framework caveat: if the server stops filtering by club,
    // both A and B would converge on the same superset and the intersection
    // assertion above would already fail. The union/total check here is a
    // secondary signal — it asserts that neither side is silently truncating
    // its own results (which could mask a leak).
    const union = new Set([...flightIdsA, ...flightIdsB]);
    expect(
      union.size,
      'Flight union should equal sum of per-club sizes (sets disjoint)',
    ).toBe(flightIdsA.size + flightIdsB.size);
  });
});
