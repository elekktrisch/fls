// e2e/tests/26-aircraft-crud.spec.ts
//
// Plan row #26: Create / edit / delete an aircraft via
// `/masterdata/aircrafts`. Mirrors the locations CRUD flow in
// `12-masterdata-crud.spec.ts`, with one deliberate deviation: the aircraft
// edit form's AircraftType field is rendered by selectize.js, which is
// brittle to drive from Playwright (hidden <select> + parallel input + ARIA
// listbox). To keep the spec deterministic we POST the create via the REST
// API (the same `AircraftDetails` payload that AircraftsEditController.save
// would build — see `flsserver/src/FLS.Server.Web/Controllers/AircraftsController.cs`
// `[HttpPost][Route("")]` -> `Insert([FromBody] AircraftDetails)`), then
// drive the edit (Comment field — plain text input) and the row-level
// delete through the UI to keep coverage on the AngularJS controllers.
//
// Endpoints touched (verified in `AircraftsController.cs`):
//   POST   /api/v1/aircrafts                  (create)
//   GET    /api/v1/aircrafts/{aircraftId}     (re-read for assertion)
//   DELETE /api/v1/aircrafts/{aircraftId}     (via X-HTTP-Method-Override
//                                              from `Aircraft.delete` in
//                                              AircraftsServices.js)
//
// Form contract reference: `flsweb/src/masterdata/aircrafts/aircraft-form-fields.html`
// — input ids used here: `#Immatriculation`, `#Comment`. AircraftType is the
// only attribute we cannot easily set from the form; we set it on the POST
// body and skip touching it from the UI.
//
// Contract gaps (flagged for the consolidation pass):
//   - aircrafts-edit.html / aircraft-form-fields.html: Save/Cancel buttons
//     have no `data-testid`. We fall back to `form button[type="submit"]`
//     and `form button[type="button"]`.
//     TODO testid: `form-save`, `form-cancel`.
//   - aircrafts-table.html row `<a class="delete-link">` has no `data-testid`.
//     TODO testid: `row-delete`.
//   - data-table directive's "+" new-button (`.fls-new-button button`) has
//     no `data-testid` (shared with all masterdata lists).
//     TODO testid: `list-new`.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const LIST_PATH = '/masterdata/aircrafts';
const NONCE = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
// Immatriculation is StringLength(15) on the DTO; "T-..." + 11 nonce chars fits.
const IMMAT = `T-${NONCE.slice(-11)}`;
const COMMENT_EDITED = `e2e edited ${NONCE.slice(-6)}`;

async function bearer(page: Page): Promise<string> {
  const t = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    try { return raw ? (JSON.parse(raw).access_token as string) : null; } catch { return null; }
  });
  expect(t, 'expected access_token in sessionStorage').toBeTruthy();
  return t!;
}

function rowByImmatriculation(page: Page, immat: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: immat });
}

test.describe.configure({ mode: 'serial' });

test('aircraft-crud: create via API, edit Comment via UI, delete via UI', async ({
  loggedInPage,
  freshDb,
}) => {
  void freshDb;
  const page = loggedInPage;
  const token = await bearer(page);
  const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  // ---------- CREATE (REST POST) -----------------------------------------
  // AircraftType=1 (Glider) per `FLS.Data.WebApi/Aircraft/AircraftType.cs`.
  // Only Immatriculation is `[Required]` on AircraftDetails; everything else
  // is optional, including AircraftStateData (server defaults / nulls are OK).
  const createRes = await page.request.post(`${API_BASE}/api/v1/aircrafts`, {
    headers: auth,
    data: {
      Immatriculation: IMMAT,
      AircraftType: 1,
      AircraftModel: 'E2E-26-Model',
      ManufacturerName: 'E2EManufacturer',
      NrOfSeats: 1,
      IsTowingAircraft: false,
      Comment: 'created by e2e #26',
    },
  });
  expect(createRes.ok(), `POST /api/v1/aircrafts failed: ${createRes.status()} ${await createRes.text()}`).toBeTruthy();
  const created = (await createRes.json()) as { AircraftId: string; Immatriculation: string };
  expect(created.AircraftId).toBeTruthy();
  expect(created.Immatriculation).toBe(IMMAT);

  // ---------- LIST RENDER ASSERTION (UI) ---------------------------------
  await gotoRoute(page, LIST_PATH);
  // ng-table fetches asynchronously; wait for any row, then filter.
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
  const immatFilter = page.locator('input[ng-model*="Immatriculation"]').first();
  await immatFilter.fill(IMMAT);
  const createdRow = rowByImmatriculation(page, IMMAT);
  await expect(createdRow).toHaveCount(1, { timeout: 10_000 });

  // ---------- EDIT (UI) --------------------------------------------------
  // The row is itself click-bound (`ng-click="editAircraft(aircraft)"`).
  await createdRow.click();
  await page.waitForURL(/\/masterdata\/aircrafts\/[0-9a-fA-F-]{36}$/, { timeout: 10_000 });
  // Wait for the form's Immatriculation input to hydrate from the GET.
  await page.locator('#Immatriculation').waitFor({ state: 'visible' });
  await expect(page.locator('#Immatriculation')).toHaveValue(IMMAT);
  // Mutate Comment (plain text, no validators) so the form stays $valid.
  await page.locator('#Comment').fill(COMMENT_EDITED);
  // Save: the Save button is the `<button type="submit">` in aircrafts-edit.html.
  // Saving navigates back to /masterdata/aircrafts via $location.path.
  await page.locator('form button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/aircrafts', { timeout: 10_000 });
  await page.waitForLoadState('domcontentloaded');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });

  // Verify the edit persisted by re-reading via the API (cheaper than
  // re-opening the form, and immune to selectize re-render timing).
  const verifyRes = await page.request.get(
    `${API_BASE}/api/v1/aircrafts/${created.AircraftId}`,
    { headers: auth },
  );
  expect(verifyRes.ok(), `GET /aircrafts/{id}: ${verifyRes.status()}`).toBeTruthy();
  const verifyBody = (await verifyRes.json()) as { Comment?: string };
  expect(verifyBody.Comment).toBe(COMMENT_EDITED);

  // ---------- DELETE (UI) ------------------------------------------------
  // The list filter is reset on route re-entry; re-apply.
  await immatFilter.fill(IMMAT);
  const rowToDelete = rowByImmatriculation(page, IMMAT);
  await expect(rowToDelete).toHaveCount(1, { timeout: 10_000 });
  // `AircraftService.delete` (AircraftsServices.js) calls `window.confirm`
  // before issuing the DELETE; accept it.
  page.once('dialog', dialog => dialog.accept());
  await rowToDelete.locator('a.delete-link').click();

  // The list controller reloads $scope after the delete promise resolves;
  // wait for the matching row to disappear.
  await expect(
    page.locator('tbody [data-testid="row"]', { hasText: IMMAT }),
  ).toHaveCount(0, { timeout: 10_000 });

  // Belt-and-braces: the aircraft must not be listable any more. The server
  // does a hard delete (`AircraftService.DeleteAircraft` → `context.Remove`),
  // so the row's overview must be gone. We assert via the paged overview
  // endpoint (used by the list itself) rather than the single-id GET, since
  // the latter throws an EntityNotNull-driven 500 against a missing row.
  const pagedRes = await page.request.post(
    `${API_BASE}/api/v1/aircrafts/page/0/200`,
    { headers: auth, data: { Sorting: {}, SearchFilter: { Immatriculation: IMMAT } } },
  );
  expect(pagedRes.ok(), `paged GET: ${pagedRes.status()}`).toBeTruthy();
  const paged = (await pagedRes.json()) as {
    Items: { AircraftId: string; Immatriculation: string }[];
  };
  expect(paged.Items.find(a => a.AircraftId === created.AircraftId)).toBeUndefined();
  await screenshot(loggedInPage, '26-aircraft-crud-01');
});
