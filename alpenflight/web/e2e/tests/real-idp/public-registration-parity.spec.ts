import { randomUUID } from 'node:crypto';

import { type Request } from '@playwright/test';
import { expect, test, watchConsoleErrors } from '../_helpers/console-guard';
import {
  CLUB_SLUG,
  DISABLED_CLUB_SLUG,
  UNKNOWN_CLUB_SLUG,
  discoveryFlightPath,
  fillInvoiceAddress,
  fillRegistrant,
  registrant,
  scenicFlightPath,
  testId,
} from '../public-registration/_helpers/public-registration-form';
import { enterClubSettingsViaNav } from '../_helpers/nav';
import { fillKcLogin } from './_helpers/kc-form';
import { waitForMessage, waitForMessageWithSubject } from './_helpers/mailpit-client';
import { proofVideo } from './_helpers/proof-video';
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

/** The seeded public-registration club this journey's proof registers against. */
const PROOF_CLUB_SLUG = process.env['E2E_PUBLIC_CLUB_SLUG'] ?? CLUB_SLUG;

/** Seeded club-administrator principal — reads the audit trail back. */
const CLUB_ADMIN = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
} as const;

const EXTERNAL_CLUB_ID = /^clb-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * A far-future date, distinct per run: a fixed one would already be published
 * on a retry and the duplicate-date 409 would read as a failure of the publish
 * path rather than of the fixture.
 */
function discoveryDayDate(): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + 400 + Math.floor(Math.random() * 300));
  return day.toISOString().slice(0, 10);
}

test.describe('public flight registration — anonymous front door', () => {
  test('[happy] the discovery-flight entry is reachable anonymously under the real IdP', async ({
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
      await expect(page.locator('main').first()).toBeVisible();
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
          'J-17 · anonymous front door · the public discovery-flight entry renders under the real ' +
          'IdP with no Keycloak redirect, no app chrome and no authenticated prefetch — the ' +
          'unauthenticated surface the registration form is built on',
        acTag: 'happy',
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

// Un-fixme with T-22 (thicken to the full oracle assertions, both fidelities).
test.describe('discovery registration — real rows, real mail', () => {
  test.fixme('[happy] an anonymous submission creates a glider-trainee registrant and books the day', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const candidate = registrant({ email: freshTestUser().email });

    try {
      await page.goto(discoveryFlightPath(PROOF_CLUB_SLUG));
      await expect(page.getByTestId(testId.form)).toBeVisible();
      await fillRegistrant(page, candidate);
      await page.getByTestId(testId.daySelect).click();
      await page.getByTestId(testId.submit).click();

      await expect(page.getByTestId(testId.success)).toBeVisible();
      const confirmation = await waitForMessage(candidate.email);
      expect(confirmation.Subject).not.toBe('');
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
      await page.goto(discoveryFlightPath(PROOF_CLUB_SLUG));
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

// Un-fixme with T-22.
test.describe('scenic registration — no reservation, no day', () => {
  test.fixme('[happy] an anonymous submission creates a non-trainee registrant and books nothing', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? SPA_BASE_URL;
    const ctx = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
    const page = await ctx.newPage();
    watchConsoleErrors(page, testInfo);
    const passenger = registrant({ email: freshTestUser().email });

    try {
      await page.goto(scenicFlightPath(PROOF_CLUB_SLUG));
      await expect(page.getByTestId(testId.daySelect)).toHaveCount(0);
      await fillRegistrant(page, passenger);
      await page.getByTestId(testId.submit).click();

      await expect(page.getByTestId(testId.success)).toBeVisible();
      const confirmation = await waitForMessage(passenger.email);
      // The legacy passenger templates interpolate the trial-flight namespace
      // and render these blank; the port binds the passenger model.
      expect(confirmation.Text).toContain(passenger.mobilePhone);
      expect(confirmation.Text).toContain(passenger.remarks);
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
      await page.goto(discoveryFlightPath(PROOF_CLUB_SLUG));
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
      await page.goto(discoveryFlightPath(PROOF_CLUB_SLUG));
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

      await page.goto(discoveryFlightPath(UNKNOWN_CLUB_SLUG));
      await expect(page.getByTestId(testId.notFound)).toBeVisible();
      await page.goto(discoveryFlightPath(DISABLED_CLUB_SLUG));
      await expect(page.getByTestId(testId.unavailable)).toBeVisible();
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
      await page.goto(scenicFlightPath(PROOF_CLUB_SLUG));
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
        page.request.post(`/api/v1/public/clubs/${PROOF_CLUB_SLUG}/scenic-flight-registrations`, {
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
