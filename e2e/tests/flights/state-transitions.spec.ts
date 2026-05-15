// Spec #06: flight process-state transitions, driven via API.
//   (a) Valid → ExcludedFromDeliveryProcess → Valid via PUT changeprocessstate.
//   (b) Invalid → Valid via POST /api/v1/flights/validate.
//
// Each test owns its own flight via ensureGliderFlight + a stable testId.

import { expect, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { API_BASE, ensureGliderFlight, getBearerToken as sharedGetToken, withPool as sharedWithPool } from '../../test-data';
import type { Page } from '@playwright/test';
import sql from 'mssql';

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

const getBearerToken = sharedGetToken;
const withPool = sharedWithPool;

async function getFlightProcessState(page: Page, token: string, flightId: string): Promise<number> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  // FlightDetails nests ProcessStateId under GliderFlightDetailsData (or
  // MotorFlightDetailsData), not at the root.
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

test('flights-state: Valid -> ExcludedFromDeliveryProcess -> Valid (toggle)', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  const { flightId: HISTORICAL_FLIGHT_ID } = await ensureGliderFlight(loggedInPage.request, token, {
    comment: id.name,
    processStateId: ProcessState.Valid,
  });

  // Sanity-check the precondition: the test's flight starts as Valid (30).
  const initial = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(initial, 'test flight should start as Valid (30)').toBe(ProcessState.Valid);

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
  await screenshot(loggedInPage, 'state-transitions-01');
});

test('flights-state: Invalid -> Valid via /api/v1/flights/validate', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  const { flightId: HISTORICAL_FLIGHT_ID } = await ensureGliderFlight(loggedInPage.request, token, {
    comment: id.name,
    processStateId: ProcessState.Invalid,
  });

  // ValidateFlights only revisits Invalid rows where ModifiedOn >= ValidatedOn.
  // Set ValidatedOn = yesterday, ModifiedOn = now so the row is eligible.
  await withPool(async pool => {
    const r = await pool.request()
      .input('id', sql.UniqueIdentifier, HISTORICAL_FLIGHT_ID)
      .input('invalidState', sql.Int, ProcessState.Invalid)
      .query(`UPDATE Flights
                 SET ProcessStateId = @invalidState,
                     ValidatedOn = DATEADD(DAY, -1, SYSDATETIME()),
                     ModifiedOn = SYSDATETIME()
               WHERE FlightId = @id`);
    expect(r.rowsAffected[0], 'expected to flip 1 flight row to Invalid').toBe(1);
  });

  const precondition = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(precondition, 'flight should now be Invalid (28)').toBe(ProcessState.Invalid);

  // Validate scans every club flight — see TEST_WRITING.md §3 on workflow timeouts.
  const res = await loggedInPage.request.post(`${API_BASE}/api/v1/flights/validate`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {},
    timeout: 60_000,
  });
  expect(res.ok(), `POST /api/v1/flights/validate -> ${res.status()}`).toBeTruthy();

  const finalState = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  expect(finalState, 'revalidated flight should be Valid (30)').toBe(ProcessState.Valid);
  await screenshot(loggedInPage, 'state-transitions-02');
});
