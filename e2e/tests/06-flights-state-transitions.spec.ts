// e2e/tests/06-flights-state-transitions.spec.ts
//
// Plan row #06: Flight process-state transitions.
//   (a) Exclude-from-delivery toggle on a Valid flight: Valid (30) ->
//       ExcludedFromDeliveryProcess (99) -> Valid (30).
//   (b) Revalidate an Invalid flight: Invalid (28) -> Valid (30) via the
//       /api/v1/flights/validate endpoint.
//
// UI-vs-API choice: API-driven.
//   - The `ManuallySetFlightProcessState` transition (server: FlightService.cs:1368)
//     is reached from the AngularJS client only indirectly via UI flows that
//     don't have stable testid markers, and the legal transitions are a small
//     server-enforced state machine that's far cleaner to drive with PUT
//     /api/v1/flights/changeprocessstate/{id}.
//   - For (b), the only legitimate Invalid -> Valid path is POST
//     /api/v1/flights/validate (FlightsController.cs:271). The client's
//     `$scope.validateFlights` (FlightsController.js:824) just confirms +
//     POSTs that same URL, so an API call exercises the same code path
//     without needing a confirm() shim.
//
// Test setup:
//   - The deterministic fixture seeds a historical glider flight with
//     ProcessStateId = 30 (Valid) at the well-known ID
//     F1500005-0000-0000-0000-000000000001 (see
//     flsserver/database/FLSTest/3 insert/_test-fixture.sql section 5).
//   - For test (a) we use that flight as-is.
//   - For test (b) we first flip its ProcessStateId to 28 (Invalid) via a
//     direct SQL write, and set ModifiedOn > ValidatedOn so the server-side
//     ValidateFlights loop (FlightService.cs:909) picks it up. The flight
//     fixture is otherwise valid (proper aircraft + pilot + locations), so
//     re-validation should land it back on 30 (Valid).
//
// Contract gaps (none introduced; not modifying shared infra):
//   - There is no UI testid for the "exclude from delivery" toggle or for
//     the "Validate flights" button. If a future spec wants the UI path,
//     `data-testid="validate-flights-button"` on flights.html and
//     `data-testid="exclude-from-delivery-toggle"` on the flight-edit form
//     would be the natural additions.

import { expect, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';
import sql from 'mssql';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

// Fixed seed ID from _test-fixture.sql (section 5: "Historical flight").
const HISTORICAL_FLIGHT_ID = 'F1500005-0000-0000-0000-000000000001';

// FlightProcessState enum values (mirror of FLS.Data.WebApi.Flight.FlightProcessState
// + FlightsServices.js constants).
const ProcessState = {
  NotProcessed: 0,
  Invalid: 28,
  Valid: 30,
  Locked: 40,
  DeliveryPreparationError: 45,
  DeliveryPrepared: 50,
  DeliveryBooked: 60,
  ExcludedFromDeliveryProcess: 99,
} as const;

const MSSQL_CONFIG: sql.config = {
  user: 'sa',
  password: 'Demo#FLS#2026',
  server: 'localhost',
  port: 1433,
  database: 'FLSTest',
  options: { trustServerCertificate: true, encrypt: false },
  pool: { max: 2, min: 0, idleTimeoutMillis: 5000 },
};

async function withPool<T>(fn: (pool: sql.ConnectionPool) => Promise<T>): Promise<T> {
  const pool = await new sql.ConnectionPool(MSSQL_CONFIG).connect();
  try {
    return await fn(pool);
  } finally {
    await pool.close();
  }
}

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try { return JSON.parse(raw).access_token as string; } catch { return null; }
  });
  expect(token, 'expected access_token in sessionStorage from loggedInPage').toBeTruthy();
  return token!;
}

async function getFlightProcessState(page: Page, token: string, flightId: string): Promise<number> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  // For glider flights, FlightDetails nests ProcessStateId inside
  // GliderFlightDetailsData. (Motor flights would use MotorFlightDetailsData;
  // the historical fixture is a glider so we read the glider path.) The
  // FlightDetails DTO does NOT expose ProcessStateId at the root.
  return body?.GliderFlightDetailsData?.ProcessStateId as number;
}

async function changeProcessState(
  page: Page,
  token: string,
  flightId: string,
  newState: number,
): Promise<number> {
  const res = await page.request.put(
    `${API_BASE}/api/v1/flights/changeprocessstate/${flightId}`,
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { FlightId: flightId, NewFlightProcessState: newState },
    },
  );
  return res.status();
}

test.describe.configure({ mode: 'serial' });

test('flights-state: Valid -> ExcludedFromDeliveryProcess -> Valid (toggle)', async ({ freshLoggedInPage: loggedInPage }) => {
  const token = await getBearerToken(loggedInPage);

  // Sanity-check the precondition: the seeded historical flight is Valid (30).
  const initial = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(initial, 'seeded historical flight should start as Valid (30)').toBe(ProcessState.Valid);

  // Transition Valid -> ExcludedFromDeliveryProcess.
  const excludeStatus = await changeProcessState(
    loggedInPage, token, HISTORICAL_FLIGHT_ID, ProcessState.ExcludedFromDeliveryProcess,
  );
  expect(excludeStatus, 'PUT changeprocessstate -> ExcludedFromDeliveryProcess should 2xx').toBeLessThan(300);

  const afterExclude = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(afterExclude).toBe(ProcessState.ExcludedFromDeliveryProcess);

  // Transition back: ExcludedFromDeliveryProcess -> Valid.
  const includeStatus = await changeProcessState(
    loggedInPage, token, HISTORICAL_FLIGHT_ID, ProcessState.Valid,
  );
  expect(includeStatus, 'PUT changeprocessstate -> Valid should 2xx').toBeLessThan(300);

  const afterInclude = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(afterInclude).toBe(ProcessState.Valid);
  await screenshot(loggedInPage, '06-flights-state-transitions-01');
});

test('flights-state: Invalid -> Valid via /api/v1/flights/validate', async ({ freshLoggedInPage: loggedInPage }) => {
  const token = await getBearerToken(loggedInPage);

  // Force the historical flight into Invalid state. The server's ValidateFlights
  // loop only revisits Invalid rows where ModifiedOn >= ValidatedOn
  // (FlightService.cs:924-930), so we stamp ModifiedOn into the future relative
  // to ValidatedOn (or NULL out ValidatedOn) to make it eligible.
  await withPool(async pool => {
    const r = await pool.request()
      .input('id', sql.UniqueIdentifier, HISTORICAL_FLIGHT_ID)
      .input('invalidState', sql.Int, ProcessState.Invalid)
      .query(`UPDATE Flights
                 SET ProcessStateId = @invalidState,
                     ValidatedOn = NULL,
                     ModifiedOn = SYSDATETIME()
               WHERE FlightId = @id`);
    expect(r.rowsAffected[0], 'expected to flip 1 flight row to Invalid').toBe(1);
  });

  const precondition = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(precondition, 'flight should now be Invalid (28)').toBe(ProcessState.Invalid);

  // Trigger validation for the current user's club (TestClub).
  const res = await loggedInPage.request.post(`${API_BASE}/api/v1/flights/validate`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {},
  });
  expect(res.ok(), `POST /api/v1/flights/validate -> ${res.status()}`).toBeTruthy();

  // The flight is otherwise well-formed (aircraft, pilot, locations all set
  // in the fixture), so revalidation should land it on Valid (30).
  const finalState = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(finalState, 'revalidated flight should be Valid (30)').toBe(ProcessState.Valid);
  await screenshot(loggedInPage, '06-flights-state-transitions-02');
});
