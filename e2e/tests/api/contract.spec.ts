
import { test, expect, APIRequestContext } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';

let token = '';
let myClubId = '';

const EMPTY_PAGE_BODY = { Sorting: {}, SearchFilter: {} };


function authHeaders(): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

function expectObject(value: unknown, label: string): asserts value is Record<string, unknown> {
  expect(value, `${label} should be a non-null object`).toBeTruthy();
  expect(typeof value, `${label} should be typeof object`).toBe('object');
  expect(Array.isArray(value), `${label} should not be an array`).toBe(false);
}

function expectArray(value: unknown, label: string): asserts value is unknown[] {
  expect(Array.isArray(value), `${label} should be an array`).toBe(true);
}

function expectKeys(value: Record<string, unknown>, keys: readonly string[], label: string): void {
  for (const key of keys) {
    expect(value, `${label} missing key '${key}'`).toHaveProperty(key);
  }
}

function expectPagedEnvelope(body: unknown, label: string): Record<string, unknown> {
  expectObject(body, label);
  expectKeys(body, ['Items', 'TotalRows', 'PageStart', 'PageSize'] as const, label);
  expectArray((body as Record<string, unknown>).Items, `${label}.Items`);
  expect(typeof (body as Record<string, unknown>).TotalRows).toBe('number');
  return body as Record<string, unknown>;
}

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


test.beforeAll(async ({ playwright }) => {
  const ctx = await playwright.request.newContext();
  try {
    await authenticate(ctx);
  } finally {
    await ctx.dispose();
  }
  expect(token, 'bearer token should be populated by /Token').toBeTruthy();
});


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
  expectKeys(body, ['UserId', 'UserName', 'ClubId'] as const, '/users/my');
  expect(typeof body.ClubId).toBe('string');
  expect(body.UserName).toBeTruthy();
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
  if (body !== null) {
    expectObject(body, '/persons/my');
    expectKeys(body, ['PersonId'] as const, '/persons/my');
  }
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


test('contract:clubs GET /api/v1/clubs/my returns the user club', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/clubs/my`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/clubs/my');
  expectKeys(body, ['ClubId', 'ClubName', 'ClubKey'] as const, '/clubs/my');
  if (myClubId) {
    expect(body.ClubId).toBe(myClubId);
  }
});

test('contract:clubs POST /api/v1/clubs/page/0/20 returns paged envelope', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/clubs/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectPagedEnvelope(body, '/clubs/page');
});


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
      ['FlightId', 'FlightDate', 'ProcessState'] as const,
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


test('contract:users GET /api/v1/users/overview/club', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/users/overview/club`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectArray(body, '/users/overview/club');
  if (body.length) {
    const first = body[0] as Record<string, unknown>;
    expectKeys(first, ['UserId', 'UserName'] as const, 'user overview item');
    expectKeys(first, ['ClubName'] as const, 'user overview item ClubName');
  }
});

test('contract:users POST /api/v1/users/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/users/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/users/page');
});


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


test('contract:planning POST /api/v1/planningdays/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/planningdays/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/planningdays/page');
});


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


test('contract:reports POST /api/v1/flightreports/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/flightreports/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/flightreports/page');
  expectKeys(body, ['Flights', 'FlightReportFilterCriteria'] as const, '/flightreports/page');
  expectPagedEnvelope(body.Flights, '/flightreports/page Flights');
});


test('contract:masterdata GET /api/v1/countries', async ({ request }) => {
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
  const res = await request.post(`${API_BASE}/api/v1/accountingrulefilters/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect(res.ok()).toBeTruthy();
  expectPagedEnvelope(await res.json(), '/accountingrulefilters/page');
});


test('contract:deliveries POST /api/v1/deliveries/page/0/20', async ({ request }) => {
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


test('contract:audit GET /api/v1/dashboards returns dashboard payload', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/dashboards`, { headers: authHeaders() });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expect(body === null || typeof body === 'object').toBeTruthy();
});


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


test('contract:translations GET /api/v1/translations?lang=de returns map', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/translations?lang=de`);
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expectObject(body, '/translations?lang=de');
  const keys = Object.keys(body);
  expect(keys.length, 'translation map should not be empty').toBeGreaterThan(0);
  for (const k of keys.slice(0, 10)) {
    expect(typeof body[k]).toBe('string');
  }
});


test('contract:system POST /api/v1/systemlogs/page/0/20', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/systemlogs/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect([200, 401, 403]).toContain(res.status());
  if (res.status() === 200) {
    expectPagedEnvelope(await res.json(), '/systemlogs/page');
  }
});

test('contract:system POST /api/v1/settings/page/0/20 returns paged envelope', async ({ request }) => {
  const res = await request.post(`${API_BASE}/api/v1/settings/page/0/20`, {
    headers: authHeaders(),
    data: EMPTY_PAGE_BODY,
  });
  expect([200, 401, 403]).toContain(res.status());
  if (res.status() === 200) {
    expectPagedEnvelope(await res.json(), '/settings/page');
  }
});


test('contract:negative request without bearer returns 401', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/users/my`);
  expect([401, 403]).toContain(res.status());
});

test('contract:negative request with bad bearer returns 401', async ({ request }) => {
  const res = await request.get(`${API_BASE}/api/v1/users/my`, {
    headers: { Authorization: 'Bearer this-is-not-a-real-token' },
  });
  expect([401, 403]).toContain(res.status());
});
