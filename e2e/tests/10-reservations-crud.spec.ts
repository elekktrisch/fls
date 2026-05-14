// e2e/tests/10-reservations-crud.spec.ts
//
// Plan row #10: aircraft reservation create / edit / delete via /reservations.
//
// Flow: (a) POST a reservation via /api/v1/aircraftreservations — the same
// AircraftReservationDetails payload ReservationEditController.save() builds
// (selectize widgets on the form have no testid contract and are brittle to
// drive from Playwright). (b) Verify /reservations list shows the new row.
// (c) Open /reservations/:id/edit, mutate the only plain text input
// (Remarks), submit, re-verify list. (d) Click the row's trash icon, accept
// window.confirm(), assert the row is gone and the API GET 404s.
//
// Contract gaps (not patched here; flagged for the consolidation pass):
//   - reservations-edit.html has no testids on the form, the Save/Delete
//     buttons, the date picker, or the four selectize dropdowns.
//     TODO testid: `reservation-form`, `form-save`, `form-delete`,
//     `form-cancel` on reservations-edit.html.
//   - reservations-table.html trash <a> has no testid; this spec falls back
//     to `.delete-link`. TODO testid: `row-delete`.
//   - AircraftReservationTypes is not seeded by _test-fixture.sql and the
//     server exposes only GET /listitems (no POST). This spec inserts one
//     row directly via SQL. Seeding a type in the fixture would remove that
//     side-channel.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';
import sql from 'mssql';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

const MSSQL_CONFIG: sql.config = {
  user: 'sa', password: 'Demo#FLS#2026', server: 'localhost', port: 1433, database: 'FLSTest',
  options: { trustServerCertificate: true, encrypt: false },
  pool: { max: 2, min: 0, idleTimeoutMillis: 5000 },
};

const TEST_CLUB_ID = '0FA7B76F-47BA-4138-8F96-671400FD7C83'; // TestClub, per fixture.

async function withPool<T>(fn: (pool: sql.ConnectionPool) => Promise<T>): Promise<T> {
  const pool = await new sql.ConnectionPool(MSSQL_CONFIG).connect();
  try { return await fn(pool); } finally { await pool.close(); }
}

async function bearer(page: Page): Promise<string> {
  const t = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    try { return raw ? (JSON.parse(raw).access_token as string) : null; } catch { return null; }
  });
  expect(t, 'expected access_token in sessionStorage').toBeTruthy();
  return t!;
}

// Ensure one AircraftReservationType exists for TestClub (no API, fixture doesn't seed).
async function ensureReservationType(): Promise<string> {
  return withPool(async pool => {
    const r = await pool.request().input('clubId', sql.UniqueIdentifier, TEST_CLUB_ID)
      .query(`SELECT TOP 1 AircraftReservationTypeId FROM AircraftReservationTypes
              WHERE ClubId=@clubId AND (IsDeleted=0 OR IsDeleted IS NULL)`);
    if (r.recordset.length > 0) return r.recordset[0].AircraftReservationTypeId as string;
    const newId = 'E2E10000-0000-0000-0000-000000000001';
    await pool.request().input('id', sql.UniqueIdentifier, newId)
      .input('clubId', sql.UniqueIdentifier, TEST_CLUB_ID)
      .query(`INSERT INTO AircraftReservationTypes
              (AircraftReservationTypeId, AircraftReservationTypeName, IsInstructorRequired,
               IsMaintenance, IsActive, ClubId, CreatedOn, CreatedByUserId, RecordState,
               OwnerId, OwnershipType, IsDeleted)
              VALUES (@id, 'e2e-RegularFlight', 0, 0, 1, @clubId, SYSDATETIME(),
                      '13731EE2-C1D8-455C-8AD1-C39399893FFF', 1, @clubId, 2, 0)`);
    return newId;
  });
}

test.describe.configure({ mode: 'serial' });

test('reservations-crud: create, edit, delete via /reservations', async ({ freshLoggedInPage: loggedInPage }) => {
  const page = loggedInPage;
  const token = await bearer(page);
  const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  const reservationTypeId = await ensureReservationType();

  // Resolve aircraft / location / pilot IDs the same way the form's master-data loaders do.
  const [aircraftsRes, pilotsRes, locationsRes] = await Promise.all([
    page.request.get(`${API_BASE}/api/v1/aircrafts/overview`, { headers: auth }),
    page.request.get(`${API_BASE}/api/v1/persons/gliderpilots/listitems/true`, { headers: auth }),
    page.request.get(`${API_BASE}/api/v1/locations`, { headers: auth }),
  ]);
  for (const [name, r] of [['aircrafts', aircraftsRes], ['pilots', pilotsRes], ['locations', locationsRes]] as const) {
    expect(r.ok(), `${name}: ${r.status()}`).toBeTruthy();
  }
  const aircrafts = await aircraftsRes.json() as { AircraftId: string; Immatriculation: string }[];
  const pilots    = await pilotsRes.json()    as { PersonId: string }[];
  const locations = await locationsRes.json() as { LocationId: string }[];
  const aircraft  = aircrafts.find(a => a.Immatriculation === 'HB-3407') ?? aircrafts[0];
  expect(aircraft, 'expected at least one aircraft').toBeDefined();
  expect(pilots.length, 'expected at least one glider pilot').toBeGreaterThan(0);
  expect(locations.length, 'expected at least one location').toBeGreaterThan(0);

  // Tomorrow; the all-day flag collapses Start/End to the same date.
  const day = new Date(Date.now() + 24 * 3600 * 1000).toISOString().slice(0, 10);
  const remarks = `e2e-create-${Date.now()}`;
  const createRes = await page.request.post(`${API_BASE}/api/v1/aircraftreservations`, {
    headers: auth,
    data: {
      Start: `${day}T00:00:00`, End: `${day}T00:00:00`, IsAllDayReservation: true,
      AircraftId: aircraft.AircraftId, PilotPersonId: pilots[0].PersonId,
      LocationId: locations[0].LocationId, ReservationTypeId: reservationTypeId,
      Remarks: remarks,
    },
  });
  expect(createRes.ok(), `POST reservation: ${createRes.status()} ${await createRes.text()}`).toBeTruthy();
  const created = await createRes.json() as { AircraftReservationId: string };
  expect(created.AircraftReservationId, 'created reservation should have an id').toBeTruthy();

  // List should now include our row (filter defaults to Start.From = today).
  await gotoRoute(page, '/reservations');
  const newRow = page.locator('tbody [data-testid="row"]').filter({ hasText: remarks });
  await expect(newRow, `expected row containing remarks="${remarks}"`).toHaveCount(1, { timeout: 10_000 });

  // Edit: open the form via /reservations/:id/edit, change Remarks, save.
  const editedRemarks = `e2e-edit-${Date.now()}`;
  await gotoRoute(page, `/reservations/${created.AircraftReservationId}/edit`);
  const remarksInput = page.locator('input#remarks');
  await expect(remarksInput).toBeVisible({ timeout: 10_000 });
  await remarksInput.fill(editedRemarks);
  // Save button: type=submit inside the form. No testid — match by translated text fallback.
  await page.getByRole('button', { name: /^Save$|^Speichern$/i }).click();
  await page.waitForURL(/#\/reservations(\?|$)/, { timeout: 10_000 });
  await gotoRoute(page, '/reservations');
  await expect(
    page.locator('tbody [data-testid="row"]').filter({ hasText: editedRemarks }),
    'expected row with edited remarks',
  ).toHaveCount(1, { timeout: 10_000 });

  // Delete: click the trash icon on the row, accept window.confirm().
  page.once('dialog', d => d.accept());
  const targetRow = page.locator('tbody [data-testid="row"]').filter({ hasText: editedRemarks });
  // No testid on the trash <a>; row template uses `.delete-link`. TODO testid: `row-delete`.
  await targetRow.locator('a.delete-link').click();
  await expect(
    page.locator('tbody [data-testid="row"]').filter({ hasText: editedRemarks }),
    'expected the reservation row to be gone after delete',
  ).toHaveCount(0, { timeout: 10_000 });

  // Cross-check via API.
  const verify = await page.request.get(`${API_BASE}/api/v1/aircraftreservations/${created.AircraftReservationId}`, { headers: auth });
  expect(verify.ok(), 'expected GET on deleted reservation to fail').toBeFalsy();
  await screenshot(loggedInPage, '10-reservations-crud-01');
});
