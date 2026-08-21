import { randomUUID } from 'node:crypto';

import { type APIResponse, type Page, type Request, type Response } from '@playwright/test';
import { allowConsoleErrors, expect, test, watchConsoleErrors } from '../_helpers/console-guard';
import {
  UNKNOWN_CLUB_SLUG,
  discoveryFlightPath,
  fillInvoiceAddress,
  fillRegistrant,
  publicApi,
  registrant,
  scenicFlightPath,
  testId,
} from '../public-registration/_helpers/public-registration-form';
import { enterClubSettingsViaNav, enterViaNav } from '../_helpers/nav';
import { labelInTheLocaleTheSessionRenders } from '../_helpers/rendered-locale';
import { fillKcLogin } from './_helpers/kc-form';
import { waitForExactlyOneMessage, waitForMessageWithBody } from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';
import {
  discoveryDayDate,
  registrantWireBody,
  seedPublicRegistrationClub,
  seedPublicRegistrationClubWithoutDoubleSeater,
  type PublicRegistrationClub,
  type PublicRegistrationClubWithDoubleSeater,
} from './_helpers/public-registration-fixture';
import { freshTestUser } from './_helpers/test-user';

const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const KC_HOST = 'localhost:8090';

const CLUB_ADMIN = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
} as const;

const CLOSED_CLUB_SLUG = 'seed-club-1';

const EXTERNAL_CLUB_ID = /^clb-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const LEGACY_CANDIDATE_RESERVATION_REMARK = 'Schnupperflug-Kandidat';

const SUBJECT_DISCOVERY_CANDIDATE = 'Anmeldung Schnupperflug erhalten';
const SUBJECT_DISCOVERY_ORGANISER = 'Neue Anmeldung Schnupperflug';
const SUBJECT_SCENIC_CANDIDATE = 'Anmeldung Mitflug erhalten';

const RESERVATION_BOOKED = 'Reservation erstellt.';
const RESERVATION_SKIPPED_NO_DOUBLE_SEATER =
  'Keine Reservation: kein Doppelsitzer für den Verein erfasst.';
const RESERVATION_SKIP_HOMEBASE_WORD = 'Heimflugplatz';

const AUDIT_REGISTRATION_TARGET = 'PublicFlightRegistration';
const AUDIT_AUTHENTICATED_ACTOR_TARGET = 'Location';

const AUDIT_TESTID = {
  table: 'audit-logs-table',
  rowTarget: 'audit-row-target',
  rowActor: 'audit-row-actor',
  filterTarget: 'audit-filter-target',
} as const;

const DAY_MS = 24 * 60 * 60 * 1000;

const SUBMIT_INCLUDING_MAIL_DELIVERY_TIMEOUT_MS = 15_000;

const THREE_REAL_KEYCLOAK_LOGINS_AND_CLUB_SETUP_TIMEOUT_MS = 300_000;

const TWO_SUBMISSIONS_PLUS_SILENCE_WINDOW_TIMEOUT_MS = 90_000;

interface PersonProjection {
  id: string;
  hasGliderTraineeLicence: boolean;
  memberships: { clubId: string; isGliderTrainee: boolean }[];
}

interface ReservationRow {
  id: string;
  aircraftId: string;
  start: string;
  end: string;
  isAllDay: boolean;
  pilotPersonId: string;
  locationId: string;
  remarks: string | null;
}

interface PersonRow {
  id: string;
  firstname: string;
  lastname: string;
  email: string | null;
  isGliderTrainee: boolean;
}

let seeded: PublicRegistrationClubWithDoubleSeater;

let clubWithoutDoubleSeater: PublicRegistrationClub;

test.beforeAll(async ({ browser }, testInfo) => {
  testInfo.setTimeout(THREE_REAL_KEYCLOAK_LOGINS_AND_CLUB_SETUP_TIMEOUT_MS);
  const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
  seeded = await seedPublicRegistrationClub(browser, baseURL);
  clubWithoutDoubleSeater = await seedPublicRegistrationClubWithoutDoubleSeater(browser, baseURL, {
    sysadminAuthorization: seeded.sysadminAuthorization,
  });
});

test.afterAll(async () => {
  await seeded?.dispose();
  await clubWithoutDoubleSeater?.dispose();
});

async function readAs<T>(page: Page, path: string, authorization: string): Promise<T> {
  const res = await page.request.get(path, { headers: { authorization } });
  expect(res.status(), `GET ${path} as the club's administrator`).toBe(200);
  return (await res.json()) as T;
}

function readAsClubAdmin<T>(page: Page, path: string): Promise<T> {
  return readAs<T>(page, path, seeded.adminAuthorization);
}

function personsIn(page: Page, club: { adminAuthorization: string }): Promise<PersonRow[]> {
  return readAs<PersonRow[]>(page, '/api/v1/persons', club.adminAuthorization);
}

function personWithEmail(rows: PersonRow[], email: string): PersonRow | undefined {
  return rows.find((row) => row.email?.toLowerCase() === email.toLowerCase());
}

async function signIn(page: Page, user: { email: string; password: string }): Promise<string> {
  const bearerRequest = page.waitForRequest(
    (req: Request) =>
      req.url().includes('/api/v1/') && /^Bearer /i.test(req.headers()['authorization'] ?? ''),
  );
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, user.email, user.password);
  await page.waitForURL(/\/start(\?|$|\/)/, { timeout: 30_000 });
  return (await bearerRequest).headers()['authorization']!;
}

function discoveryWireBody(
  privateEmail: string,
  selectedDay: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    registrant: registrantWireBody({ privateEmail, ...overrides }),
    selectedDay,
  };
}

async function submitPublicForm(page: Page, submitPath: string): Promise<Response> {
  const submitted = page.waitForResponse(
    (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === submitPath,
    { timeout: SUBMIT_INCLUDING_MAIL_DELIVERY_TIMEOUT_MS },
  );
  await page.getByTestId(testId.submit).click();
  return submitted;
}

function registrantPersonId(response: APIResponse | Response): string {
  const location = response.headers()['location'];
  const id = location?.match(/\/api\/v1\/persons\/(pn-[0-9a-f-]{36})$/)?.[1];
  expect(id, `the 201 Location names the created registrant (got "${location}")`).toBeTruthy();
  return id!;
}

function mailBody(message: { HTML?: string; Text: string }): string {
  return message.HTML ?? message.Text ?? '';
}

function ddMmYyyy(isoDate: string): string {
  const [yyyy, mm, dd] = isoDate.split('-');
  return `${dd}.${mm}.${yyyy}`;
}

function futureReservations(page: Page): Promise<ReservationRow[]> {
  return readAsClubAdmin<ReservationRow[]>(page, '/api/v1/aircraft-reservations/future');
}

async function filterAuditTarget(page: Page, targetEntityType: string): Promise<void> {
  await page.getByTestId(AUDIT_TESTID.filterTarget).locator('input').fill(targetEntityType);
  await expect
    .poll(
      async () => {
        const cells = await page.getByTestId(AUDIT_TESTID.rowTarget).allTextContents();
        return cells.length > 0 && cells.every((cell) => cell.trim() === targetEntityType);
      },
      { message: `every /system/logs row reads ${targetEntityType}, and there is at least one` },
    )
    .toBe(true);
}

test.describe('public flight registration — anonymous front door', () => {
  test('[edge] a club-less discovery-flight URL lands anonymously on the landing page', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const apiCalls: string[] = [];
    page.on('request', (req: Request) => {
      const u = new URL(req.url());
      if (u.host === new URL(SPA_BASE_URL).host && u.pathname.startsWith('/api/v1/')) {
        apiCalls.push(u.pathname);
      }
    });

    try {
      await page.goto('/discovery-flight');
      await page.waitForLoadState('networkidle');

      expect(new URL(page.url()).host).not.toBe(KC_HOST);
      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByTestId('landing')).toBeVisible();
      await expect(page.locator('af-nav-bar')).toHaveCount(0);
      expect(apiCalls.filter((p) => !p.startsWith('/api/v1/public/'))).toEqual([]);

      await page.screenshot({
        path: `${testInfo.outputDir}/discovery-flight-entry.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · anonymous front door · a discovery-flight URL carrying no club returns the ' +
          'visitor to the landing page under the real IdP — no Keycloak redirect, no app chrome ' +
          'and no authenticated prefetch on the unauthenticated surface',
        acTag: 'edge',
      });
    }
  });
});

test.describe('club-admin registration settings', () => {
  test('[happy] a club administrator manages its own club and still cannot read another', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);

    try {
      const authorization = await signIn(page, {
        email: CLUB_ADMIN.username,
        password: CLUB_ADMIN.password,
      });
      const api = { headers: { authorization } };

      const me = await page.request.get('/api/v1/me', api);
      expect(me.status()).toBe(200);
      const clubId = ((await me.json()) as { clubId: string }).clubId;
      expect(clubId).toMatch(EXTERNAL_CLUB_ID);

      const ownClub = await page.request.get(`/api/v1/clubs/${clubId}`, api);
      expect(ownClub.status()).toBe(200);

      expect((await page.request.get('/api/v1/clubs', api)).status()).toBe(403);
      const foreignClub = await page.request.get(`/api/v1/clubs/clb-${randomUUID()}`, api);
      expect(foreignClub.status()).toBe(403);
      expect(await foreignClub.text()).not.toContain('clubKey');

      const operatorEmail = `organiser-${randomUUID().slice(0, 8)}@example.com`;
      const eventDate = discoveryDayDate();

      expect(await enterClubSettingsViaNav(page)).toBe(clubId);
      await expect(page).toHaveURL(new RegExp(`/clubs/${clubId}/edit$`));
      await expect(page.getByTestId('clubs-load-error')).toBeHidden();
      await expect(page.locator('#clubName')).not.toHaveValue('');
      await expect(page.getByTestId('clubs-discovery-flight-type-select')).toBeVisible();

      await page.getByTestId('clubs-discovery-operator-email').locator('input').fill(operatorEmail);
      await expect(page.getByTestId('clubs-discovery-days-panel')).toBeVisible();
      await page.getByTestId('clubs-discovery-day-input').locator('input').fill(eventDate);
      await page.getByTestId('clubs-discovery-day-add').click();
      await expect(page.getByTestId(`clubs-discovery-day-${eventDate}`)).toBeVisible();

      await page.getByTestId('clubs-save-button').click();
      await page.waitForURL(/\/start(\?|$|\/)/, { timeout: 30_000 });

      const stored = await page.request.get(`/api/v1/clubs/${clubId}`, api);
      expect(stored.status()).toBe(200);
      expect((await stored.json()) as Record<string, unknown>).toMatchObject({
        discoveryFlightOperatorEmail: operatorEmail,
      });

      await page.goto(`/clubs/${clubId}/edit`);
      await expect(page.getByTestId('clubs-discovery-operator-email').locator('input')).toHaveValue(
        operatorEmail,
      );
      await expect(page.getByTestId(`clubs-discovery-day-${eventDate}`)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/club-admin-registration-settings.png`,
        fullPage: true,
      });

      await page.getByTestId(`clubs-discovery-day-withdraw-${eventDate}`).click();
      await expect(page.getByTestId(`clubs-discovery-day-${eventDate}`)).toBeHidden();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · club-admin settings · a real CLUB_ADMINISTRATOR opens its own club, sets the ' +
          'discovery-flight organiser address and publishes a discovery day, and the values survive ' +
          'a reload — while the club catalog and every other club stay 403 for that principal',
        acTag: 'happy',
      });
    }
  });
});

test.describe('discovery registration — real rows, real mail', () => {
  test('[happy] an anonymous submission creates a glider-trainee registrant and books the day', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const candidate = registrant({ email: freshTestUser().email });

    try {
      await page.goto(discoveryFlightPath(seeded.slug));
      await expect(page.getByTestId(testId.clubName)).toHaveText(seeded.clubName);
      await expect(page.getByTestId(testId.form)).toBeVisible();

      await fillRegistrant(page, candidate);
      await page.getByTestId(testId.dayOption(seeded.eventDate)).check();

      const submitted = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === publicApi.discoverySubmit(seeded.slug),
        { timeout: SUBMIT_INCLUDING_MAIL_DELIVERY_TIMEOUT_MS },
      );
      await page.getByTestId(testId.submit).click();
      const response = await submitted;
      expect(response.status()).toBe(201);
      const personId = registrantPersonId(response);

      await expect(page.getByTestId(testId.success)).toBeVisible();
      await expect(page.getByTestId('discovery-flight-success-day')).toContainText(
        ddMmYyyy(seeded.eventDate),
      );
      await expect(page).toHaveURL(new RegExp(`${discoveryFlightPath(seeded.slug)}$`));
      expect(page.url()).not.toContain(candidate.email);
      await page.screenshot({
        path: `${testInfo.outputDir}/discovery-registration-success.png`,
        fullPage: true,
      });

      const confirmation = await waitForExactlyOneMessage(candidate.email);
      expect(confirmation.Subject).toBe(SUBJECT_DISCOVERY_CANDIDATE);
      const body = mailBody(confirmation);
      expect(body).toContain(seeded.clubName);
      expect(body).toContain(ddMmYyyy(seeded.eventDate));
      expect(body).toContain(seeded.homebaseName);

      const person = await readAsClubAdmin<PersonProjection>(page, `/api/v1/persons/${personId}`);
      expect(person.hasGliderTraineeLicence, 'the person-level glider-trainee marker').toBe(true);
      const membership = person.memberships.find((m) => m.clubId === seeded.clubId);
      expect(membership, 'a PersonClub in the club the URL named').toBeTruthy();
      expect(membership!.isGliderTrainee, 'the club-level glider-trainee marker').toBe(true);

      const onTheDay = await readAsClubAdmin<ReservationRow[]>(
        page,
        `/api/v1/aircraft-reservations/day/${seeded.eventDate}`,
      );
      const booked = onTheDay.filter((r) => r.pilotPersonId === personId);
      expect(booked, 'exactly one slot blocked for the candidate').toHaveLength(1);
      expect(booked[0]).toMatchObject({
        aircraftId: seeded.gliderId,
        locationId: seeded.homebaseId,
        isAllDay: true,
        remarks: LEGACY_CANDIDATE_RESERVATION_REMARK,
      });
      const dayStart = Date.parse(`${seeded.eventDate}T00:00:00Z`);
      expect(Date.parse(booked[0]!.start)).toBe(dayStart);
      expect(Date.parse(booked[0]!.end)).toBe(dayStart + DAY_MS);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · discovery flight · an anonymous submission creates a glider-trainee person in the ' +
          'club, books the all-day double-seater reservation on the chosen day, and mails the candidate',
        acTag: 'happy',
      });
    }
  });

  test('[edge] a club without a double-seater glider still registers the candidate', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const candidate = registrant({ email: freshTestUser().email });

    try {
      await page.goto(discoveryFlightPath(clubWithoutDoubleSeater.slug));
      await expect(page.getByTestId(testId.clubName)).toHaveText(clubWithoutDoubleSeater.clubName);
      await fillRegistrant(page, candidate);
      await page.getByTestId(testId.dayOption(clubWithoutDoubleSeater.eventDate)).check();

      const response = await submitPublicForm(
        page,
        publicApi.discoverySubmit(clubWithoutDoubleSeater.slug),
      );
      expect(response.status(), 'an unbookable club still accepts the registration').toBe(201);
      const personId = registrantPersonId(response);

      await expect(page.getByTestId(testId.success)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/discovery-registration-reservation-skipped.png`,
        fullPage: true,
      });

      const person = await readAs<PersonProjection>(
        page,
        `/api/v1/persons/${personId}`,
        clubWithoutDoubleSeater.adminAuthorization,
      );
      expect(person.hasGliderTraineeLicence, 'the person-level glider-trainee marker').toBe(true);
      const membership = person.memberships.find(
        (m) => m.clubId === clubWithoutDoubleSeater.clubId,
      );
      expect(membership?.isGliderTrainee, 'the club-level glider-trainee marker').toBe(true);

      const onTheDay = await readAs<ReservationRow[]>(
        page,
        `/api/v1/aircraft-reservations/day/${clubWithoutDoubleSeater.eventDate}`,
        clubWithoutDoubleSeater.adminAuthorization,
      );
      expect(onTheDay, 'a club with no eligible glider blocks no slot').toHaveLength(0);

      await waitForExactlyOneMessage(candidate.email);

      const organiser = await waitForMessageWithBody(
        clubWithoutDoubleSeater.operatorEmail,
        SUBJECT_DISCOVERY_ORGANISER,
        candidate.email,
      );
      const body = mailBody(organiser);
      expect(body).toContain(RESERVATION_SKIPPED_NO_DOUBLE_SEATER);
      expect(body).not.toContain(RESERVATION_BOOKED);
      expect(body).not.toContain(RESERVATION_SKIP_HOMEBASE_WORD);
      expect(body).toContain(clubWithoutDoubleSeater.homebaseName);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · reservation skip · a club with no club-owned double-seater glider still registers ' +
          'the candidate — no reservation is booked and the organiser mail states why',
        acTag: 'edge',
      });
    }
  });
});

test.describe('scenic registration — no reservation, no day', () => {
  test('[happy] an anonymous submission creates a non-trainee registrant and books nothing', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const passenger = registrant({ email: freshTestUser().email });

    try {
      await page.goto(scenicFlightPath(seeded.slug));
      await expect(page.getByTestId(testId.clubName)).toHaveText(seeded.clubName);
      await expect(page.getByTestId(testId.form)).toBeVisible();
      await expect(page.getByTestId(testId.daySelect)).toHaveCount(0);

      const before = await futureReservations(page);

      await fillRegistrant(page, passenger);
      const submitted = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === publicApi.scenicSubmit(seeded.slug),
        { timeout: SUBMIT_INCLUDING_MAIL_DELIVERY_TIMEOUT_MS },
      );
      await page.getByTestId(testId.submit).click();
      const response = await submitted;
      expect(response.status()).toBe(201);
      const personId = registrantPersonId(response);

      await expect(page.getByTestId(testId.success)).toBeVisible();
      await expect(page.getByTestId('discovery-flight-success-day')).toHaveCount(0);
      await expect(page).toHaveURL(new RegExp(`${scenicFlightPath(seeded.slug)}$`));
      expect(page.url()).not.toContain(passenger.email);
      await page.screenshot({
        path: `${testInfo.outputDir}/scenic-registration-success.png`,
        fullPage: true,
      });

      const confirmation = await waitForExactlyOneMessage(passenger.email);
      expect(confirmation.Subject).toBe(SUBJECT_SCENIC_CANDIDATE);
      const body = mailBody(confirmation);
      expect(body).toContain(seeded.clubName);
      expect(body).toContain(passenger.mobilePhone);
      expect(body).toContain(passenger.remarks);

      const person = await readAsClubAdmin<PersonProjection>(page, `/api/v1/persons/${personId}`);
      expect(person.hasGliderTraineeLicence, 'the person-level marker').toBe(false);
      const membership = person.memberships.find((m) => m.clubId === seeded.clubId);
      expect(membership, 'a PersonClub in the club the URL named').toBeTruthy();
      expect(membership!.isGliderTrainee, 'the club-level marker').toBe(false);

      const after = await futureReservations(page);
      expect(after.length, 'the scenic flow blocked no aircraft slot').toBe(before.length);
      expect(after.map((r) => r.pilotPersonId)).not.toContain(personId);

      const discovery = await page.request.post(publicApi.discoverySubmit(seeded.slug), {
        data: {
          registrant: registrantWireBody({ privateEmail: freshTestUser().email }),
          selectedDay: seeded.eventDate,
        },
      });
      expect(discovery.status(), 'the seeded club is genuinely bookable').toBe(201);
      const afterDiscovery = await futureReservations(page);
      expect(afterDiscovery.length).toBe(after.length + 1);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · scenic flight · an anonymous submission creates a non-trainee person in the club, ' +
          'books no reservation, and mails the passenger a confirmation whose phone and remarks render populated',
        acTag: 'happy',
      });
    }
  });
});

test.describe('registration recipients + organiser notification', () => {
  test('[happy] a differing invoice address moves the confirmation to the notification email', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(TWO_SUBMISSIONS_PLUS_SILENCE_WINDOW_TIMEOUT_MS);
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const candidate = registrant({ email: freshTestUser().email });
    const payer = registrant({ firstName: 'Beat', lastName: 'Frei', email: freshTestUser().email });

    try {
      await page.goto(discoveryFlightPath(seeded.slug));
      await fillRegistrant(page, candidate);
      await fillInvoiceAddress(page, payer);
      await page.getByTestId(testId.couponToInvoiceRecipient).check();
      await page.getByTestId(testId.dayOption(seeded.eventDate)).check();

      const response = await submitPublicForm(page, publicApi.discoverySubmit(seeded.slug));
      expect(
        (response.request().postDataJSON() as { registrant: Record<string, unknown> }).registrant[
          'sendCouponToInvoiceAddress'
        ],
        'the submitted payload carries the coupon recipient the visitor picked',
      ).toBe(true);
      expect(response.status()).toBe(201);
      const registrantPerson = registrantPersonId(response);

      await expect(page.getByTestId(testId.success)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/discovery-registration-invoice-differs.png`,
        fullPage: true,
      });

      const confirmation = await waitForExactlyOneMessage(payer.email);
      expect(confirmation.Subject).toBe(SUBJECT_DISCOVERY_CANDIDATE);
      await expect(
        waitForExactlyOneMessage(candidate.email, { timeoutMs: 3_000 }),
        "the registrant's own address is not written to when the invoice address differs",
      ).rejects.toThrow(/no message to:/);

      const members = await personsIn(page, seeded);
      const written = personWithEmail(members, candidate.email);
      expect(written?.id, 'the registrant is in the club the URL named').toBe(registrantPerson);
      expect(written?.isGliderTrainee, 'the discovery registrant is a glider trainee').toBe(true);

      const invoicePerson = personWithEmail(members, payer.email);
      expect(invoicePerson, 'the invoice address is written as a second Person').toBeTruthy();
      expect(invoicePerson).toMatchObject({
        firstname: payer.firstName,
        lastname: payer.lastName,
        isGliderTrainee: false,
      });

      const scenicCandidate = freshTestUser().email;
      const scenicPayer = freshTestUser().email;
      const scenic = await page.request.post(publicApi.scenicSubmit(seeded.slug), {
        data: {
          registrant: registrantWireBody({
            privateEmail: scenicCandidate,
            invoiceAddressIsSame: false,
            invoiceRecipient: {
              firstname: 'Beat',
              lastname: 'Frei',
              addressLine1: 'Buchhaltungsweg 3',
              zip: '6003',
              city: 'Luzern',
              notificationEmail: scenicPayer,
            },
          }),
        },
      });
      expect(scenic.status()).toBe(201);
      expect((await waitForExactlyOneMessage(scenicPayer)).Subject).toBe(SUBJECT_SCENIC_CANDIDATE);
      await expect(
        waitForExactlyOneMessage(scenicCandidate, { timeoutMs: 3_000 }),
        'the scenic recipient branch is the same branch',
      ).rejects.toThrow(/no message to:/);

      const afterScenic = await personsIn(page, seeded);
      expect(personWithEmail(afterScenic, scenicCandidate)?.isGliderTrainee).toBe(false);
      expect(personWithEmail(afterScenic, scenicPayer), 'the scenic invoice Person').toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · invoice address differs · a second invoice person is created without the trainee ' +
          'flag and the confirmation goes to the notification email instead of the private one',
        acTag: 'happy',
      });
    }
  });

  test('[happy] the club operator address receives the organiser notification', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const candidate = registrant({ email: freshTestUser().email });

    try {
      await page.goto(discoveryFlightPath(seeded.slug));
      await fillRegistrant(page, candidate);
      await page.getByTestId(testId.dayOption(seeded.eventDate)).check();

      const response = await submitPublicForm(page, publicApi.discoverySubmit(seeded.slug));
      expect(response.status()).toBe(201);
      await expect(page.getByTestId(testId.success)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/discovery-registration-organiser-notified.png`,
        fullPage: true,
      });

      const organiser = await waitForMessageWithBody(
        seeded.operatorEmail,
        SUBJECT_DISCOVERY_ORGANISER,
        candidate.email,
      );
      const body = mailBody(organiser);
      expect(body).toContain(`${candidate.firstName} ${candidate.lastName}`);
      expect(body).toContain(ddMmYyyy(seeded.eventDate));
      expect(body).toContain(seeded.homebaseName);
      expect(body).toContain(RESERVATION_BOOKED);
      expect(body).not.toContain(RESERVATION_SKIPPED_NO_DOUBLE_SEATER);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          "J-17 · organiser notification · the club's configured discovery-flight operator address " +
          'receives the registration notification carrying the reservation outcome',
        acTag: 'happy',
      });
    }
  });
});

test.describe('public registration — error contract + abuse guard', () => {
  test('[key-error] an unknown slug 404s and a closed club 403s, and neither registers anyone', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    allowConsoleErrors(testInfo, /\b404\b/, /\b403\b/);
    const rejected = registrant({ email: freshTestUser().email });

    try {
      expect((await page.request.get(publicApi.club(UNKNOWN_CLUB_SLUG))).status()).toBe(404);
      expect((await page.request.get(publicApi.club(CLOSED_CLUB_SLUG))).status()).toBe(403);
      const openClub = await page.request.get(publicApi.club(seeded.slug));
      expect(openClub.status()).toBe(200);
      expect(Object.keys((await openClub.json()) as Record<string, unknown>)).toEqual(['clubName']);

      const body = discoveryWireBody(rejected.email, seeded.eventDate);

      const unknown = await page.request.post(publicApi.discoverySubmit(UNKNOWN_CLUB_SLUG), {
        data: body,
      });
      expect(unknown.status()).toBe(404);

      const closed = await page.request.post(publicApi.discoverySubmit(CLOSED_CLUB_SLUG), {
        data: body,
      });
      expect(closed.status()).toBe(403);

      for (const path of [discoveryFlightPath, scenicFlightPath]) {
        await page.goto(path(UNKNOWN_CLUB_SLUG));
        await expect(page.getByTestId(testId.notFound)).toBeVisible();
        await expect(page.getByTestId(testId.form)).toHaveCount(0);

        await page.goto(path(CLOSED_CLUB_SLUG));
        await expect(page.getByTestId(testId.unavailable)).toBeVisible();
        await expect(page.getByTestId(testId.form)).toHaveCount(0);
      }
      await page.screenshot({
        path: `${testInfo.outputDir}/public-registration-club-unavailable.png`,
        fullPage: true,
      });

      const accepted = await page.request.post(publicApi.discoverySubmit(seeded.slug), {
        data: body,
      });
      expect(accepted.status(), 'the rejected body is otherwise a valid registration').toBe(201);
      expect(
        personWithEmail(await personsIn(page, seeded), rejected.email),
        'the open club holds the registrant the two rejected submissions describe',
      ).toBeTruthy();

      const closedClubAdmin = await signIn(page, {
        email: CLUB_ADMIN.username,
        password: CLUB_ADMIN.password,
      });
      const closedClubMembers = await readAs<PersonRow[]>(page, '/api/v1/persons', closedClubAdmin);
      expect(
        closedClubMembers.length,
        'the closed club has members to be found among',
      ).toBeGreaterThan(0);
      expect(
        personWithEmail(closedClubMembers, rejected.email),
        'a club with public registration closed writes no registrant',
      ).toBeUndefined();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · club resolution · an unknown club slug is rejected 404 and a club with public ' +
          'registration disabled 403 — neither writes a person row',
        acTag: 'key-error',
      });
    }
  });

  test('[edge] an accepted submission surfaces in the club audit log as an anonymous actor', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const candidate = registrant({ email: freshTestUser().email });

    try {
      await page.goto(scenicFlightPath(seeded.slug));
      await fillRegistrant(page, candidate);
      const response = await submitPublicForm(page, publicApi.scenicSubmit(seeded.slug));
      expect(response.status()).toBe(201);
      await expect(page.getByTestId(testId.success)).toBeVisible();

      await signIn(page, seeded.admin);
      await enterViaNav(page, '/system/logs');
      await expect(page).toHaveURL('/system/logs');
      await expect(page.getByTestId(AUDIT_TESTID.table)).toBeVisible();

      const anonymousPublicActorLabel = await labelInTheLocaleTheSessionRenders(
        page,
        (translations) => translations.auditLogs.actor.anonymousPublic,
      );

      await filterAuditTarget(page, AUDIT_REGISTRATION_TARGET);
      await page.screenshot({
        path: `${testInfo.outputDir}/audit-anonymous-registration.png`,
        fullPage: true,
      });

      const anonymousActors = page.getByTestId(AUDIT_TESTID.rowActor);
      const anonymousCount = await anonymousActors.count();
      expect(anonymousCount, 'the accepted submission left an audit entry').toBeGreaterThan(0);
      for (let i = 0; i < anonymousCount; i += 1) {
        await expect(anonymousActors.nth(i)).toHaveText(anonymousPublicActorLabel);
      }

      await filterAuditTarget(page, AUDIT_AUTHENTICATED_ACTOR_TARGET);
      const staged = page.getByTestId(AUDIT_TESTID.rowActor).first();
      await expect(staged).not.toHaveText(anonymousPublicActorLabel);
      await expect(staged, 'an authenticated row names its actor').not.toBeEmpty();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · anonymous audit · an accepted public registration writes an audit entry attributed ' +
          "to an anonymous actor in the target club, visible to that club's admin at /system/logs",
        acTag: 'edge',
      });
    }
  });

  test('[key-error] repeated anonymous submissions trip the abuse guard and write nothing', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    allowConsoleErrors(testInfo, /\b429\b/);
    const accepted = freshTestUser().email;
    const refused = registrant({ email: freshTestUser().email });

    try {
      const before = await page.request.post(
        publicApi.discoverySubmit(clubWithoutDoubleSeater.slug),
        {
          data: discoveryWireBody(accepted, clubWithoutDoubleSeater.eventDate),
        },
      );
      expect(before.status(), 'the club registers this body before the window fills').toBe(201);

      const unpublishedDay = new Date(
        Date.parse(`${clubWithoutDoubleSeater.eventDate}T00:00:00Z`) + DAY_MS,
      )
        .toISOString()
        .slice(0, 10);
      let charged = 0;
      let status = 400;
      while (status !== 429 && charged < 15) {
        const primed = await page.request.post(
          publicApi.discoverySubmit(clubWithoutDoubleSeater.slug),
          {
            data: discoveryWireBody(freshTestUser().email, unpublishedDay),
          },
        );
        status = primed.status();
        charged += 1;
        expect(
          [400, 429],
          'a charged-but-unwritten attempt is refused 400 until throttled',
        ).toContain(status);
      }
      expect(status, `the guard tripped within ${charged} charged attempts`).toBe(429);

      await page.goto(discoveryFlightPath(clubWithoutDoubleSeater.slug));
      await expect(page.getByTestId(testId.form)).toBeVisible();
      await fillRegistrant(page, refused);
      await page.getByTestId(testId.dayOption(clubWithoutDoubleSeater.eventDate)).check();

      const response = await submitPublicForm(
        page,
        publicApi.discoverySubmit(clubWithoutDoubleSeater.slug),
      );
      expect(response.status()).toBe(429);
      expect(response.headers()['retry-after'], 'the 429 names when to come back').toMatch(/^\d+$/);

      const throttled = page.getByTestId(testId.throttled);
      await expect(throttled).toBeVisible();
      await expect(throttled).toHaveText(/in [1-9]\d* s/);
      await expect(page.getByTestId(testId.success)).toHaveCount(0);
      await page.screenshot({
        path: `${testInfo.outputDir}/public-registration-throttled.png`,
        fullPage: true,
      });

      const members = await personsIn(page, clubWithoutDoubleSeater);
      expect(
        personWithEmail(members, accepted),
        'the pre-throttle registrant is in the club, so this list can find one',
      ).toBeTruthy();
      expect(
        personWithEmail(members, refused.email),
        'the throttled attempt wrote no Person',
      ).toBeUndefined();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-17',
        caption:
          'J-17 · abuse guard · repeated anonymous submissions from one source are throttled with ' +
          '429 + Retry-After, and the throttled attempt writes no person row',
        acTag: 'key-error',
      });
    }
  });
});
