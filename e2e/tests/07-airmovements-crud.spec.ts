// e2e/tests/07-airmovements-crud.spec.ts
//
// Plan row #07: Motor aircraft CRUD mirror of glider flights.
//
// /airmovements is the parallel surface for motor flights
// (FlightAircraftType = MotorFlight = 4). The Flight entity is shared with
// glider/tow flights, discriminated by FlightAircraftType; the same
// /api/v1/flights endpoints handle all three flavors (see SERVER.md #2 and
// flsweb/src/flights/airmovements/AirMovementsServices.js). The list view
// paginates via POST /api/v1/flights/motorflights/page/{start}/{size}.
//
// _test-fixture.sql does not seed a motor flight. This spec creates one via
// the API (same surface the form's $save hits), asserts the list row renders,
// then opens /airmovements/:id, edits FlightComment, and asserts via API.
// Delete is skipped (confirm() prompt + no testid on the delete button).
//
// Contract gaps (TODOs for a follow-up template pass, no shared files modified):
//   - new-flight "+" button (air-movements.html:11) has no testid.
//     TODO testid: data-testid="new-flight" on <button ng-click="newFlight()">.
//   - comment input id is "MotorFlightDetailsData.FlightComment"
//     (air-movement-edit-form.html:389) — dotted, we use input[id="..."].
//     TODO testid: data-testid="flight-comment-input".
//   - SAVE button has no testid (same gap as glider form).
//     TODO testid: data-testid="form-save".

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try { return JSON.parse(raw).access_token as string; } catch { return null; }
  });
  expect(token, 'expected access_token in sessionStorage from loggedInPage').toBeTruthy();
  return token!;
}

async function api<T>(page: Page, token: string, method: 'GET' | 'POST', url: string, body?: unknown): Promise<T> {
  const res = await page.request.fetch(`${API_BASE}${url}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: body !== undefined ? JSON.stringify(body) : undefined,
  });
  expect(res.ok(), `${method} ${url} -> ${res.status()} ${await res.text().catch(() => '')}`).toBeTruthy();
  return res.json() as Promise<T>;
}

test.describe.configure({ mode: 'serial' });

test('airmovements-list: renders /airmovements (empty or seeded)', async ({ loggedInPage, freshDb }) => {
  await gotoRoute(loggedInPage, '/airmovements');
  // The motor-flights list defaults its date filter to today..today. Seed has
  // no motor flight, so the list may legitimately render zero rows. Assert the
  // ng-table chrome is present (header row) instead of insisting on >=1 row.
  const table = loggedInPage.locator('table.fls').first();
  await expect(table).toBeVisible({ timeout: 10_000 });
  await screenshot(loggedInPage, '07-airmovements-crud-01');
});

test('airmovements-crud: API-create motor flight, UI-edit comment, API-readback', async ({ loggedInPage, freshDb }) => {
  const token = await getBearerToken(loggedInPage);

  // Look up the masterdata IDs the airmovements form would otherwise resolve
  // via Aircrafts.getMotorPlanes / Persons.getMotorPilots / FlightTypes.queryFlightTypesFor({dest:'motor'}).
  const motorAircraft = (await api<Array<{ AircraftId: string; Immatriculation: string }>>(
    loggedInPage, token, 'GET', '/api/v1/aircrafts/listitems/motoraircrafts'))[0];
  const motorPilot = (await api<Array<{ PersonId: string }>>(
    loggedInPage, token, 'GET', '/api/v1/persons/motorpilots/listitems/true'))[0];
  const motorFlightType = (await api<Array<{ FlightTypeId: string }>>(
    loggedInPage, token, 'GET', '/api/v1/flighttypes/motor'))[0];
  const lszk = (await api<Array<{ LocationId: string; IcaoCode: string }>>(
    loggedInPage, token, 'GET', '/api/v1/locations'))
      .find(l => l.IcaoCode === 'LSZK') ?? { LocationId: undefined as unknown as string };

  expect(motorAircraft?.AircraftId, 'seed must have at least one motoraircraft').toBeTruthy();
  expect(motorPilot?.PersonId, 'seed must have at least one motor pilot').toBeTruthy();
  expect(motorFlightType?.FlightTypeId, 'seed must have at least one motor flight type').toBeTruthy();
  expect(lszk.LocationId, 'seed must have LSZK location').toBeTruthy();

  // Build a minimal motor flight. AircraftId is the only [Required] field on
  // FlightDetailsData (FLS.Data.WebApi/Flight/FlightDetailsData.cs); we fill
  // the rest the way the form would (start/landing today, comment, locations).
  const today = new Date();
  const flightDate = today.toISOString().slice(0, 10);
  const start = `${flightDate}T10:00:00`;
  const end = `${flightDate}T10:30:00`;
  const initialComment = `e2e-airmove-create ${Date.now()}`;

  const created = await api<{ FlightId: string }>(loggedInPage, token, 'POST', '/api/v1/flights', {
    FlightDate: flightDate,
    StartType: 5, // Self-launch (DefaultStartType for motor flights)
    MotorFlightDetailsData: {
      AircraftId: motorAircraft.AircraftId,
      PilotPersonId: motorPilot.PersonId,
      FlightTypeId: motorFlightType.FlightTypeId,
      StartLocationId: lszk.LocationId,
      LdgLocationId: lszk.LocationId,
      StartDateTime: start,
      LdgDateTime: end,
      NrOfLdgs: 1,
      FlightComment: initialComment,
    },
  });

  expect(created.FlightId, 'POST /api/v1/flights should return the new FlightId').toBeTruthy();

  // Read back: confirm it's actually a motor flight.
  const readBack = await api<{ MotorFlightDetailsData: { FlightComment: string; AircraftId: string } | null }>(
    loggedInPage, token, 'GET', `/api/v1/flights/${created.FlightId}`);
  expect(readBack.MotorFlightDetailsData, 'created flight should have MotorFlightDetailsData (FlightAircraftType=4)').toBeTruthy();
  expect(readBack.MotorFlightDetailsData!.AircraftId).toBe(motorAircraft.AircraftId);

  // List assertion: the row appears in /airmovements (date filter defaults to today..today).
  await gotoRoute(loggedInPage, '/airmovements');
  const rows = loggedInPage.locator('tbody [data-testid="row"]');
  await expect.poll(async () => rows.count(), { timeout: 10_000 }).toBeGreaterThan(0);

  // Edit: open the form, change the comment, save.
  await gotoRoute(loggedInPage, `/airmovements/${created.FlightId}`);
  // Dotted id -> use attribute selector instead of CSS '#id' (would require escaping).
  const commentInput = loggedInPage.locator('input[id="MotorFlightDetailsData.FlightComment"]');
  await expect(commentInput).toBeVisible({ timeout: 10_000 });
  await expect(commentInput).toHaveValue(initialComment);

  const editedComment = `e2e-airmove-edit ${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  await commentInput.fill(editedComment);

  // No testid on the SAVE button (see contract gaps); match by role + text.
  const saveButton = loggedInPage
    .locator('button[type="submit"]')
    .filter({ hasText: /^\s*(Save|Speichern)\s*$/i })
    .first();
  await expect(saveButton).toBeEnabled();
  await saveButton.click();

  // AirMovementsController.save() -> $cancel() -> $location.path('/airmovements').
  await loggedInPage.waitForURL(/#\/airmovements$/, { timeout: 15_000 });
  await loggedInPage.waitForLoadState('domcontentloaded');

  // API readback proves the mutation persisted.
  const after = await api<{ MotorFlightDetailsData: { FlightComment: string } }>(
    loggedInPage, token, 'GET', `/api/v1/flights/${created.FlightId}`);
  expect(after.MotorFlightDetailsData.FlightComment, 'API readback should reflect the edited FlightComment')
    .toBe(editedComment);
  await screenshot(loggedInPage, '07-airmovements-crud-02');
});
