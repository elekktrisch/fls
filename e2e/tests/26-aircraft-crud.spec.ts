// Spec #26: aircraft CRUD via /masterdata/aircrafts. Create via API
// (AircraftType selectize is hostile), edit Comment via UI, delete via UI.
//
// TODO testid: `form-save`, `form-cancel`, `row-delete`, `list-new`.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const LIST_PATH = '/masterdata/aircrafts';

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

  // Pre-clean prior-run aircraft.
  const existing = await page.request.post(`${API_BASE}/api/v1/aircrafts/page/0/200`, {
    headers: auth,
    data: { Sorting: {}, SearchFilter: { Immatriculation: IMMAT } },
  });
  if (existing.ok()) {
    const body = await existing.json() as { Items?: Array<{ AircraftId: string; Immatriculation: string }> };
    for (const a of body.Items ?? []) {
      if (a.Immatriculation !== IMMAT) continue;
      await page.request.post(`${API_BASE}/api/v1/aircrafts/${a.AircraftId}`, {
        headers: { ...auth, 'X-HTTP-Method-Override': 'DELETE' },
      });
    }
  }

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

  // LIST
  await gotoRoute(page, LIST_PATH);
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
  const immatFilter = page.locator('input[ng-model*="Immatriculation"]').first();
  await immatFilter.waitFor({ state: 'visible', timeout: 30_000 });
  await immatFilter.fill(IMMAT);
  const createdRow = rowByImmatriculation(page, IMMAT);
  await expect(createdRow).toHaveCount(1, { timeout: 10_000 });

  // EDIT — row is click-bound (ng-click="editAircraft(...)").
  await createdRow.click();
  await page.waitForURL(/\/masterdata\/aircrafts\/[0-9a-fA-F-]{36}$/, { timeout: 10_000 });
  await page.locator('#Immatriculation').waitFor({ state: 'visible' });
  await expect(page.locator('#Immatriculation')).toHaveValue(IMMAT);
  await page.locator('#Comment').fill(COMMENT_EDITED);
  await page.locator('form[name="aircraftForm"] button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/aircrafts', { timeout: 10_000 });
  await page.waitForLoadState('domcontentloaded');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });

  // API readback (avoids selectize re-render timing).
  const verifyRes = await page.request.get(
    `${API_BASE}/api/v1/aircrafts/${created.AircraftId}`,
    { headers: auth },
  );
  expect(verifyRes.ok(), `GET /aircrafts/{id}: ${verifyRes.status()}`).toBeTruthy();
  const verifyBody = (await verifyRes.json()) as { Comment?: string };
  expect(verifyBody.Comment).toBe(COMMENT_EDITED);

  // DELETE
  await immatFilter.fill(IMMAT);
  const rowToDelete = rowByImmatriculation(page, IMMAT);
  await expect(rowToDelete).toHaveCount(1, { timeout: 10_000 });
  page.once('dialog', dialog => dialog.accept());
  await rowToDelete.locator('a.delete-link').click();

  await expect(
    page.locator('tbody [data-testid="row"]', { hasText: IMMAT }),
  ).toHaveCount(0, { timeout: 10_000 });

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
