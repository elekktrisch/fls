import { InjectionToken, type Provider, inject } from '@angular/core';
import {
  FormBuilder,
  type AbstractControl,
  type FormControl,
  type FormGroup,
  type NonNullableFormBuilder,
  type ValidationErrors,
  Validators,
} from '@angular/forms';

import type { PublicRegistrantDetails } from '@api/generated/model';
import { withOptionals } from '@shared/util/form';

/**
 * The registrant both public flows submit — the scenic form is the discovery
 * form minus the day selection, so the field set, its rules and its wire
 * mapping are one implementation.
 *
 * The rules mirror `PublicRegistrantDetails`' compact constructor (the
 * server-side contract): address / zip / city required, at least one of mobile
 * phone or private email, and an invoice block that is required only while the
 * invoice address differs. The browser copy is the fast feedback; the server
 * stays the safety step.
 */

/** Column caps of the `Person` aggregate — a longer value is rejected there. */
const MAX_NAME = 100;
const MAX_ADDRESS = 200;
const MAX_ZIP = 10;
const MAX_CITY = 100;
const MAX_PHONE = 30;
const MAX_EMAIL = 256;

/** Error key of the mobile-phone-or-email rule; renders via `common.errors.*`. */
export const CONTACT_REQUIRED_ERROR = 'contactRequired';

export type InvoiceRecipientForm = FormGroup<{
  firstname: FormControl<string>;
  lastname: FormControl<string>;
  addressLine1: FormControl<string>;
  zipCode: FormControl<string>;
  city: FormControl<string>;
  notificationEmail: FormControl<string>;
}>;

export type RegistrantForm = FormGroup<{
  firstname: FormControl<string>;
  lastname: FormControl<string>;
  addressLine1: FormControl<string>;
  zipCode: FormControl<string>;
  city: FormControl<string>;
  privateEmail: FormControl<string>;
  mobilePhone: FormControl<string>;
  privatePhone: FormControl<string>;
  businessPhone: FormControl<string>;
  remarks: FormControl<string>;
  invoiceAddressDiffers: FormControl<boolean>;
  sendCouponToInvoiceAddress: FormControl<boolean>;
  invoice: InvoiceRecipientForm;
}>;

export type RegistrantFormValue = ReturnType<RegistrantForm['getRawValue']>;

/** True when the club can reach the registrant back on at least one channel. */
export function contactReachable(mobilePhone: string, privateEmail: string): boolean {
  return mobilePhone.trim().length > 0 || privateEmail.trim().length > 0;
}

/**
 * Mobile phone and private email are an either-or pair, not two independent
 * optional fields, so the rule sits on the group: neither control can decide
 * on its own whether it is required.
 */
function reachableRegistrantValidator(group: AbstractControl): ValidationErrors | null {
  const mobilePhone = String(group.get('mobilePhone')?.value ?? '');
  const privateEmail = String(group.get('privateEmail')?.value ?? '');
  return contactReachable(mobilePhone, privateEmail) ? null : { [CONTACT_REQUIRED_ERROR]: true };
}

/**
 * A disabled group drops out of both validity and `form.value`, so "the
 * invoice block is required only while the invoice address differs" is one
 * switch rather than a conditional validator repeated on seven controls.
 */
function applyInvoiceRequirement(form: RegistrantForm, differs: boolean): void {
  if (differs) {
    form.controls.invoice.enable();
  } else {
    form.controls.invoice.disable();
  }
}

export function buildRegistrantForm(fb: NonNullableFormBuilder): RegistrantForm {
  const form: RegistrantForm = fb.group(
    {
      firstname: fb.control('', [Validators.required, Validators.maxLength(MAX_NAME)]),
      lastname: fb.control('', [Validators.required, Validators.maxLength(MAX_NAME)]),
      addressLine1: fb.control('', [Validators.required, Validators.maxLength(MAX_ADDRESS)]),
      zipCode: fb.control('', [Validators.required, Validators.maxLength(MAX_ZIP)]),
      city: fb.control('', [Validators.required, Validators.maxLength(MAX_CITY)]),
      privateEmail: fb.control('', [Validators.email, Validators.maxLength(MAX_EMAIL)]),
      mobilePhone: fb.control('', [Validators.maxLength(MAX_PHONE)]),
      privatePhone: fb.control('', [Validators.maxLength(MAX_PHONE)]),
      businessPhone: fb.control('', [Validators.maxLength(MAX_PHONE)]),
      remarks: fb.control(''),
      invoiceAddressDiffers: fb.control(false),
      sendCouponToInvoiceAddress: fb.control(false),
      invoice: fb.group({
        firstname: fb.control('', [Validators.required, Validators.maxLength(MAX_NAME)]),
        lastname: fb.control('', [Validators.required, Validators.maxLength(MAX_NAME)]),
        addressLine1: fb.control('', [Validators.required, Validators.maxLength(MAX_ADDRESS)]),
        zipCode: fb.control('', [Validators.required, Validators.maxLength(MAX_ZIP)]),
        city: fb.control('', [Validators.required, Validators.maxLength(MAX_CITY)]),
        notificationEmail: fb.control('', [
          Validators.required,
          Validators.email,
          Validators.maxLength(MAX_EMAIL),
        ]),
      }),
    },
    { validators: [reachableRegistrantValidator] },
  );

  applyInvoiceRequirement(form, form.controls.invoiceAddressDiffers.value);
  // Reachable only from the form's own object graph, so it is collected with
  // the form — no teardown handle to thread through the callers.
  form.controls.invoiceAddressDiffers.valueChanges.subscribe((differs) =>
    applyInvoiceRequirement(form, differs),
  );
  return form;
}

/**
 * The form instance shared by a public page and the registrant fieldset it
 * renders. Provided rather than passed as an input because `liveFieldErrors`
 * runs in a field initializer, where a signal input is not yet set.
 */
export const REGISTRANT_FORM = new InjectionToken<RegistrantForm>('REGISTRANT_FORM');

export function provideRegistrantForm(): Provider {
  return {
    provide: REGISTRANT_FORM,
    useFactory: () => buildRegistrantForm(inject(FormBuilder).nonNullable),
  };
}

/** "Nina Brunner" — the candidate / invoice-recipient labels of the coupon choice. */
export function displayName(firstname: string, lastname: string): string {
  return [firstname.trim(), lastname.trim()].filter((part) => part.length > 0).join(' ');
}

/**
 * Map the form onto the wire contract. `countryId` is deliberately unset:
 * there is no anonymous countries catalog to pick from and legacy's form has
 * no country field either, so the optional stays absent rather than guessed.
 */
export function toRegistrantDetails(value: RegistrantFormValue): PublicRegistrantDetails {
  const contact = {
    privateEmail: value.privateEmail.trim(),
    mobilePhone: value.mobilePhone.trim(),
    privatePhone: value.privatePhone.trim(),
    businessPhone: value.businessPhone.trim(),
    remarks: value.remarks.trim(),
  };
  const registrant = {
    firstname: value.firstname.trim(),
    lastname: value.lastname.trim(),
    addressLine1: value.addressLine1.trim(),
    zip: value.zipCode.trim(),
    city: value.city.trim(),
    invoiceAddressIsSame: !value.invoiceAddressDiffers,
  };

  // Same address: the block is dropped structurally, matching the server's
  // compact constructor — whatever was typed before the toggle flipped back
  // cannot reach the writer and mint a second Person.
  if (!value.invoiceAddressDiffers) {
    return withOptionals(registrant, contact) as PublicRegistrantDetails;
  }

  return withOptionals(
    {
      ...registrant,
      sendCouponToInvoiceAddress: value.sendCouponToInvoiceAddress,
      invoiceRecipient: {
        firstname: value.invoice.firstname.trim(),
        lastname: value.invoice.lastname.trim(),
        addressLine1: value.invoice.addressLine1.trim(),
        zip: value.invoice.zipCode.trim(),
        city: value.invoice.city.trim(),
        notificationEmail: value.invoice.notificationEmail.trim(),
      },
    },
    contact,
  ) as PublicRegistrantDetails;
}
