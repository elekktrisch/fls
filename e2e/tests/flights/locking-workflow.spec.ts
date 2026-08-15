import { test, expect } from "../../fixtures";
import type { Page } from "@playwright/test";

import { testId } from "../../test-id";
import { ensureGliderFlight, getBearerToken } from "../../test-data";

const API_BASE = process.env.FLS_API ?? "http://localhost:25567";

const ProcessState = {
  NotProcessed: 0,
  Invalid: 28,
  Valid: 30,
  Locked: 40,
} as const;

async function getFlight(
  page: Page,
  token: string,
  flightId: string,
): Promise<{ ProcessStateId: number; CreatedOn?: string }> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let body: any;
  await expect(async () => {
    const res = await page.request.get(
      `${API_BASE}/api/v1/flights/${flightId}`,
      {
        headers: { Authorization: `Bearer ${token}` },
        timeout: 30_000,
      },
    );
    expect(
      res.ok(),
      `GET /api/v1/flights/${flightId} -> ${res.status()}`,
    ).toBeTruthy();
    body = await res.json();
  }).toPass({ timeout: 60_000 });
  return {
    ProcessStateId: body?.GliderFlightDetailsData?.ProcessStateId,
    CreatedOn: body?.CreatedOn ?? body?.GliderFlightDetailsData?.CreatedOn,
  };
}

test("flight-locking: Valid -> Locked via /workflows/flightvalidation", async ({
  loggedInPage,
}, testInfo) => {
  const id = testId(testInfo);
  const token = await getBearerToken(loggedInPage);
  const { flightId: ownedFlightId } = await ensureGliderFlight(
    loggedInPage.request,
    token,
    {
      comment: id.name,
      processStateId: ProcessState.Valid,
      createdOnDaysAgo: 3,
    },
  );

  const before = await getFlight(loggedInPage, token, ownedFlightId);

  expect(before.ProcessStateId, "test flight should start as Valid (30)").toBe(
    ProcessState.Valid,
  );

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

  const workflowRes = await loggedInPage.request.get(
    `${API_BASE}/api/v1/workflows/flightvalidation`,
    { headers: { Authorization: `Bearer ${token}` }, timeout: 30_000 },
  );
  expect(
    workflowRes.ok(),
    `GET /api/v1/workflows/flightvalidation -> ${workflowRes.status()}`,
  ).toBeTruthy();

  const deadline = Date.now() + 5000;
  let latest = before;
  while (Date.now() < deadline) {
    latest = await getFlight(loggedInPage, token, ownedFlightId);
    if (latest.ProcessStateId === ProcessState.Locked) break;
    await new Promise((r) => setTimeout(r, 200));
  }

  expect(
    latest.ProcessStateId,
    `flight should transition Valid(30) -> Locked(40) after running ` +
      `DailyFlightValidationJob; saw ProcessStateId=${latest.ProcessStateId}`,
  ).toBe(ProcessState.Locked);
});
