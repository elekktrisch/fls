import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import sql from 'mssql';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const NONCE = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const NEW_ADDR = `E2E Profile Strasse ${NONCE}`;
const NEW_MOBILE = `+41 79 555 ${NONCE.slice(-4)}`;
const NEW_PHONE = `+41 44 555 ${NONCE.slice(-4)}`;

const MSSQL_CONFIG: sql.config = {
  user: 'sa',
  password: 'Demo#FLS#2026',
  server: 'localhost',
  port: 1433,
  database: 'FLSTest',
  options: { trustServerCertificate: true, encrypt: false },
  pool: { max: 2, min: 0, idleTimeoutMillis: 5000 },
};

async function withPool<T>(fn: (pool: sql.ConnectionPool) => Promise<T>): Promise<T> {
  const pool = await new sql.ConnectionPool(MSSQL_CONFIG).connect();
  try { return await fn(pool); } finally { await pool.close(); }
}

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    try { return raw ? JSON.parse(raw).access_token as string : null; } catch { return null; }
  });
  expect(token, 'expected access_token from loggedInPage').toBeTruthy();
  return token!;
}

test('profile-edit: testclubadmin updates own Person and reload confirms persistence', async ({ loggedInPage }) => {
  const page = loggedInPage;

  const personId = await withPool(async pool => {
    const r = await pool.request().query(`
      DECLARE @pid uniqueidentifier =
        (SELECT TOP 1 p.PersonId FROM Persons p
           INNER JOIN PersonClub pc ON pc.PersonId = p.PersonId
           INNER JOIN Clubs c ON c.ClubId = pc.ClubId
          WHERE c.ClubKey = 'TestClub'
            AND p.Lastname IS NOT NULL
          ORDER BY p.Lastname);
      UPDATE Users SET PersonId = @pid WHERE Username = 'testclubadmin';
      SELECT @pid AS PersonId;
    `);
    return r.recordset[0].PersonId as string;
  });
  expect(personId, 'expected to find a TestClub Person to attach').toBeTruthy();

  await page.goto('/#/main');
  await page.evaluate((pid) => {
    const raw = sessionStorage.getItem('ngStorage-user');
    if (!raw) return;
    const u = JSON.parse(raw);
    u.PersonId = pid;
    sessionStorage.setItem('ngStorage-user', JSON.stringify(u));
  }, personId);

  await gotoRoute(page, '/profile');
  const personForm = page.locator('form[name="personForm"]');
  await personForm.waitFor({ state: 'visible', timeout: 15_000 });
  await page.locator('#AddressLine1').waitFor({ state: 'visible' });
  await expect(page.locator('#Firstname')).not.toHaveValue('');

  await page.locator('#AddressLine1').fill(NEW_ADDR);
  await page.locator('#MobilePhoneNumber').fill(NEW_MOBILE);
  await page.locator('#PrivatePhoneNumber').fill(NEW_PHONE);

  await personForm.locator('button[type="submit"]').click();

  const token = await getBearerToken(page);
  await expect(async () => {
    const res = await page.request.get(`${API_BASE}/api/v1/persons/${personId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(res.ok(), `GET /persons/${personId} -> ${res.status()}`).toBeTruthy();
    const body = await res.json();
    expect(body.AddressLine1).toBe(NEW_ADDR);
    expect(body.MobilePhoneNumber).toBe(NEW_MOBILE);
    expect(body.PrivatePhoneNumber).toBe(NEW_PHONE);
  }).toPass({ timeout: 10_000 });

  await gotoRoute(page, '/profile');
  await page.locator('#AddressLine1').waitFor({ state: 'visible' });
  await expect(page.locator('#AddressLine1')).toHaveValue(NEW_ADDR);
  await expect(page.locator('#MobilePhoneNumber')).toHaveValue(NEW_MOBILE);
  await expect(page.locator('#PrivatePhoneNumber')).toHaveValue(NEW_PHONE);
  await screenshot(loggedInPage, 'edit-01');
});
