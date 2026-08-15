import { InjectionToken, type Provider, type Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  type AbstractControl,
  type FormControl,
  type FormGroup,
  type NonNullableFormBuilder,
  type ValidationErrors,
  Validators,
} from '@angular/forms';
import { map, startWith } from 'rxjs';

import type { PublicRegistrantDetails } from '@api/generated/model';
import { withOptionals } from '@shared/util/form';

const MAX_NAME = 100;
const MAX_ADDRESS = 200;
const MAX_ZIP = 10;
const MAX_CITY = 100;
const MAX_PHONE = 30;
const MAX_EMAIL = 256;

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

export function contactReachable(mobilePhone: string, privateEmail: string): boolean {
  return mobilePhone.trim().length > 0 || privateEmail.trim().length > 0;
}

function reachableRegistrantValidator(group: AbstractControl): ValidationErrors | null {
  const mobilePhone = String(group.get('mobilePhone')?.value ?? '');
  const privateEmail = String(group.get('privateEmail')?.value ?? '');
  return contactReachable(mobilePhone, privateEmail) ? null : { [CONTACT_REQUIRED_ERROR]: true };
}

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
  form.controls.invoiceAddressDiffers.valueChanges.subscribe((differs) =>
    applyInvoiceRequirement(form, differs),
  );
  return form;
}

export const REGISTRANT_FORM = new InjectionToken<RegistrantForm>('REGISTRANT_FORM');

export function provideRegistrantForm(): Provider {
  return {
    provide: REGISTRANT_FORM,
    useFactory: () => buildRegistrantForm(inject(FormBuilder).nonNullable),
  };
}

export function registrantFormInvalid(form: RegistrantForm): Signal<boolean> {
  return toSignal(
    form.statusChanges.pipe(
      startWith(form.status),
      map(() => form.invalid),
    ),
    { requireSync: true },
  );
}

export function displayName(firstname: string, lastname: string): string {
  return [firstname.trim(), lastname.trim()].filter((part) => part.length > 0).join(' ');
}

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
