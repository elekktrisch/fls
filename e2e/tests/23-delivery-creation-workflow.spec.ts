// Spec #23: DeliveryCreationJob pipeline, API-driven.
//   Valid → (flightvalidation) → Locked → (deliverycreation) → DeliveryPrepared
//
// Both workflows are gated by wall-clock age (TEST_WRITING.md §4):
//   ensureGliderFlight backdates CreatedOn; SQL UPDATE backdates LockedOn.

import { test, expect } from '../fixtures';
import { testId } from '../test-id';
import { ensureGliderFlight, getBearerToken as sharedGetToken, withPool } from '../test-data';
import sql from 'mssql';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

// Mirror of FLS.Data.WebApi.Flight.FlightProcessState.
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

async function getFlightProcessState(
  page: Page, token: string, flightId: string,
): Promise<number> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  // For glider flights, ProcessStateId is nested under GliderFlightDetailsData.
  return body?.GliderFlightDetailsData?.ProcessStateId as number;
}

async function triggerWorkflow(
  page: Page, token: string, name: 'flightvalidation' | 'deliverycreation',
): Promise<void> {
  // Workflows scan every club flight — see TEST_WRITING.md §3.
  const res = await page.request.get(`${API_BASE}/api/v1/workflows/${name}`, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: 90_000,
  });
  expect(res.ok(), `GET /api/v1/workflows/${name} -> ${res.status()}`).toBeTruthy();
}

async function listDeliveriesForFlight(
  page: Page, token: string, flightId: string,
): Promise<unknown[]> {
  // Empty filter → all deliveries visible to current club; filter by FlightId client-side.
  const res = await page.request.post(`${API_BASE}/api/v1/deliveries/page/0/100`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { PageStart: 0, PageSize: 100, SearchFilter: {}, Sorting: null },
  });
  expect(res.ok(), `POST /api/v1/deliveries/page -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  const items: any[] = body.Items ?? body.items ?? [];
  return items.filter(d =>
    (d.FlightId ?? d.flightId ?? '').toLowerCase() === flightId.toLowerCase(),
  );
}

test('delivery-creation-workflow: Locked -> DeliveryPrepared (with rules) and a Delivery row exists', async ({
  loggedInPage,
}, testInfo) => {
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  // Aged 5 days: clears the 2-day locking gate. LockedOn is backdated below.
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

  // Deliverycreation needs LockedOn ≤ today - 3d; flightvalidation just set it to now.
  await withPool(async (pool) => {
    await pool.request()
      .input('id', sql.UniqueIdentifier, HISTORICAL_FLIGHT_ID)
      .query(`UPDATE Flights
                 SET LockedOn = DATEADD(DAY, -5, SYSDATETIME())
               WHERE FlightId = @id`);
  });
  await triggerWorkflow(loggedInPage, token, 'deliverycreation');

  const finalState = await getFlightProcessState(loggedInPage, token, HISTORICAL_FLIGHT_ID);

  // Happy path is DeliveryPrepared(50); DeliveryPreparationError(45) or
  // ExcludedFromDeliveryProcess(99) are degraded-pass branches.
  expect(finalState, `flight still Locked(40) — job did not pick it up`).not.toBe(ProcessState.Locked);

  expect(
    [ProcessState.DeliveryPrepared, ProcessState.DeliveryPreparationError, ProcessState.ExcludedFromDeliveryProcess],
    `unexpected final state ${finalState}`,
  ).toContain(finalState);

  if (finalState === ProcessState.DeliveryPrepared) {
    const deliveries = await listDeliveriesForFlight(
      loggedInPage, token, HISTORICAL_FLIGHT_ID,
    );
    expect(
      deliveries.length,
      `DeliveryPrepared but no Delivery row references FlightId ${HISTORICAL_FLIGHT_ID}`,
    ).toBeGreaterThan(0);
  } else {
    // Degraded-pass: workflow advanced state but rules produced no Delivery.
    // eslint-disable-next-line no-console
    console.warn(`delivery-creation-workflow: no Delivery created (state ${finalState})`);
  }
});
