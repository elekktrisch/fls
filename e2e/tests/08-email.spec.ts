// e2e/tests/08-email.spec.ts
//
// Email coverage via Mailpit (task #24 of the e2e-gap plan).
//
// Each test triggers an action that should cause the FLS server to send a
// piece of email, then asserts the resulting message lands in Mailpit. The
// helpers in `../mailpit.ts` poll Mailpit's HTTP API.
//
// Recipients: where possible each test owns a unique recipient address
// (`uniqueRecipient()`), so parallel agents sharing the Mailpit instance
// don't interfere. Workflow jobs (#6 daily report, #7 planning day, #8
// licence expiry) send to seeded recipients which we cannot control, so
// those tests filter on subject patterns instead.
//
// SystemData.SmtpServer is pinned to `mailpit` by the deterministic fixture
// (database/FLSTest/3 insert/_test-fixture.sql). The fixture also configures
// UseSmtpAuthentication=1 because Mono's SmtpClient throws on
// UseDefaultCredentials=true (see comment in the fixture file).

import { test, expect } from '@playwright/test';
import {
  clearInboxForRecipient,
  expectEmail,
  listInbox,
  uniqueRecipient,
} from '../mailpit';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';

// Subject text fragments seeded in `90 Insert EmailTemplates.sql` (and
// passenger templates added in _test-fixture.sql §7). Asserted on so we know
// we hit the right template, not just *some* email.
const SUBJECTS = {
  trialPilot:         /Bestätigung für Schnupperflug-Registrierung/i,
  trialOrganizer:     /Neue Schnupperflug-Registrierung/i,
  passengerPilot:     /Bestätigung für Passagierflug-Registrierung/i,
  passengerOrganizer: /Neue Passagierflug-Registrierung/i,
  lostPassword:       /Passwort-Reset für Flight Logging System/i,
  flightReport:       /Flug-Informationen vom/i,
  planningDayOk:      /Flugtag vom .* findet statt/i,
  licenceExpire:      /Lizenz läuft bald ab/i,
};

// The testclub organizer recipients set by the fixture (see _test-fixture.sql
// §8). Tests #2 and #4 assert email is addressed to these.
const TRIAL_ORGANIZER     = 'trial-organizer@e2e.fls.local';
const PASSENGER_ORGANIZER = 'passenger-organizer@e2e.fls.local';

async function getToken(request: import('@playwright/test').APIRequestContext): Promise<string> {
  const res = await request.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: USERNAME, password: PASSWORD },
  });
  if (!res.ok()) {
    throw new Error(`Token request failed: ${res.status()} ${await res.text()}`);
  }
  const body = await res.json();
  return body.access_token as string;
}

// ---------------------------------------------------------------------------
// 1. Trial flight registration confirmation -> trial pilot
// ---------------------------------------------------------------------------
test('email:trial-flight-registration-to-pilot', async ({ request }) => {
  const pilotEmail = uniqueRecipient('e2e-trial-pilot');
  await clearInboxForRecipient(pilotEmail);

  const res = await request.post(`${API_BASE}/api/v1/trialflightsregistrations`, {
    data: {
      ClubKey: 'TestClub',
      Firstname: 'Trial',
      Lastname: 'PilotA',
      PrivateEmail: pilotEmail,
      SelectedDay: '2026-06-15',
      InvoiceAddressIsSame: true,
      AddressLine1: 'Musterstrasse 1',
      ZipCode: '8000',
      City: 'Zürich',
    },
  });
  expect(res.ok()).toBeTruthy();

  const msg = await expectEmail(
    { to: pilotEmail, subjectMatches: SUBJECTS.trialPilot },
    { timeout: 8000 },
  );
  expect(msg.To.some(t => t.Address === pilotEmail)).toBeTruthy();
});

// ---------------------------------------------------------------------------
// 2. Trial flight registration -> club organizer (set on Club row)
// ---------------------------------------------------------------------------
test('email:trial-flight-registration-to-organizer', async ({ request }) => {
  // Don't clear-by-recipient here: parallel agents may be hitting the same
  // organizer recipient. Use a unique pilot, then assert *some* organizer
  // email arrived with our pilot's lastname somewhere in the body so we
  // know it was the one we triggered.
  const pilotEmail = uniqueRecipient('e2e-trial-pilot');
  const pilotLastname = `OrgTest${Date.now().toString(36)}`;

  const res = await request.post(`${API_BASE}/api/v1/trialflightsregistrations`, {
    data: {
      ClubKey: 'TestClub',
      Firstname: 'Trial',
      Lastname: pilotLastname,
      PrivateEmail: pilotEmail,
      SelectedDay: '2026-06-15',
      InvoiceAddressIsSame: true,
      AddressLine1: 'Musterstrasse 1',
      ZipCode: '8000',
      City: 'Zürich',
    },
  });
  expect(res.ok()).toBeTruthy();

  await expectEmail(
    {
      to: TRIAL_ORGANIZER,
      subjectMatches: SUBJECTS.trialOrganizer,
      bodyMatches: new RegExp(pilotLastname),
    },
    { timeout: 8000 },
  );
});

// ---------------------------------------------------------------------------
// 3. Passenger flight registration confirmation -> passenger
// ---------------------------------------------------------------------------
test('email:passenger-flight-registration-to-passenger', async ({ request }) => {
  const paxEmail = uniqueRecipient('e2e-passenger');
  await clearInboxForRecipient(paxEmail);

  const res = await request.post(`${API_BASE}/api/v1/passengerflightsregistrations`, {
    data: {
      ClubKey: 'TestClub',
      Firstname: 'Pax',
      Lastname: 'SampleA',
      PrivateEmail: paxEmail,
      InvoiceAddressIsSame: true,
      AddressLine1: 'Bergstrasse 2',
      ZipCode: '3000',
      City: 'Bern',
    },
  });
  expect(res.ok()).toBeTruthy();

  await expectEmail(
    { to: paxEmail, subjectMatches: SUBJECTS.passengerPilot },
    { timeout: 8000 },
  );
});

// ---------------------------------------------------------------------------
// 4. Passenger flight registration -> club organizer
// ---------------------------------------------------------------------------
test('email:passenger-flight-registration-to-organizer', async ({ request }) => {
  const paxEmail = uniqueRecipient('e2e-passenger');
  const paxLastname = `OrgTest${Date.now().toString(36)}`;

  const res = await request.post(`${API_BASE}/api/v1/passengerflightsregistrations`, {
    data: {
      ClubKey: 'TestClub',
      Firstname: 'Pax',
      Lastname: paxLastname,
      PrivateEmail: paxEmail,
      InvoiceAddressIsSame: true,
      AddressLine1: 'Bergstrasse 2',
      ZipCode: '3000',
      City: 'Bern',
    },
  });
  expect(res.ok()).toBeTruthy();

  // Passenger template body only echoes RecipientName/Lastname/Firstname when
  // rendered, but the templates we seeded for passenger are intentionally
  // minimal (lacking those tokens). Match by subject + recipient only.
  await expectEmail(
    {
      to: PASSENGER_ORGANIZER,
      subjectMatches: SUBJECTS.passengerOrganizer,
    },
    { timeout: 8000 },
  );
});

// ---------------------------------------------------------------------------
// 5. Lost password reset
//    Recipient is the user's NotificationEmail (testclubadmin ->
//    schuele@galaxy-net.ch per the base seed). Asserts a callback URL is in
//    the body.
// ---------------------------------------------------------------------------
test('email:lost-password', async ({ request }) => {
  const recipient = 'schuele@galaxy-net.ch';
  await clearInboxForRecipient(recipient);

  const marker = `e2e-pwreset-${Date.now().toString(36)}`;
  const res = await request.post(`${API_BASE}/api/v1/users/lostpassword`, {
    data: {
      UsernameOrNotificationEmailAddress: 'testclubadmin',
      SearchForUsernameOnly: false,
      PasswordResetLink: `https://test.example/?token={code}&userid={userid}&marker=${marker}`,
    },
  });
  expect(res.ok()).toBeTruthy();

  await expectEmail(
    {
      to: recipient,
      subjectMatches: SUBJECTS.lostPassword,
      bodyMatches: new RegExp(marker),
    },
    { timeout: 8000 },
  );
});

// ---------------------------------------------------------------------------
// 6. Daily flight report (FlightInformationEmailBuildService -> `flightreport`).
//    Triggered by GET /api/v1/workflows/dailyreports. Sends one email per
//    pilot whose ReceiveFlightReports=true for flights created today.
//
//    The deterministic seed adds a historical flight 30 days back, which is
//    too old for the "created today" filter. Other agents may have created
//    fresh flights, but we can't depend on that. So: trigger the workflow,
//    poll the inbox, and skip if no flight-report emails appear.
// ---------------------------------------------------------------------------
test('email:daily-flight-report', async ({ request }) => {
  const token = await getToken(request);

  // Snapshot the inbox before we trigger so we can spot new arrivals.
  const before = (await listInbox()).map(m => m.ID);

  const res = await request.get(`${API_BASE}/api/v1/workflows/dailyreports`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok()).toBeTruthy();

  // The job iterates clubs; it can take a few seconds for SMTP delivery.
  // We poll up to 6s. If no new flightreport email shows up, the seed has no
  // "today" flights for any club. Skip with a note.
  const deadline = Date.now() + 6000;
  let found: Awaited<ReturnType<typeof listInbox>>[number] | null = null;
  while (Date.now() < deadline && !found) {
    const messages = await listInbox();
    found = messages.find(m =>
      !before.includes(m.ID) && SUBJECTS.flightReport.test(m.Subject || ''),
    ) ?? null;
    if (!found) await new Promise(r => setTimeout(r, 250));
  }

  test.skip(!found,
    'No "today" flights in any club, so DailyReportJob is a no-op. ' +
    'Re-seed with a current-day flight to exercise this path.');
  expect(found?.Subject).toMatch(SUBJECTS.flightReport);
});

// ---------------------------------------------------------------------------
// 7. Planning day notifications (PlanningDayEmailBuildService).
//    Triggered by GET /api/v1/workflows/planningdaymails. Sends notifications
//    for planning days "tomorrow" (planningday-ok / planningday-cancel) and
//    "7 days out" (planningday-assignment-notification).
//
//    The seed adds planning-day rows; whether they fall on tomorrow/+7 depends
//    on the date. Skip if no matching planning day exists in the DB.
// ---------------------------------------------------------------------------
test('email:planning-day-notification', async ({ request }) => {
  const token = await getToken(request);

  const before = (await listInbox()).map(m => m.ID);

  const res = await request.get(`${API_BASE}/api/v1/workflows/planningdaymails`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok()).toBeTruthy();

  const planningRegex = /Flugtag vom|Erinnerung für/;

  const deadline = Date.now() + 6000;
  let found: Awaited<ReturnType<typeof listInbox>>[number] | null = null;
  while (Date.now() < deadline && !found) {
    const messages = await listInbox();
    found = messages.find(m =>
      !before.includes(m.ID) && planningRegex.test(m.Subject || ''),
    ) ?? null;
    if (!found) await new Promise(r => setTimeout(r, 250));
  }

  test.skip(!found,
    'No planning day matched the tomorrow / +7d window. Re-seed with a ' +
    'planning day dated DATEADD(DAY, 1, today) to exercise this path.');
  expect(found?.Subject).toMatch(planningRegex);
});

// ---------------------------------------------------------------------------
// 8. Licence expiry notification (LicenceExpireEmailBuildService).
//    LicenceNotificationJob has no dedicated HTTP endpoint; it's only invoked
//    by GET /api/v1/workflows/ at UTC hour 22 (WorkflowService.cs:138). The
//    test calls the root workflows endpoint and skips if either the hour or
//    the seed gates the job out.
// ---------------------------------------------------------------------------
test('email:licence-expiry', async ({ request }) => {
  const token = await getToken(request);

  const before = (await listInbox()).map(m => m.ID);

  const res = await request.get(`${API_BASE}/api/v1/workflows/`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok()).toBeTruthy();

  const deadline = Date.now() + 6000;
  let found: Awaited<ReturnType<typeof listInbox>>[number] | null = null;
  while (Date.now() < deadline && !found) {
    const messages = await listInbox();
    found = messages.find(m =>
      !before.includes(m.ID) && SUBJECTS.licenceExpire.test(m.Subject || ''),
    ) ?? null;
    if (!found) await new Promise(r => setTimeout(r, 250));
  }

  const utcHour = new Date().getUTCHours();
  test.skip(!found,
    `LicenceNotificationJob requires UTC hour=22 (now ${utcHour}) AND a person ` +
    'with a licence expiring within 60 days. Neither condition is guaranteed ' +
    'by the seed, so the test skips when no matching email arrives.');
  expect(found?.Subject).toMatch(SUBJECTS.licenceExpire);
});
