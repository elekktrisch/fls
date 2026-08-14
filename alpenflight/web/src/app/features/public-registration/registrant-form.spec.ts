import { FormBuilder } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import {
  CONTACT_REQUIRED_ERROR,
  buildRegistrantForm,
  displayName,
  toRegistrantDetails,
  type RegistrantForm,
} from './registrant-form';


const fb = new FormBuilder().nonNullable;

function filledForm(): RegistrantForm {
  const form = buildRegistrantForm(fb);
  form.patchValue({
    firstname: 'Nina',
    lastname: 'Brunner',
    addressLine1: 'Flugplatzstrasse 12',
    zipCode: '8600',
    city: 'Dübendorf',
    privateEmail: 'nina.brunner@example.com',
  });
  return form;
}

const INVOICE_RECIPIENT = {
  firstname: 'Beat',
  lastname: 'Frei',
  addressLine1: 'Bahnhofstrasse 1',
  zipCode: '8001',
  city: 'Zürich',
  notificationEmail: 'beat.frei@example.com',
};

describe('registrant form — mobile-or-email', () => {
  it('is invalid while neither a mobile number nor an email is given', () => {
    const form = filledForm();
    form.patchValue({ privateEmail: '', mobilePhone: '' });

    expect(form.errors).toEqual({ [CONTACT_REQUIRED_ERROR]: true });
    expect(form.valid).toBe(false);
  });

  it('accepts an email alone', () => {
    const form = filledForm();
    form.patchValue({ mobilePhone: '' });

    expect(form.errors).toBeNull();
    expect(form.valid).toBe(true);
  });

  it('accepts a mobile number alone', () => {
    const form = filledForm();
    form.patchValue({ privateEmail: '', mobilePhone: '+41 79 555 21 12' });

    expect(form.errors).toBeNull();
    expect(form.valid).toBe(true);
  });

  it('does not accept whitespace as a contact channel', () => {
    const form = filledForm();
    form.patchValue({ privateEmail: '   ', mobilePhone: '  ' });

    expect(form.errors).toEqual({ [CONTACT_REQUIRED_ERROR]: true });
  });

  it('re-opens the rule when the only channel is cleared again', () => {
    const form = filledForm();
    form.patchValue({ privateEmail: '' });
    expect(form.errors).toEqual({ [CONTACT_REQUIRED_ERROR]: true });

    form.patchValue({ mobilePhone: '+41 79 555 21 12' });
    expect(form.errors).toBeNull();
  });
});

describe('registrant form — conditional invoice block', () => {
  it('ignores the empty invoice block while the invoice address is the same', () => {
    const form = filledForm();

    expect(form.controls.invoice.disabled).toBe(true);
    expect(form.valid).toBe(true);
  });

  it('requires the invoice block once the invoice address differs', () => {
    const form = filledForm();
    form.controls.invoiceAddressDiffers.setValue(true);

    expect(form.controls.invoice.enabled).toBe(true);
    expect(form.valid).toBe(false);
    expect(form.controls.invoice.controls.notificationEmail.errors).toEqual({ required: true });
  });

  it('is valid again once the invoice block is filled', () => {
    const form = filledForm();
    form.controls.invoiceAddressDiffers.setValue(true);
    form.controls.invoice.patchValue(INVOICE_RECIPIENT);

    expect(form.valid).toBe(true);
  });

  it('stops requiring the invoice block when the toggle flips back', () => {
    const form = filledForm();
    form.controls.invoiceAddressDiffers.setValue(true);
    expect(form.valid).toBe(false);

    form.controls.invoiceAddressDiffers.setValue(false);

    expect(form.controls.invoice.disabled).toBe(true);
    expect(form.valid).toBe(true);
  });
});

describe('toRegistrantDetails', () => {
  it('drops the invoice block structurally while the addresses match', () => {
    const form = filledForm();
    form.controls.invoice.patchValue(INVOICE_RECIPIENT);

    const details = toRegistrantDetails(form.getRawValue());

    expect(details.invoiceAddressIsSame).toBe(true);
    expect(details).not.toHaveProperty('invoiceRecipient');
    expect(details).not.toHaveProperty('sendCouponToInvoiceAddress');
  });

  it('carries the invoice recipient and the coupon choice once they differ', () => {
    const form = filledForm();
    form.controls.invoiceAddressDiffers.setValue(true);
    form.controls.invoice.patchValue(INVOICE_RECIPIENT);
    form.controls.sendCouponToInvoiceAddress.setValue(true);

    const details = toRegistrantDetails(form.getRawValue());

    expect(details.invoiceAddressIsSame).toBe(false);
    expect(details.sendCouponToInvoiceAddress).toBe(true);
    expect(details.invoiceRecipient).toEqual({
      firstname: 'Beat',
      lastname: 'Frei',
      addressLine1: 'Bahnhofstrasse 1',
      zip: '8001',
      city: 'Zürich',
      notificationEmail: 'beat.frei@example.com',
    });
  });

  it('trims values and omits the blank optionals', () => {
    const form = filledForm();
    form.patchValue({ firstname: '  Nina  ', remarks: '  Erstflug  ', mobilePhone: '   ' });

    const details = toRegistrantDetails(form.getRawValue());

    expect(details.firstname).toBe('Nina');
    expect(details.remarks).toBe('Erstflug');
    expect(details).not.toHaveProperty('mobilePhone');
    expect(details).not.toHaveProperty('privatePhone');
    expect(details).not.toHaveProperty('businessPhone');
    expect(details).not.toHaveProperty('countryId');
  });

  it('maps the form zip onto the wire name', () => {
    const details = toRegistrantDetails(filledForm().getRawValue());

    expect(details.zip).toBe('8600');
    expect(details).not.toHaveProperty('zipCode');
  });
});

describe('displayName', () => {
  it('joins the parts that are present', () => {
    expect(displayName(' Nina ', 'Brunner')).toBe('Nina Brunner');
    expect(displayName('', 'Brunner')).toBe('Brunner');
    expect(displayName('  ', ' ')).toBe('');
  });
});
