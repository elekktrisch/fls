import { type Page } from '@playwright/test';

/**
 * One selector + endpoint vocabulary shared by both J-17 fidelities (the
 * mock inner-loop spec and the real-idp proof spec). Driving the same testids
 * from both means a renamed control breaks them together instead of letting
 * the cheap one drift green while the real chain rots.
 */

export const CLUB_SLUG = 'alpine-soaring';
export const DISABLED_CLUB_SLUG = 'registration-closed-club';
export const UNKNOWN_CLUB_SLUG = 'no-such-club';

export const discoveryFlightPath = (slug: string): string => `/discovery-flight/${slug}`;
export const scenicFlightPath = (slug: string): string => `/scenic-flight/${slug}`;

/**
 * The unauthenticated surface. Kept under a `/public/` segment so the
 * `permitAll()` matchers stay a single prefix rather than a growing list of
 * individually-exempted paths.
 */
export const publicApi = {
  discoveryDays: (slug: string): string => `/api/v1/public/clubs/${slug}/discovery-flight-days`,
  discoverySubmit: (slug: string): string =>
    `/api/v1/public/clubs/${slug}/discovery-flight-registrations`,
  scenicSubmit: (slug: string): string =>
    `/api/v1/public/clubs/${slug}/scenic-flight-registrations`,
} as const;

/** `data-testid`s the two public forms and their shared shell expose. */
export const testId = {
  discoveryPage: 'discovery-flight-page',
  scenicPage: 'scenic-flight-page',
  form: 'public-registration-form',
  clubName: 'public-registration-club-name',
  submit: 'public-registration-submit',
  success: 'public-registration-success',
  notFound: 'public-registration-not-found',
  unavailable: 'public-registration-unavailable',
  throttled: 'public-registration-throttled',
  invoiceDiffers: 'public-registration-invoice-differs',
  invoiceFieldset: 'public-registration-invoice-fieldset',
  daySelect: 'discovery-day-select',
  dayOption: (isoDate: string): string => `discovery-day-option-${isoDate}`,
} as const;

/** Reactive-form control ids, label-associated through `<af-form-field>`. */
export const fieldId = {
  firstName: '#firstName',
  lastName: '#lastName',
  addressLine1: '#addressLine1',
  zipCode: '#zipCode',
  city: '#city',
  privateEmail: '#privateEmail',
  mobilePhone: '#mobilePhone',
  dateOfBirth: '#dateOfBirth',
  remarks: '#remarks',
  invoiceFirstName: '#invoiceFirstName',
  invoiceLastName: '#invoiceLastName',
  invoiceAddressLine1: '#invoiceAddressLine1',
  invoiceZipCode: '#invoiceZipCode',
  invoiceCity: '#invoiceCity',
  notificationEmail: '#notificationEmail',
} as const;

export interface Registrant {
  firstName: string;
  lastName: string;
  addressLine1: string;
  zipCode: string;
  city: string;
  email: string;
  mobilePhone: string;
  dateOfBirth: string;
  remarks: string;
}

export function registrant(overrides: Partial<Registrant> = {}): Registrant {
  return {
    firstName: 'Nina',
    lastName: 'Brunner',
    addressLine1: 'Flugplatzstrasse 12',
    zipCode: '8600',
    city: 'Dübendorf',
    email: 'nina.brunner@example.com',
    mobilePhone: '+41 79 555 21 12',
    dateOfBirth: '1994-05-17',
    remarks: 'Erstflug, Geschenk',
    ...overrides,
  };
}

/** The registrant fieldset both forms share. */
export async function fillRegistrant(page: Page, r: Registrant): Promise<void> {
  await page.locator(fieldId.firstName).fill(r.firstName);
  await page.locator(fieldId.lastName).fill(r.lastName);
  await page.locator(fieldId.addressLine1).fill(r.addressLine1);
  await page.locator(fieldId.zipCode).fill(r.zipCode);
  await page.locator(fieldId.city).fill(r.city);
  await page.locator(fieldId.privateEmail).fill(r.email);
  await page.locator(fieldId.mobilePhone).fill(r.mobilePhone);
  await page.locator(fieldId.dateOfBirth).fill(r.dateOfBirth);
  await page.locator(fieldId.remarks).fill(r.remarks);
}

/**
 * The invoice block, revealed by the "invoice address differs" toggle. Its
 * `notificationEmail` is the recipient the confirmation switches to, which is
 * the branch the parity assertion turns on.
 */
export async function fillInvoiceAddress(page: Page, r: Registrant): Promise<void> {
  await page.getByTestId(testId.invoiceDiffers).check();
  await page.locator(fieldId.invoiceFirstName).fill(r.firstName);
  await page.locator(fieldId.invoiceLastName).fill(r.lastName);
  await page.locator(fieldId.invoiceAddressLine1).fill(r.addressLine1);
  await page.locator(fieldId.invoiceZipCode).fill(r.zipCode);
  await page.locator(fieldId.invoiceCity).fill(r.city);
  await page.locator(fieldId.notificationEmail).fill(r.email);
}
