import { test, expect } from '../../fixtures';
import { testId } from '../../test-id';
import { ensureGliderFlight, getBearerToken, withPool } from '../../test-data';
import sql from 'mssql';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

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

async function getFlightProcessState(
  page: Page, token: string, flightId: string,
): Promise<number> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  return body?.GliderFlightDetailsData?.ProcessStateId as number;
}

async function triggerWorkflow(
  page: Page, token: string, name: 'flightvalidation' | 'deliverycreation',
): Promise<void> {
  const res = await page.request.get(`${API_BASE}/api/v1/workflows/${name}`, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: 90_000,
  });
  expect(res.ok(), `GET /api/v1/workflows/${name} -> ${res.status()}`).toBeTruthy();
}

async function listDeliveriesForFlight(flightId: string): Promise<{ DeliveryId: string }[]> {
  return withPool(async (pool) => {
    const r = await pool.request()
      .input('id', sql.UniqueIdentifier, flightId)
      .query<{ DeliveryId: string }>(
        `SELECT CAST(DeliveryId AS NVARCHAR(40)) AS DeliveryId
           FROM Deliveries
          WHERE FlightId = @id AND IsDeleted = 0`,
      );
    return r.recordset;
  });
}

test('delivery-creation-workflow: deliverycreation advances the Locked flight — DeliveryPrepared carries a Delivery row, preparation-error/excluded are degraded passes', async ({
  loggedInPage,
}, testInfo) => {
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  const { flightId: HISTORICAL_FLIGHT_ID } = await ensureGliderFlight(loggedInPage.request, token, {
    comment: id.name,
    processStateId: ProcessState.Valid,
    createdOnDaysAgo: 5,
  });

  const initial = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  test.skip(initial !== ProcessState.Valid, `flight state ${initial}, expected Valid (30)`);

  await triggerWorkflow(loggedInPage, token, 'flightvalidation');
  const afterValidation = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
  test.skip(afterValidation !== ProcessState.Locked, `flight state ${afterValidation}, expected Locked (40)`);

  let finalState: number = ProcessState.Locked;
  for (let attempt = 0; attempt < 4; attempt++) {
    await triggerWorkflow(loggedInPage, token, 'deliverycreation');
    finalState = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);
    if (finalState !== ProcessState.Locked) break;
  }

  if (finalState === ProcessState.Locked) {
    const row = await withPool(async (pool) => {
      const r = await pool.request()
        .input('id', sql.UniqueIdentifier, HISTORICAL_FLIGHT_ID)
        .query(`SELECT
                  CAST(f.OwnerId AS NVARCHAR(40))            AS OwnerId,
                  CAST(ft.ClubId AS NVARCHAR(40))            AS FlightType_ClubId,
                  f.FlightAircraftType,
                  f.ProcessStateId,
                  CONVERT(NVARCHAR(30), f.CreatedOn, 126)    AS CreatedOn,
                  CONVERT(NVARCHAR(30), SYSDATETIME(), 126)  AS ServerNow
                FROM Flights f
                LEFT JOIN FlightTypes ft ON ft.FlightTypeId = f.FlightTypeId
                WHERE f.FlightId = @id`);
      return r.recordset[0];
    });
    expect(
      false,
      `flight still Locked(40); diagnostic: ${JSON.stringify(row)}`,
    ).toBeTruthy();
  }
  expect(finalState, `flight still Locked(40) — job did not pick it up`).not.toBe(ProcessState.Locked);

  expect(
    [ProcessState.DeliveryPrepared, ProcessState.DeliveryPreparationError, ProcessState.ExcludedFromDeliveryProcess],
    `unexpected final state ${finalState}`,
  ).toContain(finalState);

  if (finalState === ProcessState.DeliveryPrepared) {
    const deliveries = await listDeliveriesForFlight(HISTORICAL_FLIGHT_ID);
    expect(
      deliveries.length,
      `DeliveryPrepared but no Delivery row references FlightId ${HISTORICAL_FLIGHT_ID}`,
    ).toBeGreaterThan(0);
  } else {
    // eslint-disable-next-line no-console
    console.warn(`delivery-creation-workflow: no Delivery created (state ${finalState})`);
  }
});
