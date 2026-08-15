import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import sql from 'mssql';
import { findMessage } from '../../mailpit';
import { screenshot } from '../../fixtures';

const WEB_BASE = process.env.FLS_WEB ?? 'http://localhost:3000';
const TEST_CLUB_KEY = process.env.FLS_TEST_CLUB_KEY ?? 'TestClub';
const TESTCLUBADMIN_EMAIL = 'schuele@galaxy-net.ch';

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
  try {
    return await fn(pool);
  } finally {
    await pool.close();
  }
}

function uniqueEmail(kind: 'trial' | 'passenger'): string {
  const nonce = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  return `${kind}-${nonce}@e2e.fls.local`;
}

async function injectSelectedDayBecauseAvailableDatesIsHardcodedToAnotherClub(
  page: Page,
): Promise<void> {
  await page.evaluate(() => {
    const w = window as any;
    const elem = w.angular.element(document.querySelector('[data-testid="trial-flight-form"]'));
    const ctrl = elem.scope().ctrl;
    ctrl.selectedDay = { date: '2099-07-01T00:00:00' };
    elem.scope().$apply();
  });
}

test('public:trialflight registration creates Person row (email delivery annotated, not asserted)', async ({ page }) => {
  const submittedAt = new Date();
  const email = uniqueEmail('trial');
  const firstName = `Trial${Date.now().toString().slice(-6)}`;
  const lastName = 'E2EUser';

  await page.goto(`${WEB_BASE}/#/trialflight?club=${TEST_CLUB_KEY}`);
  const form = page.locator('[data-testid="trial-flight-form"]');
  await form.waitFor({ state: 'visible', timeout: 10_000 });

  await form.locator('input#name').fill(lastName);
  await form.locator('input#firstname').fill(firstName);
  await form.locator('input#AddressLine1').fill('Teststrasse 1');
  await form.locator('input#zip').fill('1234');
  await form.locator('input#city').fill('Teststadt');
  await form.locator('input#PrivateEmail').fill(email);

  await injectSelectedDayBecauseAvailableDatesIsHardcodedToAnotherClub(page);

  await form.locator('[data-testid="submit"]').click();

  await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });

  const row = await withPool(async pool => {
    const r = await pool.request()
      .input('email', sql.NVarChar, email)
      .query(`SELECT TOP 1 Firstname, Lastname, EmailPrivate
                FROM Persons WHERE EmailPrivate = @email
                ORDER BY CreatedOn DESC`);
    return r.recordset[0];
  });
  expect(row, `expected Persons row for ${email}`).toBeDefined();
  expect(row.Firstname).toBe(firstName);
  expect(row.Lastname).toBe(lastName);
  expect(row.EmailPrivate).toBe(email);

  try {
    const mail = await findMessage(
      { to: email, createdAfter: submittedAt, subjectMatches: /Schnupperflug/i },
      { timeout: 8_000 },
    );
    test.info().annotations.push({
      type: 'mailpit',
      description: `Trial-flight email delivered: subject="${mail.Subject}"`,
    });
  } catch (err) {
    test.info().annotations.push({
      type: 'mailpit:missing',
      description: `No trial-flight email found for ${email}: ${(err as Error).message}`,
    });
  }
  await screenshot(page, 'registration-flows-01');
});

test('public:passengerflight registration creates Person row (email delivery annotated, not asserted)', async ({ page }) => {
  const submittedAt = new Date();
  const email = uniqueEmail('passenger');
  const firstName = `Pax${Date.now().toString().slice(-6)}`;
  const lastName = 'E2EUser';

  await page.goto(`${WEB_BASE}/#/passengerflight?club=${TEST_CLUB_KEY}`);
  const form = page.locator('[data-testid="passenger-flight-form"]');
  await form.waitFor({ state: 'visible', timeout: 10_000 });

  await form.locator('input#name').fill(lastName);
  await form.locator('input#firstname').fill(firstName);
  await form.locator('input#AddressLine1').fill('Teststrasse 2');
  await form.locator('input#zip').fill('5678');
  await form.locator('input#city').fill('Teststadt');
  await form.locator('input#PrivateEmail').fill(email);

  await form.locator('[data-testid="submit"]').click();

  await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });

  const row = await withPool(async pool => {
    const r = await pool.request()
      .input('email', sql.NVarChar, email)
      .query(`SELECT TOP 1 Firstname, Lastname, EmailPrivate
                FROM Persons WHERE EmailPrivate = @email
                ORDER BY CreatedOn DESC`);
    return r.recordset[0];
  });
  expect(row, `expected Persons row for ${email}`).toBeDefined();
  expect(row.Firstname).toBe(firstName);
  expect(row.Lastname).toBe(lastName);
  expect(row.EmailPrivate).toBe(email);

  try {
    const mail = await findMessage(
      { to: email, createdAfter: submittedAt, subjectMatches: /Passagier/i },
      { timeout: 8_000 },
    );
    test.info().annotations.push({
      type: 'mailpit',
      description: `Passenger-flight email delivered: subject="${mail.Subject}"`,
    });
  } catch (err) {
    test.info().annotations.push({
      type: 'mailpit:missing',
      description: `No passenger-flight email found for ${email}: ${(err as Error).message}`,
    });
  }
  await screenshot(page, 'registration-flows-02');
});

test('public:lostpassword sends reset email to seeded user', async ({ page }) => {
  const submittedAt = new Date();

  await page.goto(`${WEB_BASE}/#/lostpassword`);
  const form = page.locator('[data-testid="lostpassword-form"]');
  await form.waitFor({ state: 'visible', timeout: 10_000 });

  await form.locator('input[type="text"]').first().fill('testclubadmin');
  await form.locator('[data-testid="submit"]').click();

  await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });

  const mail = await findMessage(
    {
      to: TESTCLUBADMIN_EMAIL,
      subjectMatches: /Passwort-?Reset/i,
      createdAfter: submittedAt,
    },
    { timeout: 10_000 },
  );
  expect(mail.To.some(r => r.Address.toLowerCase() === TESTCLUBADMIN_EMAIL)).toBeTruthy();
  expect(mail.Text + mail.HTML).toMatch(/userid=[a-f0-9-]+&code=/i);
  await screenshot(page, 'registration-flows-03');
});

test('public:confirm renders the password-set form when given a real reset token', async ({ page }) => {
  const submittedAt = new Date();

  await page.goto(`${WEB_BASE}/#/lostpassword`);
  const form = page.locator('[data-testid="lostpassword-form"]');
  await form.waitFor({ state: 'visible', timeout: 10_000 });
  await form.locator('input[type="text"]').first().fill('testclubadmin');
  await form.locator('[data-testid="submit"]').click();
  await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });

  const mail = await findMessage(
    {
      to: TESTCLUBADMIN_EMAIL,
      subjectMatches: /Passwort-?Reset/i,
      createdAfter: submittedAt,
    },
    { timeout: 10_000 },
  );
  const body = mail.Text + '\n' + mail.HTML;
  const linkMatch = body.match(/(https?:\/\/[^\s"'<>]+\/#\/confirm\?userid=[^\s"'<>]+)/i);
  expect(linkMatch, `confirmation link not found in reset email body: ${body.slice(0, 400)}`).not.toBeNull();
  const link = linkMatch![1];

  const hashIdx = link.indexOf('#/');
  expect(hashIdx).toBeGreaterThan(-1);
  const hashPath = link.slice(hashIdx);
  await page.goto(`${WEB_BASE}/${hashPath}`);

  await expect(page.locator('[data-testid="confirm-email-form"]')).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('input#newPassword')).toBeVisible();
  await expect(page.locator('input#newPasswordConfirm')).toBeVisible();
  await screenshot(page, 'registration-flows-04');
});
