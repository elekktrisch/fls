
import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { ensureGliderFlight } from '../../test-data';
import type { Page, APIResponse } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const POLL_TIMEOUT_MS = 15_000;
const POLL_INTERVAL_MS = 500;

type AuditLogOverview = {
  AuditLogId: number;
  EventDateTime: string;
  UserName: string;
  EventTypeName: string;
  EntityName: string;
  RecordId: string;
  PropertyChanges: Array<{
    PropertyName?: string;
    OriginalValue?: string;
    NewValue?: string;
  }>;
};

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try { return JSON.parse(raw).access_token as string; } catch { return null; }
  });
  expect(token, 'expected access_token in sessionStorage from loggedInPage').toBeTruthy();
  return token!;
}

async function fetchAuditLogs(page: Page, token: string, flightId: string): Promise<AuditLogOverview[]> {
  const res: APIResponse = await page.request.get(
    `${API_BASE}/api/v1/auditlogs/Flight/${flightId}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(res.ok(), `GET /api/v1/auditlogs/Flight/${flightId} -> ${res.status()}`).toBeTruthy();
  return (await res.json()) as AuditLogOverview[];
}

async function readFlight(page: Page, token: string, flightId: string): Promise<any> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  return await res.json();
}

test('audit-logs: PUT flight produces an audit-log entry visible via API', async ({
  loggedInPage,
}, testInfo) => {
  const id = testId(testInfo);
  await gotoRoute(loggedInPage, '/flights');
  const token = await getBearerToken(loggedInPage);
  const { flightId: FLIGHT_ID } = await ensureGliderFlight(loggedInPage.request, token, {
    comment: id.name,
  });

  const before = await fetchAuditLogs(loggedInPage, token, FLIGHT_ID);
  const baselineCount = before.length;
  const baselineIds = new Set(before.map(x => x.AuditLogId));

  const flight = await readFlight(loggedInPage, token, FLIGHT_ID);
  const newComment = `e2e-audit ${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  flight.GliderFlightDetailsData.FlightComment = newComment;
  const mutatedAt = new Date();

  const putRes = await loggedInPage.request.put(
    `${API_BASE}/api/v1/flights/${FLIGHT_ID}`,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: flight,
    },
  );
  expect(putRes.ok(), `PUT /api/v1/flights/${FLIGHT_ID} -> ${putRes.status()}`).toBeTruthy();

  let after: AuditLogOverview[] = [];
  let newEntries: AuditLogOverview[] = [];
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  while (Date.now() < deadline) {
    after = await fetchAuditLogs(loggedInPage, token, FLIGHT_ID);
    newEntries = after.filter(x => !baselineIds.has(x.AuditLogId));
    if (newEntries.length > 0) break;
    await loggedInPage.waitForTimeout(POLL_INTERVAL_MS);
  }

  expect(
    after.length,
    `audit-log entry count should grow after PUT (baseline=${baselineCount}, after=${after.length})`,
  ).toBeGreaterThan(baselineCount);
  expect(newEntries.length, 'expected at least one new audit entry').toBeGreaterThan(0);

  const latest = newEntries[0]!;
  expect(latest.EntityName).toMatch(/Flight/i);
  expect(latest.RecordId.toLowerCase()).toBe(FLIGHT_ID.toLowerCase());

  const eventTs = new Date(latest.EventDateTime).getTime();
  expect(eventTs).toBeGreaterThan(mutatedAt.getTime() - 60_000);

  const sawComment = newEntries.some(e =>
    (e.PropertyChanges ?? []).some(p =>
      (p.NewValue ?? '').includes(newComment) ||
      (p.OriginalValue ?? '').includes(newComment)),
  );
  test.info().annotations.push({
    type: 'audit',
    description: `new audit entries=${newEntries.length}, FlightComment captured=${sawComment}`,
  });

  await gotoRoute(loggedInPage, '/flights');
  const historyLinks = loggedInPage.locator('tbody [data-testid="row"] a.history-link');
  await expect(historyLinks.first()).toBeVisible({ timeout: 10_000 });
  await screenshot(loggedInPage, 'audit-logs-01');
});
