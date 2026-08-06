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

/**
 * The public days read returns bare ISO dates ascending, not day objects —
 * `listPublicDiscoveryFlightDays` is typed `string[]` and the controller returns
 * `List<LocalDate>`. The admin resource is the one carrying ids and soft-delete
 * state, and it is not anonymously readable.
 */
const DISCOVERY_DAYS = ['2099-06-15', '2099-08-25'];

/** Diagnostic captures of each asserted state (web CLAUDE.md §8). */
const SHOT = {
  form: 'screenshots/public-registration/01-discovery-form.png',
  success: 'screenshots/public-registration/02-discovery-success.png',
  noDays: 'screenshots/public-registration/03-discovery-no-days.png',
  notFound: 'screenshots/public-registration/04-club-not-found.png',
  unavailable: 'screenshots/public-registration/05-club-unavailable.png',
  scenicForm: 'screenshots/public-registration/06-scenic-form.png',
  scenicSuccess: 'screenshots/public-registration/07-scenic-success.png',
} as const;

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

test.describe('discovery flight — anonymous registration form', () => {
  test('[happy] renders anonymously for a public-registration club', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(CLUB_SLUG));

    await expect(page.getByTestId(testId.discoveryPage)).toBeVisible();
    await expect(page.getByTestId(testId.form)).toBeVisible();
    await expect(page.getByTestId(testId.clubName)).toBeVisible();
    // `showNavBar: false` keeps the app shell off. The no-prefetch half of the
    // AC belongs to real-idp `public-routes.spec.ts`: mock-auth bootstraps a
    // principal on every route, so it cannot be proved here.
    await expect(page.locator('af-nav-bar')).toHaveCount(0);

    for (const day of DISCOVERY_DAYS) {
      await expect(page.getByTestId(testId.dayOption(day))).toBeVisible();
    }

    // A form the visitor has not typed into yet states nothing as wrong.
    await expect(page.getByTestId(testId.form).getByRole('alert')).toHaveCount(0);

    await page.screenshot({ path: SHOT.form, fullPage: true });
  });

  test('[happy] submitting posts the registrant and renders the success panel in place', async ({
    page,
  }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    const candidate = registrant();

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await fillRegistrant(page, candidate);
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!)).check();
    await page.getByTestId(testId.submit).click();

    await expect(page.getByTestId(testId.success)).toBeVisible();
    await page.screenshot({ path: SHOT.success, fullPage: true });
    // In place: no navigation, and — POST-only — no field value in the URL.
    await expect(page).toHaveURL(new RegExp(`${discoveryFlightPath(CLUB_SLUG)}$`));
    expect(page.url()).not.toContain(candidate.email);

    expect(submissions).toHaveLength(1);
    expect(submissions[0]!.path).toBe(publicApi.discoverySubmit(CLUB_SLUG));
    // The registrant is NESTED beside the day: `PublicRegistrantDetails` is one
    // type both public flows post, and its compact constructor is the server's
    // field contract — a flat body would bypass it.
    expect(submissions[0]!.body).toMatchObject({
      registrant: {
        firstname: candidate.firstName,
        lastname: candidate.lastName,
        addressLine1: candidate.addressLine1,
        zip: candidate.zipCode,
        city: candidate.city,
        privateEmail: candidate.email,
        invoiceAddressIsSame: true,
      },
      selectedDay: DISCOVERY_DAYS[0]!,
    });
  });

  test('[happy] a differing invoice address is revealed and carried in the payload', async ({
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
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!)).check();

    await expect(page.getByTestId(testId.invoiceFieldset)).toBeHidden();
    await fillInvoiceAddress(page, payer);
    await expect(page.getByTestId(testId.invoiceFieldset)).toBeVisible();

    await page.getByTestId(testId.submit).click();
    await expect(page.getByTestId(testId.success)).toBeVisible();

    expect(submissions[0]!.body).toMatchObject({
      registrant: {
        invoiceAddressIsSame: false,
        invoiceRecipient: {
          firstname: payer.firstName,
          lastname: payer.lastName,
          notificationEmail: payer.email,
        },
      },
    });
  });

  test('[key-error] required fields are validated before any POST leaves the browser', async ({
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

  test('[edge] a club that has published no days still renders its form', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    await page.route('**/api/v1/public/**', async (route: Route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });

    await page.goto(discoveryFlightPath(CLUB_SLUG));

    // An empty list is a club with nothing published yet, not a broken club:
    // the form renders, the picker says so, and submit stays shut.
    await expect(page.getByTestId(testId.form)).toBeVisible();
    await expect(page.getByTestId(testId.daySelect)).toBeVisible();
    await expect(page.getByTestId('discovery-day-empty')).toBeVisible();
    await page.screenshot({ path: SHOT.noDays, fullPage: true });
    await expect(page.getByTestId(testId.notFound)).toHaveCount(0);
    await expect(page.getByTestId(testId.unavailable)).toHaveCount(0);

    await fillRegistrant(page, registrant());
    await expect(page.getByTestId(testId.submit).locator('button')).toBeDisabled();
    expect(submissions).toEqual([]);
  });
});

test.describe('scenic flight — anonymous registration form', () => {
  test('[happy] submits without a day picker and renders the success panel', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    const passenger = registrant({ firstName: 'Livia', email: 'livia.keller@example.com' });

    await page.goto(scenicFlightPath(CLUB_SLUG));
    await expect(page.getByTestId(testId.scenicPage)).toBeVisible();
    await expect(page.getByTestId(testId.form)).toBeVisible();
    await expect(page.locator('af-nav-bar')).toHaveCount(0);
    // The scenic DTO is the discovery one minus the day: no picker may render,
    // or the two flows have silently converged.
    await expect(page.getByTestId(testId.daySelect)).toHaveCount(0);
    await page.screenshot({ path: SHOT.scenicForm, fullPage: true });

    await fillRegistrant(page, passenger);
    await page.getByTestId(testId.submit).click();

    await expect(page.getByTestId(testId.success)).toBeVisible();
    await page.screenshot({ path: SHOT.scenicSuccess, fullPage: true });
    await expect(page).toHaveURL(new RegExp(`${scenicFlightPath(CLUB_SLUG)}$`));

    expect(submissions).toHaveLength(1);
    expect(submissions[0]!.path).toBe(publicApi.scenicSubmit(CLUB_SLUG));
    // Exact, not a subset: the endpoint refuses an unknown property, so a day
    // sent here would 400 rather than be ignored — and a 201 the flow never
    // booked a slot for must not read as one that did.
    expect(Object.keys(submissions[0]!.body)).toEqual(['registrant']);
    expect(submissions[0]!.body).not.toHaveProperty('selectedDay');
    expect(submissions[0]!.body).toMatchObject({
      registrant: {
        firstname: passenger.firstName,
        lastname: passenger.lastName,
        privateEmail: passenger.email,
        invoiceAddressIsSame: true,
      },
    });
  });
});

test.describe('public registration — club resolution + abuse guard surfaces', () => {
  test('[key-error] an unknown club slug renders the not-found panel and submits nothing', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(testInfo, /\b404\b/);
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(UNKNOWN_CLUB_SLUG));

    await expect(page.getByTestId(testId.notFound)).toBeVisible();
    await page.screenshot({ path: SHOT.notFound, fullPage: true });
    await expect(page.getByTestId(testId.form)).toHaveCount(0);
    expect(submissions).toEqual([]);
  });

  test('[key-error] a club with public registration disabled renders the unavailable panel', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(testInfo, /\b403\b/);
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(DISABLED_CLUB_SLUG));

    await expect(page.getByTestId(testId.unavailable)).toBeVisible();
    await page.screenshot({ path: SHOT.unavailable, fullPage: true });
    await expect(page.getByTestId(testId.form)).toHaveCount(0);
    expect(submissions).toEqual([]);
  });

  test('[key-error] a throttled submission renders the retry-after notice', async ({
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
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!)).check();
    await page.getByTestId(testId.submit).click();

    await expect(page.getByTestId(testId.throttled)).toBeVisible();
    await expect(page.getByTestId(testId.success)).toHaveCount(0);
  });

  test('[edge] a missing club slug redirects to the landing page', async ({ page }) => {
    await page.goto('/discovery-flight');
    await expect(page).toHaveURL('/');

    await page.goto('/scenic-flight');
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
    await expect(page.locator(fieldId.zipCode)).toHaveAttribute('inputmode', 'numeric');
  });
});
