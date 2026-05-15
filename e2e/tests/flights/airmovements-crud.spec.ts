// Spec #07: motor-flight CRUD mirror of glider flights. /airmovements drives
// FlightAircraftType=4 via the same /api/v1/flights endpoints.
//
// Contract gaps (TODO testid): `new-flight`, `flight-comment-input`, `form-save`.

import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { API_BASE, getBearerToken, withPool } from '../../test-data';
import sql from 'mssql';
import type { Page } from '@playwright/test';

async function api<T>(page: Page, token: string, method: 'GET' | 'POST', url: string, body?: unknown): Promise<T> {
  const res = await page.request.fetch(`${API_BASE}${url}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: body !== undefined ? JSON.stringify(body) : undefined,
  });
  expect(res.ok(), `${method} ${url} -> ${res.status()} ${await res.text().catch(() => '')}`).toBeTruthy();
  return res.json() as Promise<T>;
}

test('airmovements-list: renders /airmovements (empty or seeded)', async ({ loggedInPage }) => {
  await gotoRoute(loggedInPage, '/airmovements');
  // Default date filter is today..today; assert table chrome, not row count.
  const table = loggedInPage.locator('table.fls').first();
  await expect(table).toBeVisible({ timeout: 10_000 });
  await screenshot(loggedInPage, 'airmovements-crud-01');
});

test('airmovements-crud: API-create motor flight, UI-edit comment, API-readback', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const initialComment = `${id.name} create`;
  const editedComment = `${id.name} edit`;

  // Pre-clean prior runs.
  await withPool(async (pool) => {
    await pool.request()
      .input('c1', sql.NVarChar, initialComment)
      .input('c2', sql.NVarChar, editedComment)
      .query('DELETE FROM Flights WHERE Comment IN (@c1, @c2)');
  });

  const token = await getBearerToken(loggedInPage);

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

  const today = new Date();
  const flightDate = today.toISOString().slice(0, 10);
  const start = `${flightDate}T10:00:00`;
  const end = `${flightDate}T10:30:00`;

  const created = await api<{ FlightId: string }>(loggedInPage, token, 'POST', '/api/v1/flights', {
    FlightDate: flightDate,
    StartType: 5, // Self-launch (DefaultStartType for motor flights)
    Comment: initialComment,
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

  const readBack = await api<{ MotorFlightDetailsData: { FlightComment: string; AircraftId: string } | null }>(
    loggedInPage, token, 'GET', `/api/v1/flights/${created.FlightId}`);
  expect(readBack.MotorFlightDetailsData, 'created flight should have MotorFlightDetailsData (FlightAircraftType=4)').toBeTruthy();
  expect(readBack.MotorFlightDetailsData!.AircraftId).toBe(motorAircraft.AircraftId);

  await gotoRoute(loggedInPage, '/airmovements');
  await expect(
    loggedInPage.locator(`tbody [data-testid="row"]:has-text("${initialComment}")`),
    'newly-created motor flight row should be visible in today list',
  ).toHaveCount(1, { timeout: 15_000 });

  await gotoRoute(loggedInPage, `/airmovements/${created.FlightId}`);
  // Dotted id — use attribute selector, not CSS '#id'.
  const commentInput = loggedInPage.locator('input[id="MotorFlightDetailsData.FlightComment"]');
  await expect(commentInput).toBeVisible({ timeout: 10_000 });
  await expect(commentInput).toHaveValue(initialComment);

  await commentInput.fill(editedComment);

  const saveButton = loggedInPage
    .locator('form[name="flightDetailsForm"] button[type="submit"]')
    .first();
  await expect(saveButton).toBeEnabled({ timeout: 15_000 });
  await saveButton.click();

  await loggedInPage.waitForURL(/#\/airmovements$/, { timeout: 15_000 });
  await loggedInPage.waitForLoadState('domcontentloaded');

  const after = await api<{ MotorFlightDetailsData: { FlightComment: string } }>(
    loggedInPage, token, 'GET', `/api/v1/flights/${created.FlightId}`);
  expect(after.MotorFlightDetailsData.FlightComment, 'API readback should reflect the edited FlightComment')
    .toBe(editedComment);
  await screenshot(loggedInPage, 'airmovements-crud-02');
});
