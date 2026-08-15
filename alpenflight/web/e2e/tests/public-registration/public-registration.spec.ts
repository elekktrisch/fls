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


const DISCOVERY_DAYS = ['2099-06-15', '2099-08-25'];

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

const PUBLIC_CLUB = { clubName: CLUB_NAME };

interface SubmittedRegistration {
  path: string;
  body: Record<string, unknown>;
}

function stubPublicRegistrationBackend(page: Page, submissions: SubmittedRegistration[]) {
  return page.route('**/api/v1/public/**', async (route: Route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;

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
    await expect(page.getByTestId(testId.clubName)).toHaveText(CLUB_NAME);
    await expect(page.locator('af-nav-bar')).toHaveCount(0);

    for (const day of DISCOVERY_DAYS) {
      await expect(page.getByTestId(testId.dayOption(day))).toBeVisible();
    }

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
    await expect(page).toHaveURL(new RegExp(`${discoveryFlightPath(CLUB_SLUG)}$`));
    expect(page.url()).not.toContain(candidate.email);

    expect(submissions).toHaveLength(1);
    expect(submissions[0]!.path).toBe(publicApi.discoverySubmit(CLUB_SLUG));
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

test.describe('discovery flight — coupon recipient choice', () => {
  const COUPON_CHOICE = [
    {
      recipient: 'the candidate',
      select: testId.couponToCandidate,
      deselect: testId.couponToInvoiceRecipient,
      posted: false,
    },
    {
      recipient: 'the invoice recipient',
      select: testId.couponToInvoiceRecipient,
      deselect: testId.couponToCandidate,
      posted: true,
    },
  ] as const;

  for (const choice of COUPON_CHOICE) {
    test(`[happy] the coupon goes to ${choice.recipient} when that radio is selected`, async ({
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
      await fillInvoiceAddress(page, payer);

      await expect(
        page.locator('label', { has: page.getByTestId(testId.couponToCandidate) }),
      ).toContainText(`${candidate.firstName} ${candidate.lastName}`);
      await expect(
        page.locator('label', { has: page.getByTestId(testId.couponToInvoiceRecipient) }),
      ).toContainText(`${payer.firstName} ${payer.lastName}`);

      const deselected = page.getByTestId(choice.deselect);
      const selected = page.getByTestId(choice.select);
      await deselected.check();
      await expect(deselected).toBeChecked();
      await selected.check();
      await expect(selected).toBeChecked();
      await expect(deselected).not.toBeChecked();

      await page.getByTestId(testId.submit).click();
      await expect(page.getByTestId(testId.success)).toBeVisible();

      expect(submissions).toHaveLength(1);
      expect(submissions[0]!.body).toMatchObject({
        registrant: {
          invoiceAddressIsSame: false,
          sendCouponToInvoiceAddress: choice.posted,
          invoiceRecipient: { notificationEmail: payer.email },
        },
      });
    });
  }

  test('[edge] no coupon choice renders — or travels — while the invoice address is the visitor own', async ({
    page,
  }) => {
    const submissions: SubmittedRegistration[] = [];
    await stubPublicRegistrationBackend(page, submissions);
    const candidate = registrant();

    await page.goto(discoveryFlightPath(CLUB_SLUG));
    await fillRegistrant(page, candidate);
    await page.getByTestId(testId.dayOption(DISCOVERY_DAYS[0]!)).check();

    await expect(page.getByTestId(testId.couponToCandidate)).toHaveCount(0);
    await expect(page.getByTestId(testId.couponToInvoiceRecipient)).toHaveCount(0);

    await fillInvoiceAddress(page, registrant({ firstName: 'Beat', lastName: 'Frei' }));
    await page.getByTestId(testId.couponToInvoiceRecipient).check();
    await page.getByTestId(testId.invoiceDiffers).uncheck();
    await expect(page.getByTestId(testId.couponToInvoiceRecipient)).toHaveCount(0);

    await page.getByTestId(testId.submit).click();
    await expect(page.getByTestId(testId.success)).toBeVisible();

    expect(submissions).toHaveLength(1);
    const posted = submissions[0]!.body['registrant'] as Record<string, unknown>;
    expect(posted['invoiceAddressIsSame']).toBe(true);
    expect(posted).not.toHaveProperty('sendCouponToInvoiceAddress');
    expect(posted).not.toHaveProperty('invoiceRecipient');
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
    await expect(page.getByTestId(testId.daySelect)).toHaveCount(0);
    await page.screenshot({ path: SHOT.scenicForm, fullPage: true });

    await fillRegistrant(page, passenger);
    await page.getByTestId(testId.submit).click();

    await expect(page.getByTestId(testId.success)).toBeVisible();
    await page.screenshot({ path: SHOT.scenicSuccess, fullPage: true });
    await expect(page).toHaveURL(new RegExp(`${scenicFlightPath(CLUB_SLUG)}$`));

    expect(submissions).toHaveLength(1);
    expect(submissions[0]!.path).toBe(publicApi.scenicSubmit(CLUB_SLUG));
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

test.describe('public registration — mobile-first', () => {
  const MOBILE_PORTRAIT = { width: 360, height: 640 } as const;
  const TOUCH_TARGET_PX = 44;

  interface Box {
    y: number;
    width: number;
    height: number;
  }

  test.use({ viewport: MOBILE_PORTRAIT });

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

    const documentWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(documentWidth).toBeLessThanOrEqual(MOBILE_PORTRAIT.width);

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
    const decoded = decodeURIComponent(page.url());
    for (const value of Object.values(candidate)) {
      expect(decoded, `${value} reached the URL`).not.toContain(value);
    }
  });
});
