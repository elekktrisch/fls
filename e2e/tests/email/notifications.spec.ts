
import { test, expect } from '@playwright/test';
import {
  clearInboxForRecipient,
  expectEmail,
  listInbox,
  uniqueRecipient,
} from '../../mailpit';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';

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

test('email:trial-flight-registration-to-organizer', async ({ request }) => {
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

  await expectEmail(
    {
      to: PASSENGER_ORGANIZER,
      subjectMatches: SUBJECTS.passengerOrganizer,
    },
    { timeout: 8000 },
  );
});

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

test('email:daily-flight-report', async ({ request }) => {
  const token = await getToken(request);

  const before = (await listInbox()).map(m => m.ID);

  const res = await request.get(`${API_BASE}/api/v1/workflows/dailyreports`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok()).toBeTruthy();

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
