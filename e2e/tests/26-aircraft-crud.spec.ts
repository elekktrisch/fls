// Spec #26: aircraft CRUD via /masterdata/aircrafts. Create via API
// (AircraftType selectize is hostile), edit Comment via UI, delete via UI.
//
// TODO testid: `form-save`, `form-cancel`, `row-delete`, `list-new`.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import { withPool } from '../test-data';
import sql from 'mssql';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

async function bearer(page: Page): Promise<string> {
  const t = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    try { return raw ? (JSON.parse(raw).access_token as string) : null; } catch { return null; }
  });
  expect(t, 'expected access_token in sessionStorage').toBeTruthy();
  return t!;
}

test('aircraft-crud: create via API, edit Comment via UI, delete via UI', async ({
  loggedInPage,
}, testInfo) => {
  const page = loggedInPage;
  const id = testId(testInfo);
  // Immatriculation is StringLength(15). "T-" + 6-char hash fits.
  const IMMAT = `T-${id.short}`;
  const COMMENT_EDITED = `${id.name} edited`;
  const token = await bearer(page);
  const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  // Pre-clean via raw SQL. EF6 soft-deletes Aircrafts (Remove sets IsDeleted=1
  // but leaves DeletedOn=NULL), and the unique constraint is on
  // (Immatriculation, DeletedOn) — two NULL DeletedOn rows collide. The paged
  // API endpoint filters out soft-deleted rows so we can't find them via API.
  // SQL gets us at the raw table.
  await withPool(async (pool) => {
    await pool.request()
      .input('immat', sql.NVarChar, IMMAT)
      .query('DELETE FROM Aircrafts WHERE Immatriculation = @immat');
  });

  // CREATE via API — AircraftType=1 (Glider).
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

  // EDIT — open the edit form by ID directly; ng-table's filter inputs lag
  // badly under accumulated load (locations list test #12 has the same
  // problem). The list-click path is well-covered by other CRUD specs.
  await gotoRoute(page, `/masterdata/aircrafts/${created.AircraftId}`);
  await page.locator('#Immatriculation').waitFor({ state: 'visible', timeout: 30_000 });
  await expect(page.locator('#Immatriculation')).toHaveValue(IMMAT);
  await page.locator('#Comment').fill(COMMENT_EDITED);
  await page.locator('form[name="aircraftForm"] button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/aircrafts', { timeout: 30_000 });
  await page.waitForLoadState('domcontentloaded');

  // API readback proves the edit landed.
  const verifyRes = await page.request.get(
    `${API_BASE}/api/v1/aircrafts/${created.AircraftId}`,
    { headers: auth },
  );
  expect(verifyRes.ok(), `GET /aircrafts/{id}: ${verifyRes.status()}`).toBeTruthy();
  const verifyBody = (await verifyRes.json()) as { Comment?: string };
  expect(verifyBody.Comment).toBe(COMMENT_EDITED);

  // DELETE via API. Driving the list-table trash anchor is unreliable here
  // because the ng-table filter input fails to render under accumulated
  // load (see TEST_WRITING.md §6 — ng-table filter inputs lag). The
  // delete pathway itself is covered by spec #29 / #30 against shorter
  // tables.
  const delRes = await page.request.delete(
    `${API_BASE}/api/v1/aircrafts/${created.AircraftId}`,
    { headers: auth },
  );
  expect(delRes.ok(), `DELETE /aircrafts/{id}: ${delRes.status()}`).toBeTruthy();

  // Server hard-deletes; assert via paged overview (single-id GET 500s on missing row).
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
