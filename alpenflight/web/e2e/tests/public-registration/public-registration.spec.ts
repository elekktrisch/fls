import { type Locator, type Page, type Route } from '@playwright/test';
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
  mobile: 'screenshots/public-registration/08-discovery-mobile-360x640.png',
} as const;

const CLUB_NAME = 'Alpine Soaring';
const REGISTRANT_PERSON_ID = 'pn-019e30c3-2c00-7001-8000-000000000777';

/**
 * The anonymous club read carries the display name and nothing else — the
 * server pins that field set, so a fixture with more in it would be fiction.
 */
const PUBLIC_CLUB = { clubName: CLUB_NAME };

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

    // Prefix-matched, not `/clubs/<slug>/`: the club read has no trailing
    // segment, and a slug the server rejects is rejected on every path under it.
    if (path.startsWith(publicApi.club(UNKNOWN_CLUB_SLUG))) {
      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Club not found' }),
      });
      return;
    }
    if (path.startsWith(publicApi.club(DISABLED_CLUB_SLUG))) {
      await route.fulfill({
        status: 403,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Public registration is disabled for this club' }),
      });
      return;
    }
    if (req.method() === 'GET' && path === publicApi.club(CLUB_SLUG)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(PUBLIC_CLUB),
      });
      return;
    }
    if (req.method() === 'GET' && path === publicApi.discoveryDays(CLUB_SLUG)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(DISCOVERY_DAYS),
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
    // The club's real name, not the URL slug — this is the product's public face
    // and the visitor has submitted nothing yet.
    await expect(page.getByTestId(testId.clubName)).toHaveText(CLUB_NAME);
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
      const path = new URL(route.request().url()).pathname;
      const body = path === publicApi.club(CLUB_SLUG) ? JSON.stringify(PUBLIC_CLUB) : '[]';
      await route.fulfill({ status: 200, contentType: 'application/json', body });
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
    await expect(page.getByTestId(testId.clubName)).toHaveText(CLUB_NAME);
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
  /**
   * Both flows adjudicate the slug on the anonymous club read, BEFORE any field
   * renders — asserted by the absence of the form, so a visitor never fills one
   * in only to be told on submit that the club is not available.
   */
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

    await page.goto(scenicFlightPath(UNKNOWN_CLUB_SLUG));

    await expect(page.getByTestId(testId.notFound)).toBeVisible();
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

    await page.goto(scenicFlightPath(DISABLED_CLUB_SLUG));

    await expect(page.getByTestId(testId.unavailable)).toBeVisible();
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
        const path = new URL(req.url()).pathname;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body:
            path === publicApi.club(CLUB_SLUG)
              ? JSON.stringify(PUBLIC_CLUB)
              : JSON.stringify(DISCOVERY_DAYS),
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

/**
 * Mobile-first (AC-DIR-1..4). The phone is the device a discovery-flight
 * customer registers from, so the narrow portrait viewport is the primary
 * layout, not a degraded one — and the tap-target floor is the gloves /
 * dirty-hands rationale from the vision NFR, verified by bounding rect rather
 * than axe-core (web CLAUDE.md §5).
 */
test.describe('public registration — mobile-first', () => {
  const MOBILE_PORTRAIT = { width: 360, height: 640 } as const;
  const TOUCH_TARGET_PX = 44;

  interface Box {
    y: number;
    width: number;
    height: number;
  }

  test.use({ viewport: MOBILE_PORTRAIT });

  /**
   * Document-absolute rect. `boundingBox()` is viewport-relative, so any action
   * that scrolls an element into view shifts every earlier field's `y` — which
   * would make the stacking check below report on the scroll position instead of
   * on the layout.
   */
  async function boxOf(locator: Locator): Promise<Box> {
    return locator.evaluate((el: Element) => {
      const rect = el.getBoundingClientRect();
      return {
        y: rect.y + window.scrollY,
        width: rect.width,
        height: rect.height,
      };
    });
  }

  test('[edge] the form is a single column of >=44px targets at 360x640', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await expect(page.getByTestId(testId.form)).toBeVisible();
    await page.getByTestId(testId.invoiceDiffers).check();
    await expect(page.getByTestId(testId.invoiceFieldset)).toBeVisible();
    await page.screenshot({ path: SHOT.mobile, fullPage: true });

    // AC-DIR-1, single column: every field starts below the previous one's
    // bottom edge — a two-up row at this width would overlap vertically.
    const column = [
      fieldId.firstName,
      fieldId.lastName,
      fieldId.addressLine1,
      fieldId.zipCode,
      fieldId.city,
      fieldId.privateEmail,
      fieldId.mobilePhone,
      fieldId.invoiceFirstName,
      fieldId.invoiceZipCode,
      fieldId.notificationEmail,
    ];
    let previousBottom = 0;
    for (const selector of column) {
      const box = await boxOf(page.locator(selector));
      expect(box.y, `${selector} shares a row with the field above it`).toBeGreaterThanOrEqual(
        previousBottom,
      );
      previousBottom = box.y + box.height;
    }

    // …and nothing pushes the page wider than the viewport, which is what turns
    // a stacked column into a side-scrolling one on a real phone.
    const documentWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(documentWidth).toBeLessThanOrEqual(MOBILE_PORTRAIT.width);

    // AC-DIR-2, touch targets. The tap surface of a radio/checkbox is its
    // enclosing label, not the 20px box inside it, so that is what is measured.
    const dayOption = page.locator('label', {
      has: page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!)),
    });
    const invoiceToggle = page.locator('label', {
      has: page.getByTestId(testId.invoiceDiffers),
    });
    const targets = [
      page.getByTestId(testId.submit),
      dayOption,
      invoiceToggle,
      page.locator(fieldId.firstName),
      page.locator(fieldId.notificationEmail),
    ];
    for (const target of targets) {
      const box = await boxOf(target);
      expect(box.height).toBeGreaterThanOrEqual(TOUCH_TARGET_PX);
      expect(box.width).toBeGreaterThanOrEqual(TOUCH_TARGET_PX);
    }
  });

  test('[edge] the fields carry the native types the phone keyboard follows', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await expect(page.getByTestId(testId.form)).toBeVisible();
    await page.getByTestId(testId.invoiceDiffers).check();

    // AC-DIR-3: native types, not custom widgets — a `type="text"` email field
    // hands the visitor an alphabetic keyboard for an address they must type
    // exactly right, and the confirmation mail is what rides on it.
    await expect(page.locator(fieldId.privateEmail)).toHaveAttribute('type', 'email');
    await expect(page.locator(fieldId.privateEmail)).toHaveAttribute('inputmode', 'email');
    await expect(page.locator(fieldId.notificationEmail)).toHaveAttribute('type', 'email');
    for (const phone of [fieldId.mobilePhone, fieldId.privatePhone, fieldId.businessPhone]) {
      await expect(page.locator(phone)).toHaveAttribute('type', 'tel');
      await expect(page.locator(phone)).toHaveAttribute('inputmode', 'tel');
    }
    await expect(page.locator(fieldId.zipCode)).toHaveAttribute('inputmode', 'numeric');
    await expect(page.locator(fieldId.invoiceZipCode)).toHaveAttribute('inputmode', 'numeric');
  });

  test('[edge] submitting keeps every field value out of the URL', async ({ page }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    const candidate = registrant();

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    // AC-DIR-4: the form element carries no `action`, so a submit the app
    // failed to intercept would serialise the fields into the address bar.
    await expect(page.getByTestId(testId.form)).not.toHaveAttribute('action');

    await fillRegistrant(page, candidate);
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!)).check();
    await page.getByTestId(testId.submit).click();
    await expect(page.getByTestId(testId.success)).toBeVisible();

    expect(submissions).toHaveLength(1);
    const url = new URL(page.url());
    expect(url.pathname).toBe(discoveryFlightPath(CLUB_SLUG));
    expect(url.search).toBe('');
    expect(url.hash).toBe('');
    // Value by value, not just the email: a GET fallback would carry the whole
    // registrant, and the address bar is the one place this PII must never land.
    const decoded = decodeURIComponent(page.url());
    for (const value of Object.values(candidate)) {
      expect(decoded, `${value} reached the URL`).not.toContain(value);
    }
  });
});
