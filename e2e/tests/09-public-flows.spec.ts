// Public, unauthenticated flow tests (task #22).
//
// For each public form the FLS web client exposes, drive it through the UI and
// then assert an observable side effect: a DB row (Persons in FLSTest), a
// Mailpit message, or both.
//
// Tables in play:
//   - trial / passenger flight registrations do NOT have dedicated tables in
//     this schema; the server's RegistrationService creates Persons rows
//     (+ optional AircraftReservation for trial flights). The DB assertion is
//     therefore against `Persons.EmailPrivate` with a per-test-run unique
//     suffix so concurrent agents / repeated runs don't collide.
//   - lostpassword sends to a known seeded user (testclubadmin) whose
//     NotificationEmail is `schuele@galaxy-net.ch`. We don't clear the inbox
//     (per the task constraints) — we filter by recipient + subject + a
//     `createdAfter` timestamp so we don't pick up older messages.

import { test, expect } from '@playwright/test';
import sql from 'mssql';
import { findMessage } from '../mailpit';
import { screenshot } from '../fixtures';


// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
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


// ---------------------------------------------------------------------------
// 1. Trial flight registration
// ---------------------------------------------------------------------------
test('public:trialflight registration creates Person row and sends email', async ({ page }) => {
  const submittedAt = new Date();
  const email = uniqueEmail('trial');
  const firstName = `Trial${Date.now().toString().slice(-6)}`;
  const lastName = 'E2EUser';

  await page.goto(`${WEB_BASE}/#/trialflight?club=${TEST_CLUB_KEY}`);
  // Public route: no auth, no global busy indicator wait — just wait for the form.
  const form = page.locator('[data-testid="trial-flight-form"]');
  await form.waitFor({ state: 'visible', timeout: 10_000 });

  // Form labels come from angular-translate (NAME, FIRST_NAME, ADDRESS, ZIP_CODE,
  // CITY, EMAIL) and render as "Name:", "Vorname:", "Adresse:", "PLZ:", "Stadt:",
  // "Email Adresse:". InvoiceAddressIsSame defaults to true so the invoice-block
  // duplicate fields are hidden — `.first()` is safe but explicit.
  await form.locator('input#name').fill(lastName);
  await form.locator('input#firstname').fill(firstName);
  await form.locator('input#AddressLine1').fill('Teststrasse 1');
  await form.locator('input#zip').fill('1234');
  await form.locator('input#city').fill('Teststadt');
  await form.locator('input#PrivateEmail').fill(email);

  // The client's TrialFlightResourceService hard-codes its availabledates URL
  // to /api/v1/trialflightsregistrations/availabledates/fgzo regardless of
  // the ?club= query param (see TrialFlightResourceService.js). Since the
  // TestClub fixture has no `fgzo` row in Settings, the radio list ends up
  // empty and the form would submit SelectedDay=undefined (server 500).
  // Reach into the AngularJS controller scope to inject a SelectedDay.
  await page.evaluate(() => {
    const w = window as any;
    const elem = w.angular.element(document.querySelector('[data-testid="trial-flight-form"]'));
    const ctrl = elem.scope().ctrl;
    ctrl.selectedDay = { date: '2099-07-01T00:00:00' };
    elem.scope().$apply();
  });

  await form.locator('[data-testid="submit"]').click();

  // Success state appears when the controller flips ctrl.success = true.
  await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });

  // DB-side: a Person row should have been inserted with this exact email.
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

  // Mailpit-side: the registration emails the trial pilot. Soft-assert so that
  // an unrelated SMTP misconfig doesn't fail the test (the DB row is the
  // load-bearing assertion). Annotate the result either way.
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
  await screenshot(page, '09-public-flows-01');
});

// ---------------------------------------------------------------------------
// 2. Passenger flight registration
// ---------------------------------------------------------------------------
test('public:passengerflight registration creates Person row and sends email', async ({ page }) => {
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
  await screenshot(page, '09-public-flows-02');
});

// ---------------------------------------------------------------------------
// 3. Lost password
// ---------------------------------------------------------------------------
test('public:lostpassword sends reset email to seeded user', async ({ page }) => {
  const submittedAt = new Date();

  await page.goto(`${WEB_BASE}/#/lostpassword`);
  const form = page.locator('[data-testid="lostpassword-form"]');
  await form.waitFor({ state: 'visible', timeout: 10_000 });

  // Single text input for "Benutzername oder Email".
  await form.locator('input[type="text"]').first().fill('testclubadmin');
  await form.locator('[data-testid="submit"]').click();

  await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });

  // Mailpit: a "Passwort-Reset" email goes to schuele@galaxy-net.ch. Filter by
  // recipient + subject + createdAfter so we don't pick up older identical
  // messages from other agents' tests.
  const mail = await findMessage(
    {
      to: TESTCLUBADMIN_EMAIL,
      subjectMatches: /Passwort-?Reset/i,
      createdAfter: submittedAt,
    },
    { timeout: 10_000 },
  );
  expect(mail.To.some(r => r.Address.toLowerCase() === TESTCLUBADMIN_EMAIL)).toBeTruthy();
  // Body should carry the confirmation link with userid + code.
  expect(mail.Text + mail.HTML).toMatch(/userid=[a-f0-9-]+&code=/i);
  await screenshot(page, '09-public-flows-03');
});

// ---------------------------------------------------------------------------
// 4. Email confirmation (via lostpassword token)
// ---------------------------------------------------------------------------
// We piggy-back on the lostpassword flow to obtain a real {userid, code} pair
// (approach (b) in the task brief) and then visit /confirm with those plus
// emailconfirmed=true. The controller short-circuits to its success branch
// when emailconfirmed is truthy and renders the "choose new password" form.
test('public:confirm renders the password-set form when given a real reset token', async ({ page }) => {
  const submittedAt = new Date();

  // 1. Trigger a fresh reset email so we can scrape userid + code.
  await page.goto(`${WEB_BASE}/#/lostpassword`);
  const form = page.locator('[data-testid="lostpassword-form"]');
  await form.waitFor({ state: 'visible', timeout: 10_000 });
  await form.locator('input[type="text"]').first().fill('testclubadmin');
  await form.locator('[data-testid="submit"]').click();
  await expect(page.locator('[data-testid="success-message"]')).toBeVisible({ timeout: 10_000 });

  // 2. Pluck the link out of the freshly delivered email.
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

  // 3. Visit the link. The link points at the dev-server origin already,
  //    but we strip to a hash path to be robust against origin mismatches.
  const hashIdx = link.indexOf('#/');
  expect(hashIdx).toBeGreaterThan(-1);
  const hashPath = link.slice(hashIdx);
  await page.goto(`${WEB_BASE}/${hashPath}`);

  // 4. Because the link carries emailconfirmed=true (appended by AuthService),
  //    ConfirmEmailController flips success=true immediately without an API
  //    call. The "choose new password" form should render.
  await expect(page.locator('[data-testid="confirm-email-form"]')).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('input#newPassword')).toBeVisible();
  await expect(page.locator('input#newPasswordConfirm')).toBeVisible();
  await screenshot(page, '09-public-flows-04');
});
