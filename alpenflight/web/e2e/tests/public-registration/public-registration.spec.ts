import { type Page, type Route } from '@playwright/test';
import { allowConsoleErrors, expect, test } from '../_helpers/console-guard';
import {
  CLUB_SLUG,
  DISABLED_CLUB_SLUG,
  UNKNOWN_CLUB_SLUG,
  discoveryFlightPath,
  fieldId,
  fillInvoiceAddress,
  fillRegistrant,
  publicApi,
  registrant,
  scenicFlightPath,
  testId,
} from './_helpers/public-registration-form';

/**
 * Public flight-experience registration — mock inner-loop fidelity.
 *
 * Anonymous surface: the SPA boots under `mock-auth`, but these routes carry
 * `publicAccess: true`, so nothing here depends on a principal — every
 * `/api/v1/public/**` call is answered by the stubs below (the console-guard's
 * lowest-priority `/api/v1/**` floor catches anything a case forgot, which
 * would otherwise reach Vite's proxy and trip the no-console-errors guard).
 *
 * The real chain — Person + PersonClub rows, the all-day reservation, the
 * Mailpit confirmation, the audit entry — is `real-idp/public-registration-parity.spec.ts`.
 * This file owns the browser-side contract: field set, day picker, conditional
 * invoice block, in-place success panel, and the typed 404 / 403 / 429 surfaces.
 */

const DISCOVERY_DAYS = [
  { id: 'day-1', date: '2099-06-15', flightTypeId: null },
  { id: 'day-2', date: '2099-08-25', flightTypeId: null },
];

const CLUB_NAME = 'Alpine Soaring';
const REGISTRANT_PERSON_ID = 'pn-019e30c3-2c00-7001-8000-000000000777';

interface SubmittedRegistration {
  path: string;
  body: Record<string, unknown>;
}

/**
 * Records every accepted submission so a case can assert on the payload AND —
 * for the error paths — assert the ABSENCE of a submission rather than only
 * the rendered status panel.
 */
function stubPublicRegistrationBackend(page: Page, submissions: SubmittedRegistration[]) {
  return page.route('**/api/v1/public/**', async (route: Route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;

    if (req.method() === 'GET' && path === publicApi.discoveryDays(CLUB_SLUG)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(DISCOVERY_DAYS),
      });
      return;
    }
    if (path.includes(`/clubs/${UNKNOWN_CLUB_SLUG}/`)) {
      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Club not found' }),
      });
      return;
    }
    if (path.includes(`/clubs/${DISABLED_CLUB_SLUG}/`)) {
      await route.fulfill({
        status: 403,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Public registration is disabled for this club' }),
      });
      return;
    }
    if (req.method() === 'POST') {
      const body = req.postDataJSON() as Record<string, unknown>;
      submissions.push({ path, body });
      // Mirrors the deployed contract: Location names the registrant's Person,
      // and the day only comes back on the flow that selects one.
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/persons/${REGISTRANT_PERSON_ID}` },
        body: JSON.stringify({
          registrantPersonId: REGISTRANT_PERSON_ID,
          clubName: CLUB_NAME,
          ...(body['selectedDay'] === undefined ? {} : { selectedDay: body['selectedDay'] }),
        }),
      });
      return;
    }
    await route.fallback();
  });
}

// Un-fixme with T-17 (/discovery-flight/:clubSlug page + store).
test.describe('discovery flight — anonymous registration form', () => {
  test.fixme('[happy] renders anonymously for a public-registration club', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    const apiCalls: string[] = [];
    page.on('request', (req) => {
      const { pathname } = new URL(req.url());
      if (pathname.startsWith('/api/v1/')) apiCalls.push(pathname);
    });
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(CLUB_SLUG));

    await expect(page.getByTestId(testId.discoveryPage)).toBeVisible();
    await expect(page.getByTestId(testId.form)).toBeVisible();
    await expect(page.getByTestId(testId.clubName)).toBeVisible();
    // The app shell stays off: `showNavBar: false` on the route, and the
    // session bootstrap prefetch must not fire without a principal — the only
    // /api/v1 traffic a public page may generate is its own public reads.
    await expect(page.locator('af-nav-bar')).toHaveCount(0);
    expect(apiCalls.filter((p) => !p.startsWith('/api/v1/public/'))).toEqual([]);

    for (const day of DISCOVERY_DAYS) {
      await expect(page.getByTestId(testId.dayOption(day.date))).toBeVisible();
    }
  });

  test.fixme('[happy] submitting posts the registrant and renders the success panel in place', async ({
    page,
  }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    const candidate = registrant();

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await fillRegistrant(page, candidate);
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!.date)).check();
    await page.getByTestId(testId.submit).click();

    await expect(page.getByTestId(testId.success)).toBeVisible();
    // In place: no navigation, and — POST-only — no field value in the URL.
    await expect(page).toHaveURL(new RegExp(`${discoveryFlightPath(CLUB_SLUG)}$`));
    expect(page.url()).not.toContain(candidate.email);

    expect(submissions).toHaveLength(1);
    expect(submissions[0]!.path).toBe(publicApi.discoverySubmit(CLUB_SLUG));
    expect(submissions[0]!.body).toMatchObject({
      firstName: candidate.firstName,
      lastName: candidate.lastName,
      privateEmail: candidate.email,
      selectedDay: DISCOVERY_DAYS[0]!.date,
    });
  });

  test.fixme('[happy] a differing invoice address is revealed and carried in the payload', async ({
    page,
  }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    const candidate = registrant();
    const payer = registrant({
      firstName: 'Beat',
      lastName: 'Frei',
      email: 'beat.frei@example.com',
    });

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await fillRegistrant(page, candidate);
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!.date)).check();

    await expect(page.getByTestId(testId.invoiceFieldset)).toBeHidden();
    await fillInvoiceAddress(page, payer);
    await expect(page.getByTestId(testId.invoiceFieldset)).toBeVisible();

    await page.getByTestId(testId.submit).click();
    await expect(page.getByTestId(testId.success)).toBeVisible();

    expect(submissions[0]!.body).toMatchObject({
      invoiceAddressIsSame: false,
      invoiceFirstName: payer.firstName,
      invoiceLastName: payer.lastName,
      notificationEmail: payer.email,
    });
  });

  test.fixme('[key-error] required fields are validated before any POST leaves the browser', async ({
    page,
  }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await page.locator(fieldId.firstName).fill('Nina');
    await page.locator(fieldId.firstName).blur();
    await page.locator(fieldId.lastName).fill('');
    await page.locator(fieldId.lastName).blur();

    await expect(
      page.locator('af-form-field', { has: page.locator(fieldId.lastName) }).getByRole('alert'),
    ).toBeVisible();
    await expect(page.getByTestId(testId.submit).locator('button')).toBeDisabled();
    expect(submissions).toEqual([]);
  });
});

// Un-fixme with T-18 (/scenic-flight/:clubSlug page + store).
test.describe('scenic flight — anonymous registration form', () => {
  test.fixme('[happy] submits without a day picker and renders the success panel', async ({
    page,
  }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    const passenger = registrant({ firstName: 'Livia', email: 'livia.keller@example.com' });

    await page.goto(scenicFlightPath(CLUB_SLUG));
    await expect(page.getByTestId(testId.scenicPage)).toBeVisible();
    // The scenic DTO is the discovery one minus the day: no picker may render,
    // or the two flows have silently converged.
    await expect(page.getByTestId(testId.daySelect)).toHaveCount(0);

    await fillRegistrant(page, passenger);
    await page.getByTestId(testId.submit).click();

    await expect(page.getByTestId(testId.success)).toBeVisible();
    expect(submissions).toHaveLength(1);
    expect(submissions[0]!.path).toBe(publicApi.scenicSubmit(CLUB_SLUG));
    expect(submissions[0]!.body).not.toHaveProperty('selectedDay');
  });
});

// Un-fixme with T-17 (the resolver's error contract reaches the two shells).
test.describe('public registration — club resolution + abuse guard surfaces', () => {
  test.fixme('[key-error] an unknown club slug renders the not-found panel and submits nothing', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(testInfo, /\b404\b/);
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(UNKNOWN_CLUB_SLUG));

    await expect(page.getByTestId(testId.notFound)).toBeVisible();
    await expect(page.getByTestId(testId.form)).toHaveCount(0);
    expect(submissions).toEqual([]);
  });

  test.fixme('[key-error] a club with public registration disabled renders the unavailable panel', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(testInfo, /\b403\b/);
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(DISABLED_CLUB_SLUG));

    await expect(page.getByTestId(testId.unavailable)).toBeVisible();
    await expect(page.getByTestId(testId.form)).toHaveCount(0);
    expect(submissions).toEqual([]);
  });

  test.fixme('[key-error] a throttled submission renders the retry-after notice', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(testInfo, /\b429\b/);
    await page.route('**/api/v1/public/**', async (route: Route) => {
      const req = route.request();
      if (req.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(DISCOVERY_DAYS),
        });
        return;
      }
      await route.fulfill({
        status: 429,
        contentType: 'application/problem+json',
        headers: { 'Retry-After': '60' },
        body: JSON.stringify({ title: 'Too many registrations from this address' }),
      });
    });

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await fillRegistrant(page, registrant());
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!.date)).check();
    await page.getByTestId(testId.submit).click();

    await expect(page.getByTestId(testId.throttled)).toBeVisible();
    await expect(page.getByTestId(testId.success)).toHaveCount(0);
  });

  test.fixme('[edge] a missing club slug redirects to the landing page', async ({ page }) => {
    await page.goto('/discovery-flight');
    await expect(page).toHaveURL('/');
  });
});

// Un-fixme with T-19 (mobile-first assertions, AC-DIR-1..4).
test.describe('public registration — mobile-first', () => {
  test.fixme('[edge] single column at 360x640 with >=44x44 px controls', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    await page.setViewportSize({ width: 360, height: 640 });

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await expect(page.getByTestId(testId.form)).toBeVisible();

    // Single column: the first two fields stack rather than sit side by side.
    const first = await page.locator(fieldId.firstName).boundingBox();
    const last = await page.locator(fieldId.lastName).boundingBox();
    expect(first).not.toBeNull();
    expect(last).not.toBeNull();
    expect(last!.y).toBeGreaterThanOrEqual(first!.y + first!.height);

    const submit = await page.getByTestId(testId.submit).boundingBox();
    expect(submit).not.toBeNull();
    expect(submit!.height).toBeGreaterThanOrEqual(44);
    expect(submit!.width).toBeGreaterThanOrEqual(44);

    // Native input types keep the mobile keyboard correct.
    await expect(page.locator(fieldId.privateEmail)).toHaveAttribute('type', 'email');
    await expect(page.locator(fieldId.mobilePhone)).toHaveAttribute('type', 'tel');
    await expect(page.locator(fieldId.dateOfBirth)).toHaveAttribute('type', 'date');
  });
});
