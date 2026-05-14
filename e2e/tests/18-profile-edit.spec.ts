/**
 * e2e/tests/18-profile-edit.spec.ts
 *
 * Plan row #18: Edit the logged-in user's own Person record via /profile
 * and assert persistence.
 *
 * Approach: drive the AngularJS profile page at `/profile`. The page is
 * composed of TWO forms — a password/user-settings form (left), and a
 * person-edit form (right) that only renders when `myUser.PersonId` is
 * truthy. We target the right-hand form and mutate three non-critical
 * fields: `AddressLine1`, `MobilePhoneNumber`, `PrivatePhoneNumber`. We
 * deliberately do NOT touch the Email / PrivateEmail fields — changing
 * the user's communication email could trigger a re-confirmation cycle
 * server-side (see `UsersController.UpdateUserDetails` / email-confirm
 * mailer wiring).
 *
 * Test data wrinkle: the canonical `testclubadmin` user has
 * `Users.PersonId = NULL` in the seed (see
 * `flsserver/database/FLSTest/3 insert/4 or 5 Insert Test Data.sql:106`),
 * so `myUser.PersonId` is falsy out of the box and the `<fls-person-form>`
 * is never rendered (`ng-if="myUser.PersonId"` in `profile.html:75`).
 * The fixture file does not patch this. To exercise the UI, the test
 * first picks an existing TestClub Person via SQL, points `testclubadmin`
 * at it (UPDATE Users SET PersonId = ...), and patches the
 * `ngStorage-user` sessionStorage entry that the `loggedInPage` fixture
 * injected so `AuthService.getUser()` sees the new PersonId.
 *
 * Persistence is asserted two ways:
 *   1. API readback via `GET /api/v1/persons/{id}` using the bearer token
 *      from sessionStorage.
 *   2. Page reload of `/profile` — re-reads the inputs to confirm the
 *      values come back from the server, not from any client-side cache.
 *
 * Endpoint hit by the form save: `PUT /api/v1/persons/{personId}` (via
 * the `X-HTTP-Method-Override: PUT` POST that `PersonPersister.savePerson`
 * emits — see `PersonsServices.js:161-170`).
 *
 * TODO testid: the person form's Save button has no `data-testid`. We
 * scope to the right-hand `<form>` (the one carrying `<fls-person-form>`)
 * and click its `button[type="submit"]`.
 */
import { expect, gotoRoute, screenshot, test } from '../fixtures';
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

test('profile-edit: testclubadmin updates own Person and reload confirms persistence', async ({ freshLoggedInPage: loggedInPage }) => {
  const page = loggedInPage;

  // 1. Link testclubadmin to an existing TestClub Person, and capture that
  //    PersonId for the rest of the test. We pick any Persons row that is
  //    a TestClub member (PersonClub.ClubId = TestClub).
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

  // 2. Mirror the change into ngStorage-user so AuthService.getUser() sees
  //    the new PersonId before ProfileController evaluates ng-if.
  await page.goto('/#/main');
  await page.evaluate((pid) => {
    const raw = sessionStorage.getItem('ngStorage-user');
    if (!raw) return;
    const u = JSON.parse(raw);
    u.PersonId = pid;
    sessionStorage.setItem('ngStorage-user', JSON.stringify(u));
  }, personId);

  // 3. Navigate to /profile and wait for the person form to render.
  await gotoRoute(page, '/profile');
  const personForm = page.locator('form[name="personForm"]');
  await personForm.waitFor({ state: 'visible', timeout: 15_000 });
  // Wait until the controller has loaded the person and the input is populated.
  await page.locator('#AddressLine1').waitFor({ state: 'visible' });
  await expect(page.locator('#Firstname')).not.toHaveValue('');

  // 4. Edit the three non-critical fields, then submit.
  await page.locator('#AddressLine1').fill(NEW_ADDR);
  await page.locator('#MobilePhoneNumber').fill(NEW_MOBILE);
  await page.locator('#PrivatePhoneNumber').fill(NEW_PHONE);

  // Person form's submit button is scoped to that form (the user-settings
  // form has its own type="submit" for password updates).
  await personForm.locator('button[type="submit"]').click();

  // 5. API-side readback — confirms the PUT landed and was persisted.
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

  // 6. Reload the route — values should come back from the server.
  await gotoRoute(page, '/profile');
  await page.locator('#AddressLine1').waitFor({ state: 'visible' });
  await expect(page.locator('#AddressLine1')).toHaveValue(NEW_ADDR);
  await expect(page.locator('#MobilePhoneNumber')).toHaveValue(NEW_MOBILE);
  await expect(page.locator('#PrivatePhoneNumber')).toHaveValue(NEW_PHONE);
  await screenshot(loggedInPage, '18-profile-edit-01');
});
