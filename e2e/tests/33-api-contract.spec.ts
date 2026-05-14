// e2e/tests/33-api-contract.spec.ts
//
// API CONTRACT TEST — Task #33 of the e2e-gap plan.
// ---------------------------------------------------------------------------
//
// PARITY CONTRACT RATIONALE
//
// This file is the *behavioral spec* for the soon-to-be-rewritten FLS
// server. Every endpoint exercised here is one the AngularJS client at
// `flsweb/src/` actually calls in production. The rewritten server must
// keep all assertions in this file green; any breaking shape change shows
// up here before it shows up in the UI.
//
// Coverage philosophy:
//   - Status code only goes up to "successful or expected-403" — we do NOT
//     attempt deep schema validation. A breaking change usually shows up as
//     a missing key, a renamed key, or a value flipping primitive type. The
//     `expectShape()` helper catches those, nothing more.
//   - One assertion per endpoint: present + correctly-typed top-level shape
//     (object vs array vs paged envelope). Where the client relies on a
//     specific field (e.g. `ClubId` on club-scoped entities), we add a
//     targeted invariant.
//   - GET / POST-page only. Write operations (POST / PUT / DELETE) belong
//     in the per-feature spec files (#21 deliveries, #22 flight-locking,
//     #25 reservations, etc.). This file is *read-only on purpose* so it
//     can run against any DB state without fear of mutation cross-talk.
//
// HOW THE ENDPOINT LIST WAS ENUMERATED
//
//   1. `grep -rh "GLOBALS.BASE_URL + '/api/v1/...'" flsweb/src/` to capture
//      every URL the client builds at runtime (76 unique patterns).
//   2. Cross-checked against the C# `[RoutePrefix]` declarations in
//      `flsserver/src/FLS.Server.Web/Controllers/*.cs` to confirm the
//      server side actually serves the route.
//   3. Trimmed to the ~40 paths that:
//        - Are GET-able OR are paged-list POSTs the client hits on every
//          page load.
//        - Do NOT require a specific seeded entity ID (those go in the
//          per-feature specs that own a `freshDb` fixture).
//        - Do NOT mutate state (so this file is safe to re-run forever).
//   4. Workflow endpoints are listed but only the ones the client UI does
//      NOT invoke directly (they're triggered by the FLS.Workflow.Activator
//      console app via cron) — they're contract-checked here because the
//      rewrite must preserve the same HTTP surface for the activator.
//
// CONTRACT NOTES
//
//   - All endpoints below require a Bearer token EXCEPT `/Token` itself,
//     `/api/v1/translations`, `/api/v1/users/lostpassword` (POST only,
//     [AllowAnonymous]), `/api/v1/trialflightsregistrations` (POST,
//     [AllowAnonymous]), and `/api/v1/passengerflightsregistrations` (POST,
//     [AllowAnonymous]). The latter three are POST-only and covered in
//     #9 / #11 — we don't re-test them here.
//   - ClubAdministrator-only endpoints (per `[Authorize(Roles = ...)]` on
//     the controllers): /api/v1/users/page, /api/v1/clubs/page,
//     /api/v1/accountingrulefilters/page, /api/v1/deliveries/page,
//     /api/v1/deliverycreationtests/page, /api/v1/systemlogs/page. The
//     `testclubadmin` user has the ClubAdministrator role, so these
//     succeed; the rewrite must keep the same gating.
//   - Multi-tenancy invariant (see SERVER.md §4): every collection scoped
//     to a club returns rows that all carry the *same* ClubId — the
//     authenticated user's. We spot-check this on a handful of endpoints
//     (`/aircrafts/overview`, `/persons/listitems/true`) and leave the
//     rest to per-feature specs.

import { test, expect, APIRequestContext } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';

// Shared bearer token. Captured once in beforeAll and reused for every test.
let token = '';
let myClubId = '';

// Empty pageable payload — matches the shape the client uses on first page
// load before any filter or sort is applied. See `flsweb/src/flights/
// FlightsServices.js:9-17` for the canonical client-side payload.
const EMPTY_PAGE_BODY = { Sorting: {}, SearchFilter: {} };

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function authHeaders(): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

/**
 * Assert that `value` is a plain object (not null, not array).
 */
function expectObject(value: unknown, label: string): asserts value is Record<string, unknown> {
  expect(value, `${label} should be a non-null object`).toBeTruthy();
  expect(typeof value, `${label} should be typeof object`).toBe('object');
  expect(Array.isArray(value), `${label} should not be an array`).toBe(false);
}

/**
 * Assert that `value` is an array (and optionally that the first element
 * has the expected keys).
 */
function expectArray(value: unknown, label: string): asserts value is unknown[] {
  expect(Array.isArray(value), `${label} should be an array`).toBe(true);
}

/**
 * Lightweight shape check — verifies the value has every key in `keys` and
 * each is *not undefined*. Null is allowed (the server uses nullable
 * columns liberally). This is intentionally less strict than a real schema
 * validator: we want to detect missing/renamed keys, not nullability drift.
 */
function expectKeys(value: Record<string, unknown>, keys: readonly string[], label: string): void {
  for (const key of keys) {
    expect(value, `${label} missing key '${key}'`).toHaveProperty(key);
  }
}

/**
 * Paged-list envelope shape. The server's `PagedList<T>` (see
 * `FLS.Data.WebApi/PagingSorting/PagedList.cs`) is the wire shape for every
 * `POST /<resource>/page/{pageStart}/{pageSize}` endpoint.
 */
function expectPagedEnvelope(body: unknown, label: string): Record<string, unknown> {
  expectObject(body, label);
  expectKeys(body, ['Items', 'TotalRows', 'PageStart', 'PageSize'] as const, label);
  expectArray((body as Record<string, unknown>).Items, `${label}.Items`);
  expect(typeof (body as Record<string, unknown>).TotalRows).toBe('number');
  return body as Record<string, unknown>;
}

/**
 * Fetch the token once and capture the current user's club id (used to
 * assert multi-tenancy invariants below).
 */
async function authenticate(request: APIRequestContext): Promise<void> {
  const tokenRes = await request.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: USERNAME, password: PASSWORD },
  });
  if (!tokenRes.ok()) {
    throw new Error(`/Token failed: ${tokenRes.status()} ${await tokenRes.text()}`);
  }
  const tokenBody = await tokenRes.json();
  token = tokenBody.access_token as string;

  const meRes = await request.get(`${API_BASE}/api/v1/users/my`, {
    headers: authHeaders(),
  });
  if (meRes.ok()) {
    const me = await meRes.json();
    if (me && typeof me.ClubId === 'string') {
      myClubId = me.ClubId;
    }
  }
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

test.beforeAll(async ({ playwright }) => {
  const ctx = await playwright.request.newContext();
  try {
    await authenticate(ctx);
  } finally {
    await ctx.dispose();
  }
  expect(token, 'bearer token should be populated by /Token').toBeTruthy();
});

// ---------------------------------------------------------------------------
// Category: AUTH / IDENTITY
// ---------------------------------------------------------------------------

test('contract:auth POST /Token returns access_token + token_type', async ({ request }) => {
  const res = await request.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: USERNAME, password: PASSWORD },
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/Token body');
  expectKeys(body, ['access_token', 'token_type', 'expires_in'] as const, '/Token');
  expect(typeof body.access_token).toBe('string');
  expect(body.token_type).toBe('bearer');
});

test('contract:auth POST /Token rejects bad credentials with 400', async ({ request }) => {
  const res = await request.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: USERNAME, password: 'definitely-wrong' },
  });
  expect(res.status()).toBe(400);
});

test('contract:auth GET /api/v1/users/my returns current user', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/users/my`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/users/my');
  expectKeys(body, ['UserId', 'Username', 'ClubId'] as const, '/users/my');
  expect(typeof body.ClubId).toBe('string');
  expect(body.Username).toBeTruthy();
});

test('contract:auth GET /api/v1/userroles returns array', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/userroles`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/userroles');
});

test('contract:auth GET /api/v1/persons/my returns current user person', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/my`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/persons/my');
  expectKeys(body, ['PersonId'] as const, '/persons/my');
});

test('contract:auth GET /api/v1/useraccountstates returns array of states', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/useraccountstates`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/useraccountstates');
  if (body.length) {
    expectObject(body[0], '/useraccountstates[0]');
    expectKeys(body[0] as Record<string, unknown>, ['UserAccountStateId'] as const, '/useraccountstates[0]');
  }
});

// ---------------------------------------------------------------------------
// Category: CLUBS
// ---------------------------------------------------------------------------

test('contract:clubs GET /api/v1/clubs/my returns the user club', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/clubs/my`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/clubs/my');
  expectKeys(body, ['ClubId', 'Clubname', 'ClubKey'] as const, '/clubs/my');
  // Invariant: my club ID matches the logged-in user's ClubId.
  if (myClubId) {
    expect(body.ClubId).toBe(myClubId);
  }
});

test('contract:clubs POST /api/v1/clubs/page/0/20 returns paged envelope', async ({ request }) => {
  // ClubAdministrator + SystemAdministrator only — testclubadmin has Club role.
  const res = await request.post(`${API_BASE}/api/v1/clubs/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectPagedEnvelope(body, '/clubs/page');
});

// ---------------------------------------------------------------------------
// Category: FLIGHTS
// ---------------------------------------------------------------------------

test('contract:flights POST /api/v1/flights/gliderflights/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/flights/gliderflights/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  const envelope = expectPagedEnvelope(body, '/flights/gliderflights/page');
  const items = envelope.Items as unknown[];
  if (items.length) {
    expectObject(items[0], '/flights/gliderflights/page Items[0]');
    expectKeys(items[0] as Record<string, unknown>,
      ['FlightId', 'FlightDate', 'ProcessStateId'] as const,
      'glider flight item');
  }
});

test('contract:flights POST /api/v1/flights/motorflights/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/flights/motorflights/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  const envelope = expectPagedEnvelope(body, '/flights/motorflights/page');
  const items = envelope.Items as unknown[];
  if (items.length) {
    expectKeys(items[0] as Record<string, unknown>,
      ['FlightId', 'FlightDate'] as const,
      'motor flight item');
  }
});

// ---------------------------------------------------------------------------
// Category: AIRCRAFTS
// ---------------------------------------------------------------------------

test('contract:aircrafts GET /api/v1/aircrafts/overview', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/aircrafts/overview`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/aircrafts/overview');
  if (body.length) {
    const first = body[0] as Record<string, unknown>;
    expectKeys(first, ['AircraftId', 'Immatriculation'] as const, 'aircraft overview item');
  }
});

test('contract:aircrafts GET /api/v1/aircrafts/listitems/gliders', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/aircrafts/listitems/gliders`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/aircrafts/listitems/gliders');
});

test('contract:aircrafts GET /api/v1/aircrafts/listitems/towingaircrafts', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/aircrafts/listitems/towingaircrafts`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/aircrafts/listitems/towingaircrafts');
});

test('contract:aircrafts GET /api/v1/aircrafts/listitems/motoraircrafts', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/aircrafts/listitems/motoraircrafts`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/aircrafts/listitems/motoraircrafts');
});

test('contract:aircrafts POST /api/v1/aircrafts/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/aircrafts/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectPagedEnvelope(body, '/aircrafts/page');
});

test('contract:aircrafts GET /api/v1/aircrafttypes', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/aircrafttypes`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/aircrafttypes');
});

// ---------------------------------------------------------------------------
// Category: PERSONS
// ---------------------------------------------------------------------------

test('contract:persons GET /api/v1/persons/listitems/true', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/listitems/true`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/persons/listitems/true');
  if (body.length) {
    expectKeys(body[0] as Record<string, unknown>,
      ['PersonId', 'Lastname'] as const, 'person listitem');
  }
});

test('contract:persons GET /api/v1/persons/gliderpilots/listitems/true', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/gliderpilots/listitems/true`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/persons/gliderpilots/listitems/true');
});

test('contract:persons GET /api/v1/persons/gliderinstructors/listitems/true', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/gliderinstructors/listitems/true`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/persons/gliderinstructors/listitems/true');
});

test('contract:persons GET /api/v1/persons/towingpilots/listitems/true', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/towingpilots/listitems/true`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/persons/towingpilots/listitems/true');
});

test('contract:persons GET /api/v1/persons/motorpilots/listitems/true', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/motorpilots/listitems/true`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/persons/motorpilots/listitems/true');
});

test('contract:persons GET /api/v1/persons/winchoperators/listitems/true', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/winchoperators/listitems/true`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/persons/winchoperators/listitems/true');
});

test('contract:persons GET /api/v1/persons/passengers/listitems/true', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/persons/passengers/listitems/true`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/persons/passengers/listitems/true');
});

test('contract:persons POST /api/v1/persons/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/persons/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/persons/page');
});

test('contract:persons GET /api/v1/personcategories', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/personcategories`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/personcategories');
});

// ---------------------------------------------------------------------------
// Category: USERS
// ---------------------------------------------------------------------------

test('contract:users GET /api/v1/users/overview/club', async ({ request }) => {
  // ClubAdministrator role required (per UsersController route attributes).
  const res = await request.get(`${API_BASE}/api/v1/users/overview/club`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/users/overview/club');
  if (body.length) {
    const first = body[0] as Record<string, unknown>;
    expectKeys(first, ['UserId', 'Username'] as const, 'user overview item');
    // Multi-tenancy invariant: every user in /users/overview/club belongs
    // to the authenticated user's club.
    if (myClubId) {
      for (const u of body) {
        const row = u as Record<string, unknown>;
        expect(row.ClubId, 'every user in /users/overview/club must share ClubId').toBe(myClubId);
      }
    }
  }
});

test('contract:users POST /api/v1/users/page/0/20', async ({ request }) => {
  // ClubAdministrator-gated.
  const res = await request.post(`${API_BASE}/api/v1/users/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/users/page');
});

// ---------------------------------------------------------------------------
// Category: LOCATIONS
// ---------------------------------------------------------------------------

test('contract:locations GET /api/v1/locations/', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/locations/`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/locations/');
  if (body.length) {
    expectKeys(body[0] as Record<string, unknown>,
      ['LocationId'] as const, 'location item');
  }
});

test('contract:locations POST /api/v1/locations/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/locations/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/locations/page');
});

test('contract:locations GET /api/v1/locationtypes/listitems', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/locationtypes/listitems`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/locationtypes/listitems');
});

// ---------------------------------------------------------------------------
// Category: PLANNING
// ---------------------------------------------------------------------------

test('contract:planning POST /api/v1/planningdays/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/planningdays/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/planningdays/page');
});

// ---------------------------------------------------------------------------
// Category: RESERVATIONS
// ---------------------------------------------------------------------------

test('contract:reservations POST /api/v1/aircraftreservations/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/aircraftreservations/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/aircraftreservations/page');
});

test('contract:reservations GET /api/v1/aircraftreservationtypes', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/aircraftreservationtypes`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/aircraftreservationtypes');
});

// ---------------------------------------------------------------------------
// Category: REPORTS
// ---------------------------------------------------------------------------

test('contract:reports POST /api/v1/flightreports/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/flightreports/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  // The reports endpoint sometimes returns 200 even with no data; we don't
  // assert on Items being non-empty.
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/flightreports/page');
});

// ---------------------------------------------------------------------------
// Category: MASTERDATA (dropdowns + enums)
// ---------------------------------------------------------------------------

test('contract:masterdata GET /api/v1/countries', async ({ request }) => {
  // Public — no auth needed (controller has [Authorize] commented out).
  const res = await request.get(`${API_BASE}/api/v1/countries`);
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/countries');
  if (body.length) {
    expectKeys(body[0] as Record<string, unknown>,
      ['CountryId', 'CountryName'] as const, 'country');
  }
});

test('contract:masterdata GET /api/v1/languages', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/languages`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/languages');
});

test('contract:masterdata GET /api/v1/starttypes', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/starttypes`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/starttypes');
});

test('contract:masterdata GET /api/v1/counterunittypes', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/counterunittypes`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/counterunittypes');
});

test('contract:masterdata GET /api/v1/elevationunittypes/listitems', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/elevationunittypes/listitems`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/elevationunittypes/listitems');
});

test('contract:masterdata GET /api/v1/lengthunittypes/listitems', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/lengthunittypes/listitems`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/lengthunittypes/listitems');
});

test('contract:masterdata GET /api/v1/flightcostbalancetypes', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/flightcostbalancetypes`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/flightcostbalancetypes');
});

test('contract:masterdata GET /api/v1/flighttypes/overview', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/flighttypes/overview`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/flighttypes/overview');
});

test('contract:masterdata GET /api/v1/flighttypes/gliders', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/flighttypes/gliders`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/flighttypes/gliders');
});

test('contract:masterdata GET /api/v1/flighttypes/towing', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/flighttypes/towing`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/flighttypes/towing');
});

test('contract:masterdata GET /api/v1/flighttypes/motor', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/flighttypes/motor`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/flighttypes/motor');
});

test('contract:masterdata GET /api/v1/flightcrewtypes/listitems', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/flightcrewtypes/listitems`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/flightcrewtypes/listitems');
});

test('contract:masterdata GET /api/v1/memberstates', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/memberstates`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/memberstates');
});

test('contract:masterdata GET /api/v1/accountingrulefiltertypes', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/accountingrulefiltertypes`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/accountingrulefiltertypes');
});

test('contract:masterdata GET /api/v1/accountingunittypes', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/accountingunittypes`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/accountingunittypes');
});

test('contract:masterdata GET /api/v1/articles', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/articles`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  expectArray(await res.json(), '/articles');
});

test('contract:masterdata POST /api/v1/accountingrulefilters/page/0/20', async ({ request }) => {
  // ClubAdministrator-gated.
  const res = await request.post(`${API_BASE}/api/v1/accountingrulefilters/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/accountingrulefilters/page');
});

// ---------------------------------------------------------------------------
// Category: DELIVERIES
// ---------------------------------------------------------------------------

test('contract:deliveries POST /api/v1/deliveries/page/0/20', async ({ request }) => {
  // ClubAdministrator-gated.
  const res = await request.post(`${API_BASE}/api/v1/deliveries/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/deliveries/page');
});

test('contract:deliveries POST /api/v1/deliverycreationtests/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/deliverycreationtests/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/deliverycreationtests/page');
});

// ---------------------------------------------------------------------------
// Category: AUDIT / DASHBOARD
// ---------------------------------------------------------------------------

test('contract:audit GET /api/v1/dashboards returns dashboard payload', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/dashboards`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  // Dashboards may return either an object or array depending on the
  // user's role. Just assert it's a JSON value.
  expect(body === null || typeof body === 'object').toBeTruthy();
});

// ---------------------------------------------------------------------------
// Category: WORKFLOWS
// ---------------------------------------------------------------------------
//
// These endpoints are GET-only and idempotent at the HTTP level (each just
// runs the underlying job). All require ClubAdministrator or
// SystemAdministrator. We only assert 200 — assertions about side effects
// (emails sent, flights locked, deliveries created) live in #8 / #21 / #22.

test('contract:workflows GET /api/v1/workflows/flightvalidation returns 200', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/workflows/flightvalidation`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
});

test('contract:workflows GET /api/v1/workflows/dailyreports returns 200', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/workflows/dailyreports`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
});

test('contract:workflows GET /api/v1/workflows/planningdaymails returns 200', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/workflows/planningdaymails`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
});

test('contract:workflows GET /api/v1/workflows/deliverycreation returns 200', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/workflows/deliverycreation`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
});

// ---------------------------------------------------------------------------
// Category: TRANSLATIONS (i18n)
// ---------------------------------------------------------------------------

test('contract:translations GET /api/v1/translations?lang=de returns map', async ({ request }) => {
  // Public — angular-translate loader uses this without a bearer token.
  const res = await request.get(`${API_BASE}/api/v1/translations?lang=de`);
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/translations?lang=de');
  // Translation map is a flat { KEY: 'value', ... } shape. We don't pin
  // specific keys (they change with seed data), but we do require at
  // least one entry and that all values are strings.
  const keys = Object.keys(body);
  expect(keys.length, 'translation map should not be empty').toBeGreaterThan(0);
  for (const k of keys.slice(0, 10)) {
    expect(typeof body[k]).toBe('string');
  }
});

// ---------------------------------------------------------------------------
// Category: SYSTEM
// ---------------------------------------------------------------------------

test('contract:system POST /api/v1/systemlogs/page/0/20', async ({ request }) => {
  // ClubAdministrator / SystemAdministrator only.
  const res = await request.post(`${API_BASE}/api/v1/systemlogs/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/systemlogs/page');
});

test('contract:system GET /api/v1/settings returns array', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/settings`, { headers: authHeaders() });
  // Settings may be restricted by role; accept 200 or 403.
  expect([200, 403]).toContain(res.status());
  if (res.status() === 200) {
    const body = await res.json();
    expect(body !== null && typeof body === 'object').toBeTruthy();
  }
});

// ---------------------------------------------------------------------------
// Category: NEGATIVE CONTRACT (auth gating)
// ---------------------------------------------------------------------------

test('contract:negative request without bearer returns 401', async ({ request }) => {
  // Pick any authenticated endpoint and call it without the Authorization
  // header. The rewrite must keep the same behavior.
  const res = await request.get(`${API_BASE}/api/v1/users/my`);
  expect([401, 403]).toContain(res.status());
});

test('contract:negative request with bad bearer returns 401', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/users/my`, {
    headers: { Authorization: 'Bearer this-is-not-a-real-token' },
  });
  expect([401, 403]).toContain(res.status());
});
