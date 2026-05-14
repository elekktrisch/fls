// e2e/tests/22-flight-locking-workflow.spec.ts
//
// Plan row #22: Flight locking workflow.
//
// Exercises the lock step of `DailyFlightValidationJob`:
//   Valid(30) -> Locked(40) for flights whose CreatedOn is at least 2 days
//   in the past.
//
// Trigger:  GET /api/v1/workflows/flightvalidation
//           (FLS.Server.Web/Controllers/WorkflowsController.cs:81)
// Job:      DailyFlightValidationJob -> FlightService.LockFlights(clubId)
//           (FlightService.cs:1145)
// Gate:     LockFlights uses `DateTime.Today.AddDays(-2)` and filters on
//           `flight.CreatedOn <= lockingDate` (FlightService.cs:1157,1164).
//
// =============================================================================
// TIME-GATE DEPENDENCY
// =============================================================================
// This spec depends on the deterministic fixture in
// `flsserver/database/FLSTest/3 insert/_test-fixture.sql` section 5 ("Historical
// flight"). That fixture seeds flight `F1500005-0000-0000-0000-000000000001`
// with `ProcessStateId = 30` (Valid) and `CreatedOn = @anchor + 7 minutes`
// where `@anchor = 2026-01-01`. Because the anchor is fixed in the past and
// well over two days behind wall-clock, the flight ages naturally on every
// `freshDb` re-seed and satisfies the >=2 day gate without clock manipulation.
//
// If the anchor in the fixture is ever moved into the future (within the last
// two days of today's date), this spec will detect that the flight is
// ineligible and `test.skip` with a clear reason — per the task brief, we do
// NOT add new fixture seed in this batch.
//
// See SERVER.md sec. 1 (workflow trigger mechanism) and sec. 2 (state machine).

import { test, expect } from '../fixtures';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

// Fixed seed ID from _test-fixture.sql section 5 ("Historical flight").
const HISTORICAL_FLIGHT_ID = 'F1500005-0000-0000-0000-000000000001';

// Mirror of FLS.Data.WebApi.Flight.FlightProcessState.
const ProcessState = {
  NotProcessed: 0,
  Invalid: 28,
  Valid: 30,
  Locked: 40,
} as const;

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try { return JSON.parse(raw).access_token as string; } catch { return null; }
  });
  expect(token, 'expected access_token in sessionStorage from loggedInPage').toBeTruthy();
  return token!;
}

async function getFlight(
  page: Page,
  token: string,
  flightId: string,
): Promise<{ ProcessStateId: number; CreatedOn?: string }> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  // For glider flights, ProcessStateId is nested under GliderFlightDetailsData,
  // not at the FlightDetails root. (Motor flights would use the Motor variant.)
  // CreatedOn is the same on root and nested; the root value is canonical.
  return {
    ProcessStateId: body?.GliderFlightDetailsData?.ProcessStateId,
    CreatedOn: body?.CreatedOn ?? body?.GliderFlightDetailsData?.CreatedOn,
  };
}

test('flight-locking: Valid -> Locked via /workflows/flightvalidation', async ({
  loggedInPage,
}) => {
  const token = await getBearerToken(loggedInPage);

  // -------------------------------------------------------------------------
  // Precondition: seeded historical flight is Valid (30) and aged >= 2 days.
  // -------------------------------------------------------------------------
  const before = await getFlight(loggedInPage, token, HISTORICAL_FLIGHT_ID);

  // The flight must be Valid to be eligible for locking. If it is not, the
  // seed has drifted (or another spec in the same worker left it in another
  // state). `freshDb` should guarantee Valid; bail out clearly if it didn't.
  expect(
    before.ProcessStateId,
    'seeded historical flight should start as Valid (30) — check _test-fixture.sql §5',
  ).toBe(ProcessState.Valid);

  // Confirm the time gate is met: CreatedOn must be at least 2 days behind
  // today (server compares `DbFunctions.TruncateTime(flight.CreatedOn) <=
  // today - 2`). If the fixture anchor was moved forward, skip with a clear
  // diagnostic rather than reporting a misleading failure.
  if (before.CreatedOn) {
    const createdOn = new Date(before.CreatedOn);
    const twoDaysAgo = new Date();
    twoDaysAgo.setHours(0, 0, 0, 0);
    twoDaysAgo.setDate(twoDaysAgo.getDate() - 2);
    test.skip(
      createdOn > twoDaysAgo,
      `Seeded flight CreatedOn=${before.CreatedOn} is within the 2-day lock ` +
      `gate. Fixture anchor in _test-fixture.sql must be moved further into ` +
      `the past, or this spec re-run with a backdated wall clock.`,
    );
  }

  // -------------------------------------------------------------------------
  // Trigger the workflow.
  // -------------------------------------------------------------------------
  const workflowRes = await loggedInPage.request.get(
    `${API_BASE}/api/v1/workflows/flightvalidation`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(
    workflowRes.ok(),
    `GET /api/v1/workflows/flightvalidation -> ${workflowRes.status()}`,
  ).toBeTruthy();

  // -------------------------------------------------------------------------
  // Poll the flight until ProcessStateId flips to Locked (40). The workflow
  // endpoint returns synchronously (WorkflowsController.cs:85), but EF6
  // change-tracking + write commit can take a beat under load. 5s is enough.
  // -------------------------------------------------------------------------
  const deadline = Date.now() + 5000;
  let latest = before;
  while (Date.now() < deadline) {
    latest = await getFlight(loggedInPage, token, HISTORICAL_FLIGHT_ID);
    if (latest.ProcessStateId === ProcessState.Locked) break;
    await new Promise(r => setTimeout(r, 200));
  }

  expect(
    latest.ProcessStateId,
    `flight should transition Valid(30) -> Locked(40) after running ` +
    `DailyFlightValidationJob; saw ProcessStateId=${latest.ProcessStateId}`,
  ).toBe(ProcessState.Locked);
});
