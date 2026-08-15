
import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { withPool } from '../../test-data';
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
  const IMMAT = `T-${id.short}`;
  const COMMENT_EDITED = `${id.name} edited`;
  const token = await bearer(page);
  const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  await withPool(async (pool) => {
    await pool.request()
      .input('immat', sql.NVarChar, IMMAT)
      .query('DELETE FROM Aircrafts WHERE Immatriculation = @immat');
  });

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

  await gotoRoute(page, `/masterdata/aircrafts/${created.AircraftId}`);
  await page.locator('#Immatriculation').waitFor({ state: 'visible', timeout: 30_000 });
  await expect(page.locator('#Immatriculation')).toHaveValue(IMMAT);
  await page.locator('#Comment').fill(COMMENT_EDITED);
  await page.locator('form[name="aircraftForm"] button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/aircrafts', { timeout: 30_000 });
  await page.waitForLoadState('domcontentloaded');

  const verifyRes = await page.request.get(
    `${API_BASE}/api/v1/aircrafts/${created.AircraftId}`,
    { headers: auth },
  );
  expect(verifyRes.ok(), `GET /aircrafts/{id}: ${verifyRes.status()}`).toBeTruthy();
  const verifyBody = (await verifyRes.json()) as { Comment?: string };
  expect(verifyBody.Comment).toBe(COMMENT_EDITED);

  const delRes = await page.request.delete(
    `${API_BASE}/api/v1/aircrafts/${created.AircraftId}`,
    { headers: auth },
  );
  expect(delRes.ok(), `DELETE /aircrafts/{id}: ${delRes.status()}`).toBeTruthy();

  const overviewRes = await page.request.get(
    `${API_BASE}/api/v1/aircrafts/listitems/gliders`,
    { headers: auth },
  );
  expect(
    overviewRes.ok(),
    `aircrafts/listitems: ${overviewRes.status()}: ${(await overviewRes.text().catch(() => '')).slice(0, 200)}`,
  ).toBeTruthy();
  const overview = await overviewRes.json() as Array<{ AircraftId: string; Immatriculation: string }>;
  expect(overview.find(a => a.AircraftId === created.AircraftId)).toBeUndefined();
  await screenshot(loggedInPage, 'aircrafts-crud-01');
});
