import { ChangeDetectionStrategy, Component, type Signal, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { type AbstractControl, ReactiveFormsModule, type ValidationErrors } from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';
import { map, merge, startWith } from 'rxjs';

import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFieldErrorsComponent } from '@ui/molecules/af-field-errors';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';

import { liveFieldErrors } from '@shared/util/form';

import { REGISTRANT_FORM, type RegistrantForm, displayName } from './registrant-form';

function engagedFieldErrors(
  control: AbstractControl,
  gates: readonly AbstractControl[] = [control],
): Signal<ValidationErrors | null> {
  const errors = liveFieldErrors(control);
  const isEngaged = (): boolean => gates.some((g) => g.dirty || g.touched);
  const engaged = toSignal(
    merge(...gates.map((g) => g.events)).pipe(map(isEngaged), startWith(isEngaged())),
    { requireSync: true },
  );
  return computed(() => (engaged() ? errors() : null));
}

function registrantFieldErrors(form: RegistrantForm) {
  const invoice = form.controls.invoice.controls;
  return {
    firstname: engagedFieldErrors(form.controls.firstname),
    lastname: engagedFieldErrors(form.controls.lastname),
    addressLine1: engagedFieldErrors(form.controls.addressLine1),
    zipCode: engagedFieldErrors(form.controls.zipCode),
    city: engagedFieldErrors(form.controls.city),
    privateEmail: engagedFieldErrors(form.controls.privateEmail),
    mobilePhone: engagedFieldErrors(form.controls.mobilePhone),
    privatePhone: engagedFieldErrors(form.controls.privatePhone),
    businessPhone: engagedFieldErrors(form.controls.businessPhone),
    contact: engagedFieldErrors(form, [form.controls.mobilePhone, form.controls.privateEmail]),
    invoiceFirstname: engagedFieldErrors(invoice.firstname),
    invoiceLastname: engagedFieldErrors(invoice.lastname),
    invoiceAddressLine1: engagedFieldErrors(invoice.addressLine1),
    invoiceZipCode: engagedFieldErrors(invoice.zipCode),
    invoiceCity: engagedFieldErrors(invoice.city),
    notificationEmail: engagedFieldErrors(invoice.notificationEmail),
  };
}

@Component({
  selector: 'af-registrant-fieldset',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    TranslocoDirective,
    AfInputComponent,
    AfFormFieldComponent,
    AfFieldErrorsComponent,
  ],
  template: `
    <ng-container *transloco="let t; read: 'publicRegistration'">
      <div [formGroup]="form" class="flex flex-col" data-testid="public-registration-registrant">
        <af-form-field
          [label]="t('fields.firstname')"
          for="firstName"
          [required]="true"
          [errors]="errors.firstname()"
        >
          <af-input inputId="firstName" formControlName="firstname" autocomplete="given-name" />
        </af-form-field>

        <af-form-field
          [label]="t('fields.lastname')"
          for="lastName"
          [required]="true"
          [errors]="errors.lastname()"
        >
          <af-input inputId="lastName" formControlName="lastname" autocomplete="family-name" />
        </af-form-field>

        <af-form-field
          [label]="t('fields.addressLine1')"
          for="addressLine1"
          [required]="true"
          [errors]="errors.addressLine1()"
        >
          <af-input
            inputId="addressLine1"
            formControlName="addressLine1"
            autocomplete="street-address"
          />
        </af-form-field>

        <af-form-field
          [label]="t('fields.zipCode')"
          for="zipCode"
          [required]="true"
          [errors]="errors.zipCode()"
        >
          <af-input
            inputId="zipCode"
            formControlName="zipCode"
            inputmode="numeric"
            autocomplete="postal-code"
          />
        </af-form-field>

        <af-form-field
          [label]="t('fields.city')"
          for="city"
          [required]="true"
          [errors]="errors.city()"
        >
          <af-input inputId="city" formControlName="city" autocomplete="address-level2" />
        </af-form-field>

        <fieldset class="border border-slate-200 p-3 mb-4">
          <legend class="text-sm text-slate-600 px-1">{{ t('sections.contact') }}</legend>
          <p class="m-0 mb-3 text-sm text-slate-500">{{ t('contactHint') }}</p>

          <af-form-field
            [label]="t('fields.privateEmail')"
            for="privateEmail"
            [errors]="errors.privateEmail()"
          >
            <af-input
              inputId="privateEmail"
              type="email"
              inputmode="email"
              formControlName="privateEmail"
              autocomplete="email"
            />
          </af-form-field>

          <af-form-field
            [label]="t('fields.mobilePhone')"
            for="mobilePhone"
            [errors]="errors.mobilePhone()"
          >
            <af-input
              inputId="mobilePhone"
              type="tel"
              inputmode="tel"
              formControlName="mobilePhone"
              autocomplete="mobile tel"
            />
          </af-form-field>

          <af-field-errors
            [errors]="errors.contact()"
            data-testid="public-registration-contact-error"
          />

          <af-form-field
            [label]="t('fields.privatePhone')"
            for="privatePhone"
            [errors]="errors.privatePhone()"
          >
            <af-input
              inputId="privatePhone"
              type="tel"
              inputmode="tel"
              formControlName="privatePhone"
              autocomplete="home tel"
            />
          </af-form-field>

          <af-form-field
            [label]="t('fields.businessPhone')"
            for="businessPhone"
            [errors]="errors.businessPhone()"
          >
            <af-input
              inputId="businessPhone"
              type="tel"
              inputmode="tel"
              formControlName="businessPhone"
              autocomplete="work tel"
            />
          </af-form-field>
        </fieldset>

        <af-form-field [label]="t('fields.remarks')" for="remarks">
          <textarea
            id="remarks"
            rows="3"
            formControlName="remarks"
            data-testid="public-registration-remarks"
            class="w-full px-3 py-2 bg-white border border-slate-300 text-slate-900 placeholder:text-slate-400 focus:border-brand-500 focus:outline-hidden"
          ></textarea>
        </af-form-field>

        <label class="flex items-center gap-2 min-h-11 text-sm text-slate-700">
          <input
            type="checkbox"
            formControlName="invoiceAddressDiffers"
            data-testid="public-registration-invoice-differs"
            class="w-5 h-5"
          />
          {{ t('fields.invoiceDiffers') }}
        </label>

        @if (invoiceDiffers()) {
          <div
            class="mt-3 border border-slate-200 p-3"
            data-testid="public-registration-invoice-fieldset"
          >
            <h2 class="m-0 mb-3 text-sm font-medium text-slate-700">
              {{ t('sections.invoice') }}
            </h2>

            <fieldset class="mb-4">
              <legend class="text-sm text-slate-600">{{ t('fields.sendCouponTo') }}</legend>
              <label class="flex items-center gap-2 min-h-11 text-sm text-slate-700">
                <input
                  type="radio"
                  [value]="false"
                  formControlName="sendCouponToInvoiceAddress"
                  data-testid="public-registration-coupon-candidate"
                  class="w-5 h-5"
                />
                <span>{{ t('fields.couponToCandidate') }}</span>
                <em class="text-slate-500">{{ candidateName() }}</em>
              </label>
              <label class="flex items-center gap-2 min-h-11 text-sm text-slate-700">
                <input
                  type="radio"
                  [value]="true"
                  formControlName="sendCouponToInvoiceAddress"
                  data-testid="public-registration-coupon-invoice-recipient"
                  class="w-5 h-5"
                />
                <span>{{ t('fields.couponToInvoiceRecipient') }}</span>
                <em class="text-slate-500">{{ invoiceRecipientName() }}</em>
              </label>
            </fieldset>

            <div formGroupName="invoice">
              <af-form-field
                [label]="t('fields.invoiceFirstname')"
                for="invoiceFirstName"
                [required]="true"
                [errors]="errors.invoiceFirstname()"
              >
                <af-input inputId="invoiceFirstName" formControlName="firstname" />
              </af-form-field>

              <af-form-field
                [label]="t('fields.invoiceLastname')"
                for="invoiceLastName"
                [required]="true"
                [errors]="errors.invoiceLastname()"
              >
                <af-input inputId="invoiceLastName" formControlName="lastname" />
              </af-form-field>

              <af-form-field
                [label]="t('fields.invoiceAddressLine1')"
                for="invoiceAddressLine1"
                [required]="true"
                [errors]="errors.invoiceAddressLine1()"
              >
                <af-input inputId="invoiceAddressLine1" formControlName="addressLine1" />
              </af-form-field>

              <af-form-field
                [label]="t('fields.invoiceZipCode')"
                for="invoiceZipCode"
                [required]="true"
                [errors]="errors.invoiceZipCode()"
              >
                <af-input inputId="invoiceZipCode" formControlName="zipCode" inputmode="numeric" />
              </af-form-field>

              <af-form-field
                [label]="t('fields.invoiceCity')"
                for="invoiceCity"
                [required]="true"
                [errors]="errors.invoiceCity()"
              >
                <af-input inputId="invoiceCity" formControlName="city" />
              </af-form-field>

              <af-form-field
                [label]="t('fields.notificationEmail')"
                for="notificationEmail"
                [required]="true"
                [errors]="errors.notificationEmail()"
              >
                <af-input
                  inputId="notificationEmail"
                  type="email"
                  inputmode="email"
                  formControlName="notificationEmail"
                  autocomplete="email"
                />
              </af-form-field>
            </div>
          </div>
        }
      </div>
    </ng-container>
  `,
})
export class RegistrantFieldsetComponent {
  protected readonly form = inject(REGISTRANT_FORM);
  protected readonly errors = registrantFieldErrors(this.form);

  protected readonly invoiceDiffers = toSignal(
    this.form.controls.invoiceAddressDiffers.valueChanges,
    { initialValue: this.form.controls.invoiceAddressDiffers.value },
  );

  readonly #value = toSignal(
    this.form.valueChanges.pipe(
      startWith(null),
      map(() => this.form.getRawValue()),
    ),
    { requireSync: true },
  );

  protected readonly candidateName = computed(() =>
    displayName(this.#value().firstname, this.#value().lastname),
  );
  protected readonly invoiceRecipientName = computed(() =>
    displayName(this.#value().invoice.firstname, this.#value().invoice.lastname),
  );
}
