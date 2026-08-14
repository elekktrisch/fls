import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import {
  FormBuilder,
  FormControl,
  FormsModule,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';

import type { MePersonUpdateRequest } from '@api/generated/model';
import { liveFieldErrors } from '@shared/util/form';

import { ReferenceDataStore } from '../../core/reference-data/reference-data.store';

import { PersonalStore } from './personal.store';

type PersonalForm = FormGroup<{
  addressLine1: FormControl<string>;
  addressLine2: FormControl<string>;
  zip: FormControl<string>;
  city: FormControl<string>;
  region: FormControl<string>;
  countryId: FormControl<string>;
  privatePhone: FormControl<string>;
  mobilePhone: FormControl<string>;
  businessPhone: FormControl<string>;
  faxNumber: FormControl<string>;
  emailPrivate: FormControl<string>;
  emailBusiness: FormControl<string>;
  preferMailToBusinessMail: FormControl<boolean>;
  birthday: FormControl<string>;
}>;

@Component({
  selector: 'af-profile-personal-tab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    ReactiveFormsModule,
    TranslocoDirective,
    AfButtonComponent,
    AfInputComponent,
    AfSelectComponent,
    AfFormFieldComponent,
  ],
  template: `
    <ng-container *transloco="let t; read: 'profile.personal'">
      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        novalidate
        data-testid="profile-personal-form"
        class="max-w-2xl flex flex-col gap-3"
      >
        <!-- Read-only name fields (admin-owned rename). -->
        <af-form-field [label]="t('firstName')" for="PersonalFirstName">
          <af-input
            inputId="PersonalFirstName"
            [ngModel]="store.view()?.firstName ?? ''"
            [ngModelOptions]="{ standalone: true }"
            [disabled]="true"
            data-testid="profile-personal-firstName"
          />
          <span class="block text-xs text-slate-500 mt-1">{{ t('readOnlyHint') }}</span>
        </af-form-field>

        <af-form-field [label]="t('lastName')" for="PersonalLastName">
          <af-input
            inputId="PersonalLastName"
            [ngModel]="store.view()?.lastName ?? ''"
            [ngModelOptions]="{ standalone: true }"
            [disabled]="true"
            data-testid="profile-personal-lastName"
          />
        </af-form-field>

        <!-- Editable address fields. -->
        <af-form-field [label]="t('address')" for="PersonalAddress" [errors]="addressLine1Errors()">
          <af-input
            inputId="PersonalAddress"
            formControlName="addressLine1"
            autocomplete="address-line1"
            data-testid="profile-personal-address"
          />
        </af-form-field>

        <af-form-field
          [label]="t('addressLine2')"
          for="PersonalAddressLine2"
          [errors]="addressLine2Errors()"
        >
          <af-input
            inputId="PersonalAddressLine2"
            formControlName="addressLine2"
            autocomplete="address-line2"
            data-testid="profile-personal-addressLine2"
          />
        </af-form-field>

        <af-form-field [label]="t('zip')" for="PersonalZip" [errors]="zipErrors()">
          <af-input
            inputId="PersonalZip"
            formControlName="zip"
            autocomplete="postal-code"
            data-testid="profile-personal-zip"
          />
        </af-form-field>

        <af-form-field [label]="t('city')" for="PersonalCity" [errors]="cityErrors()">
          <af-input
            inputId="PersonalCity"
            formControlName="city"
            autocomplete="address-level2"
            data-testid="profile-personal-city"
          />
        </af-form-field>

        <af-form-field [label]="t('region')" for="PersonalRegion" [errors]="regionErrors()">
          <af-input
            inputId="PersonalRegion"
            formControlName="region"
            autocomplete="address-level1"
            data-testid="profile-personal-region"
          />
        </af-form-field>

        <af-form-field [label]="t('country')" for="PersonalCountry">
          <af-select
            inputId="PersonalCountry"
            formControlName="countryId"
            [placeholder]="t('countryPlaceholder')"
            [options]="countryOptions()"
            data-testid="profile-personal-country"
          />
        </af-form-field>

        <!-- Editable contact fields. -->
        <af-form-field
          [label]="t('phonePrivate')"
          for="PersonalPhonePrivate"
          [errors]="privatePhoneErrors()"
        >
          <af-input
            inputId="PersonalPhonePrivate"
            formControlName="privatePhone"
            autocomplete="tel"
            data-testid="profile-personal-phonePrivate"
          />
        </af-form-field>

        <af-form-field
          [label]="t('mobilePhone')"
          for="PersonalMobilePhone"
          [errors]="mobilePhoneErrors()"
        >
          <af-input
            inputId="PersonalMobilePhone"
            formControlName="mobilePhone"
            autocomplete="tel"
            data-testid="profile-personal-mobilePhone"
          />
        </af-form-field>

        <af-form-field
          [label]="t('phoneBusiness')"
          for="PersonalPhoneBusiness"
          [errors]="businessPhoneErrors()"
        >
          <af-input
            inputId="PersonalPhoneBusiness"
            formControlName="businessPhone"
            autocomplete="tel"
            data-testid="profile-personal-phoneBusiness"
          />
        </af-form-field>

        <af-form-field [label]="t('faxNumber')" for="PersonalFax" [errors]="faxNumberErrors()">
          <af-input
            inputId="PersonalFax"
            formControlName="faxNumber"
            data-testid="profile-personal-faxNumber"
          />
        </af-form-field>

        <af-form-field
          [label]="t('emailPrivate')"
          for="PersonalEmailPrivate"
          [errors]="emailPrivateErrors()"
        >
          <af-input
            inputId="PersonalEmailPrivate"
            type="email"
            formControlName="emailPrivate"
            autocomplete="email"
            data-testid="profile-personal-emailPrivate"
          />
        </af-form-field>

        <af-form-field
          [label]="t('emailBusiness')"
          for="PersonalEmailBusiness"
          [errors]="emailBusinessErrors()"
        >
          <af-input
            inputId="PersonalEmailBusiness"
            type="email"
            formControlName="emailBusiness"
            autocomplete="email"
            data-testid="profile-personal-emailBusiness"
          />
        </af-form-field>

        <label class="flex items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            formControlName="preferMailToBusinessMail"
            data-testid="profile-personal-preferMailToBusinessMail"
          />
          {{ t('preferMailToBusinessMail') }}
        </label>

        <af-form-field [label]="t('birthday')" for="PersonalBirthday">
          <af-input
            inputId="PersonalBirthday"
            type="date"
            formControlName="birthday"
            autocomplete="bday"
            data-testid="profile-personal-birthday"
          />
        </af-form-field>

        @if (store.hasError()) {
          <div class="text-sm text-red-600" role="alert" data-testid="profile-personal-error">
            {{ t('saveError') }}
          </div>
        } @else if (store.savedOnce() && !store.isSaving()) {
          <div class="text-sm text-slate-600" role="status" data-testid="profile-personal-saved">
            {{ t('saved') }}
          </div>
        }

        <div class="flex justify-end mt-4 pt-4 border-t border-slate-200">
          <af-button
            type="primary"
            htmlType="submit"
            [disabled]="!store.canSave()"
            data-testid="profile-personal-save"
          >
            {{ t('save') }}
          </af-button>
        </div>
      </form>
    </ng-container>
  `,
})
export class ProfilePersonalTab {
  protected readonly store = inject(PersonalStore);
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly countryOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.referenceData.countries().map((c) => ({ value: c.id, label: c.name ?? c.id })),
  );

  protected readonly form: PersonalForm = this.fb.group({
    addressLine1: this.fb.control('', { validators: [Validators.maxLength(200)] }),
    addressLine2: this.fb.control('', { validators: [Validators.maxLength(200)] }),
    zip: this.fb.control('', { validators: [Validators.maxLength(10)] }),
    city: this.fb.control('', { validators: [Validators.maxLength(100)] }),
    region: this.fb.control('', { validators: [Validators.maxLength(100)] }),
    countryId: this.fb.control(''),
    privatePhone: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    mobilePhone: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    businessPhone: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    faxNumber: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    emailPrivate: this.fb.control('', {
      validators: [Validators.email, Validators.maxLength(256)],
    }),
    emailBusiness: this.fb.control('', {
      validators: [Validators.email, Validators.maxLength(256)],
    }),
    preferMailToBusinessMail: this.fb.control(false),
    birthday: this.fb.control(''),
  });

  protected readonly addressLine1Errors = liveFieldErrors(this.form.controls.addressLine1);
  protected readonly addressLine2Errors = liveFieldErrors(this.form.controls.addressLine2);
  protected readonly zipErrors = liveFieldErrors(this.form.controls.zip);
  protected readonly cityErrors = liveFieldErrors(this.form.controls.city);
  protected readonly regionErrors = liveFieldErrors(this.form.controls.region);
  protected readonly privatePhoneErrors = liveFieldErrors(this.form.controls.privatePhone);
  protected readonly mobilePhoneErrors = liveFieldErrors(this.form.controls.mobilePhone);
  protected readonly businessPhoneErrors = liveFieldErrors(this.form.controls.businessPhone);
  protected readonly faxNumberErrors = liveFieldErrors(this.form.controls.faxNumber);
  protected readonly emailPrivateErrors = liveFieldErrors(this.form.controls.emailPrivate);
  protected readonly emailBusinessErrors = liveFieldErrors(this.form.controls.emailBusiness);

  constructor() {
    effect(() => {
      const view = this.store.view();
      if (view !== null) {
        this.form.patchValue(
          {
            addressLine1: view.addressLine1,
            addressLine2: view.addressLine2,
            zip: view.zip,
            city: view.city,
            region: view.region,
            countryId: view.countryId,
            privatePhone: view.privatePhone,
            mobilePhone: view.mobilePhone,
            businessPhone: view.businessPhone,
            faxNumber: view.faxNumber,
            emailPrivate: view.emailPrivate,
            emailBusiness: view.emailBusiness,
            preferMailToBusinessMail: view.preferMailToBusinessMail,
            birthday: view.birthday,
          },
          { emitEvent: false },
        );
      }
    });

    this.store.load();
  }

  protected onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const req: MePersonUpdateRequest = {
      preferMailToBusinessMail: v.preferMailToBusinessMail,
    };
    setIfNonBlank(req, 'addressLine1', v.addressLine1);
    setIfNonBlank(req, 'addressLine2', v.addressLine2);
    setIfNonBlank(req, 'zip', v.zip);
    setIfNonBlank(req, 'city', v.city);
    setIfNonBlank(req, 'region', v.region);
    setIfNonBlank(req, 'countryId', v.countryId);
    setIfNonBlank(req, 'privatePhone', v.privatePhone);
    setIfNonBlank(req, 'mobilePhone', v.mobilePhone);
    setIfNonBlank(req, 'businessPhone', v.businessPhone);
    setIfNonBlank(req, 'faxNumber', v.faxNumber);
    setIfNonBlank(req, 'emailPrivate', v.emailPrivate);
    setIfNonBlank(req, 'emailBusiness', v.emailBusiness);
    setIfNonBlank(req, 'birthday', v.birthday);
    this.store.save(req);
  }
}

function setIfNonBlank(
  req: MePersonUpdateRequest,
  key: keyof MePersonUpdateRequest,
  value: string,
): void {
  const trimmed = value.trim();
  if (trimmed.length > 0) {
    (req[key] as string) = trimmed;
  }
}
