import { randomUUID } from 'node:crypto';

import { type APIResponse, type Page, type Request, type Response } from '@playwright/test';
import { expect, test, watchConsoleErrors } from '../_helpers/console-guard';
import {
  DISABLED_CLUB_SLUG,
  UNKNOWN_CLUB_SLUG,
  discoveryFlightPath,
  fillInvoiceAddress,
  fillRegistrant,
  publicApi,
  registrant,
  scenicFlightPath,
  testId,
} from '../public-registration/_helpers/public-registration-form';
import { enterClubSettingsViaNav } from '../_helpers/nav';
import { fillKcLogin } from './_helpers/kc-form';
import { waitForMessage, waitForMessageWithSubject } from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';
import {
  discoveryDayDate,
  registrantWireBody,
  seedPublicRegistrationClub,
  type PublicRegistrationClub,
} from './_helpers/public-registration-fixture';
import { freshTestUser } from './_helpers/test-user';

/**
 * Public flight-experience registration against the deployed stack — the only
 * fidelity that can prove the anonymous write path: no principal, no Bearer,
 * tenant resolved from the URL slug, real rows, real Mailpit delivery.
 *
 * The browser-side field/panel contract is the cheaper
 * `public-registration/public-registration.spec.ts`; what lives here is what a
 * mock cannot fake — the Person/PersonClub + reservation outcome, the recipient
 * branch, the abuse guard, and the anonymous-actor audit entry.
 */

const SPA_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';
const KC_HOST = 'localhost:8090';

/** Seeded club-administrator principal — reads the audit trail back. */
const CLUB_ADMIN = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
} as const;

const EXTERNAL_CLUB_ID = /^clb-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Legacy's literal reservation remark (`RegistrationService.cs:197`). */
const CANDIDATE_REMARK = 'Schnupperflug-Kandidat';

/** `PublicRegistrationMailer` subject constants. */
const SUBJECT_DISCOVERY_CANDIDATE = 'Anmeldung Schnupperflug erhalten';
const SUBJECT_SCENIC_CANDIDATE = 'Anmeldung Mitflug erhalten';

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The submit is answered only after both notification mails have gone out over
 * SMTP inside the request, so it legitimately outruns the suite's 5s
 * per-assertion cap.
 */
const SUBMIT_TIMEOUT_MS = 15_000;

/** `GET /api/v1/persons/{id}` — the markers a registrant write must set. */
interface PersonProjection {
  id: string;
  hasGliderTraineeLicence: boolean;
  memberships: { clubId: string; isGliderTrainee: boolean }[];
}

/** `GET /api/v1/aircraft-reservations/*` list row. */
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

/**
 * The club both happy flows register against — created, configured, stocked and
 * published through production HTTP only. Seeded HERE rather than in the shared
 * clean seed: a registrable club carries a homebase, a bookable glider and an
 * organiser address, so every other journey's gate would inherit a surprise
 * registrant and an unexpected outbound mail.
 */
let seeded: PublicRegistrationClub;

test.beforeAll(async ({ browser }, testInfo) => {
  // Two real Keycloak logins (the sysadmin that may create a club, then the
  // club's own administrator) plus the configuration round-trips run past the
  // per-test budget this project sets for a single flow.
  testInfo.setTimeout(180_000);
  seeded = await seedPublicRegistrationClub(browser, testInfo.project.use.baseURL ?? SPA_BASE_URL);
});

test.afterAll(async () => {
  await seeded?.dispose();
});

/** Read a tenant-scoped resource back as the seeded club's own administrator. */
async function readAsClubAdmin<T>(page: Page, path: string): Promise<T> {
  const res = await page.request.get(path, {
    headers: { authorization: seeded.adminAuthorization },
  });
  expect(res.status(), `GET ${path} as the seeded club's administrator`).toBe(200);
  return (await res.json()) as T;
}

/**
 * The registrant's `Person` id, taken from the 201 `Location` header rather
 * than the body: a POST response body is evicted the moment the SPA navigates,
 * and the header is stable either way.
 */
function registrantPersonId(response: APIResponse | Response): string {
  const location = response.headers()['location'];
  const id = location?.match(/\/api\/v1\/persons\/(pn-[0-9a-f-]{36})$/)?.[1];
  expect(id, `the 201 Location names the created registrant (got "${location}")`).toBeTruthy();
  return id!;
}

function mailBody(message: { HTML?: string; Text: string }): string {
  return message.HTML ?? message.Text ?? '';
}

/** `yyyy-MM-dd` → the `dd.MM.yyyy` the UI and the German mail templates render. */
function ddMmYyyy(isoDate: string): string {
  const [yyyy, mm, dd] = isoDate.split('-');
  return `${dd}.${mm}.${yyyy}`;
}

/** Future reservations of the seeded club, tenant-scoped to it by its own admin. */
function futureReservations(page: Page): Promise<ReservationRow[]> {
  return readAsClubAdmin<ReservationRow[]>(page, '/api/v1/aircraft-reservations/future');
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

      // Anonymous: the live IdP is configured, so a guard regression on this
      // route would bounce the visitor to Keycloak before anything renders.
      expect(new URL(page.url()).host).not.toBe(KC_HOST);
      // No club in the URL means no club to register with, so the visitor is
      // returned to the front page (`TryFlightController.js:8-10`).
      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByTestId('landing')).toBeVisible();
      // No app chrome and no authenticated prefetch on a public surface.
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
      const bearerRequest = page.waitForRequest(
        (req: Request) =>
          req.url().includes('/api/v1/') && /^Bearer /i.test(req.headers()['authorization'] ?? ''),
      );
      await page.goto('/');
      await page.getByTestId('landing-topbar-sign-in').click();
      await page.waitForURL(/\/realms\/alpenflight\//);
      await fillKcLogin(page, CLUB_ADMIN.username, CLUB_ADMIN.password);
      await page.waitForURL(/\/start(\?|$|\/)/, { timeout: 30_000 });
      const authorization = (await bearerRequest).headers()['authorization']!;
      const api = { headers: { authorization } };

      // The club id `/join-requests` builds its club-edit link from. The realm
      // stamps a club KEY into the `clubId` claim, so this is the only source of
      // the routable club id — and the gate has to agree with it.
      const me = await page.request.get('/api/v1/me', api);
      expect(me.status()).toBe(200);
      const clubId = ((await me.json()) as { clubId: string }).clubId;
      expect(clubId).toMatch(EXTERNAL_CLUB_ID);

      const ownClub = await page.request.get(`/api/v1/clubs/${clubId}`, api);
      expect(ownClub.status()).toBe(200);

      // Own-club access is not catalog access: the cross-tenant list and any
      // club that is not the caller's stay closed.
      expect((await page.request.get('/api/v1/clubs', api)).status()).toBe(403);
      const foreignClub = await page.request.get(`/api/v1/clubs/clb-${randomUUID()}`, api);
      expect(foreignClub.status()).toBe(403);
      expect(await foreignClub.text()).not.toContain('clubKey');

      const operatorEmail = `organiser-${randomUUID().slice(0, 8)}@example.com`;
      const eventDate = discoveryDayDate();

      // Entered through the chrome, not by URL: the club catalog is closed to
      // this role, so the Masterdata own-club entry is its only nav route in —
      // and it has to resolve to the SAME club `/me` reports.
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
      // No club catalog for this role, so saving returns it to its own start page.
      await page.waitForURL(/\/start(\?|$|\/)/, { timeout: 30_000 });

      // The club PUT is full-replace, so read the stored row back rather than
      // trusting the form's own echo of what it submitted.
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
      // Resolved anonymously from the slug: the club's real NAME, not the URL
      // token the visitor arrived with.
      await expect(page.getByTestId(testId.clubName)).toHaveText(seeded.clubName);
      await expect(page.getByTestId(testId.form)).toBeVisible();

      await fillRegistrant(page, candidate);
      // The club published exactly one day, so the picker offers exactly one.
      await page.getByTestId(testId.dayOption(seeded.eventDate)).check();

      const submitted = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === publicApi.discoverySubmit(seeded.slug),
        { timeout: SUBMIT_TIMEOUT_MS },
      );
      await page.getByTestId(testId.submit).click();
      const response = await submitted;
      expect(response.status()).toBe(201);
      const personId = registrantPersonId(response);

      // The panel replaces the form in place — no navigation, so no field value
      // ever reaches the URL.
      await expect(page.getByTestId(testId.success)).toBeVisible();
      await expect(page.getByTestId('discovery-flight-success-day')).toContainText(
        ddMmYyyy(seeded.eventDate),
      );
      await expect(page).toHaveURL(new RegExp(`${discoveryFlightPath(seeded.slug)}$`));
      expect(page.url()).not.toContain(candidate.email);
      // Captured on the state that RENDERS the result, before the deep asserts.
      await page.screenshot({
        path: `${testInfo.outputDir}/discovery-registration-success.png`,
        fullPage: true,
      });

      const confirmation = await waitForMessage(candidate.email);
      expect(confirmation.Subject).toBe(SUBJECT_DISCOVERY_CANDIDATE);
      const body = mailBody(confirmation);
      expect(body).toContain(seeded.clubName);
      expect(body).toContain(ddMmYyyy(seeded.eventDate));
      // The booked homebase travels into the candidate's mail.
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
        remarks: CANDIDATE_REMARK,
      });
      // All-day is the half-open `[day 00:00, +1 day)` span (J-5).
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

  test.fixme('[edge] a club without a double-seater glider still registers the candidate', async ({
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
      await page.getByTestId(testId.submit).click();

      // Reservation-skip is a SUCCESS path in legacy: the registration stands
      // and the organiser mail carries the reason.
      await expect(page.getByTestId(testId.success)).toBeVisible();
      await waitForMessage(candidate.email);
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
      // The scenic form selects no day — there is nothing to book against.
      await expect(page.getByTestId(testId.daySelect)).toHaveCount(0);

      const before = await futureReservations(page);

      await fillRegistrant(page, passenger);
      const submitted = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === publicApi.scenicSubmit(seeded.slug),
        { timeout: SUBMIT_TIMEOUT_MS },
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

      const confirmation = await waitForMessage(passenger.email);
      expect(confirmation.Subject).toBe(SUBJECT_SCENIC_CANDIDATE);
      const body = mailBody(confirmation);
      expect(body).toContain(seeded.clubName);
      // The legacy passenger templates interpolate the trial-flight namespace
      // and render these blank; the port binds the passenger model.
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

      // The zero is the FLOW's doing, not an unbookable club: the very same
      // club fills a slot the moment a discovery submission arrives.
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

// Un-fixme with T-22.
test.describe('registration recipients + organiser notification', () => {
  test.fixme('[happy] a differing invoice address moves the confirmation to the notification email', async ({
    browser,
  }, testInfo) => {
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
      await page.getByTestId(testId.submit).click();

      await expect(page.getByTestId(testId.success)).toBeVisible();
      await waitForMessage(payer.email);
      await expect(waitForMessage(candidate.email, { timeoutMs: 3_000 })).rejects.toThrow();
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

  test.fixme('[happy] the club operator address receives the organiser notification', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const candidate = registrant({ email: freshTestUser().email });
    const operatorEmail = freshTestUser().email;

    try {
      await page.goto(discoveryFlightPath(seeded.slug));
      await fillRegistrant(page, candidate);
      await page.getByTestId(testId.submit).click();
      await expect(page.getByTestId(testId.success)).toBeVisible();

      // The operator address is a club setting, not "the club admin" — a
      // shared inbox that may hold more than one mail per run.
      await waitForMessageWithSubject(operatorEmail, 'Neue Schnupperflug-Anmeldung');
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

// Un-fixme with T-22.
test.describe('public registration — error contract + abuse guard', () => {
  test.fixme('[key-error] an unknown slug 404s and a disabled club 403s, and neither registers anyone', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);

    try {
      // The club read carries the same contract as the writes, and is what both
      // forms adjudicate the slug on before rendering a field.
      expect((await page.request.get(`/api/v1/public/clubs/${UNKNOWN_CLUB_SLUG}`)).status()).toBe(
        404,
      );
      expect((await page.request.get(`/api/v1/public/clubs/${DISABLED_CLUB_SLUG}`)).status()).toBe(
        403,
      );
      const openClub = await page.request.get(`/api/v1/public/clubs/${seeded.slug}`);
      expect(openClub.status()).toBe(200);
      // The exposed field set is pinned server-side; assert it on the deployed
      // stack too, so a widening cannot reach an anonymous visitor unnoticed.
      expect(Object.keys((await openClub.json()) as Record<string, unknown>)).toEqual(['clubName']);

      const unknown = await page.request.post(
        `/api/v1/public/clubs/${UNKNOWN_CLUB_SLUG}/discovery-flight-registrations`,
        { data: registrant() },
      );
      expect(unknown.status()).toBe(404);

      const disabled = await page.request.post(
        `/api/v1/public/clubs/${DISABLED_CLUB_SLUG}/discovery-flight-registrations`,
        { data: registrant() },
      );
      expect(disabled.status()).toBe(403);

      // No form is offered on either flow: the visitor is told before typing.
      for (const path of [discoveryFlightPath, scenicFlightPath]) {
        await page.goto(path(UNKNOWN_CLUB_SLUG));
        await expect(page.getByTestId(testId.notFound)).toBeVisible();
        await expect(page.getByTestId(testId.form)).toHaveCount(0);

        await page.goto(path(DISABLED_CLUB_SLUG));
        await expect(page.getByTestId(testId.unavailable)).toBeVisible();
        await expect(page.getByTestId(testId.form)).toHaveCount(0);
      }
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

  test.fixme('[edge] an accepted submission surfaces in the club audit log as an anonymous actor', async ({
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
      await page.getByTestId(testId.submit).click();
      await expect(page.getByTestId(testId.success)).toBeVisible();

      await page.goto('/');
      await page.getByTestId('landing-topbar-sign-in').click();
      await page.waitForURL(/\/realms\/alpenflight\//);
      await fillKcLogin(page, CLUB_ADMIN.username, CLUB_ADMIN.password);
      await page.waitForURL(/\/start(\?|$|\/)/, { timeout: 30_000 });

      await page.goto('/system/logs');
      await expect(page.getByTestId('audit-logs-table')).toBeVisible();
      await expect(page.getByTestId('audit-logs-table')).toContainText(candidate.lastName);
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

  test.fixme('[key-error] repeated anonymous submissions trip the abuse guard', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const throttled = registrant({ email: freshTestUser().email });

    try {
      const submit = () =>
        page.request.post(`/api/v1/public/clubs/${seeded.slug}/scenic-flight-registrations`, {
          data: throttled,
        });

      let last = await submit();
      for (let i = 0; i < 10 && last.status() !== 429; i += 1) {
        last = await submit();
      }
      expect(last.status()).toBe(429);
      expect(last.headers()['retry-after']).toBeTruthy();
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
