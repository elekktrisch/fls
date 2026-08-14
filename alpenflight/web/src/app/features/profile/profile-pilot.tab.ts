import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
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
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';

import type { MePersonLicencesUpdateRequest } from '@api/generated/model';
import { liveFieldErrors } from '@shared/util/form';

import { PilotStore } from './pilot.store';

type PilotForm = FormGroup<{
  hasGliderPilotLicence: FormControl<boolean>;
  hasGliderTraineeLicence: FormControl<boolean>;
  hasGliderInstructorLicence: FormControl<boolean>;
  hasGliderPaxLicence: FormControl<boolean>;
  hasMotorPilotLicence: FormControl<boolean>;
  hasMotorInstructorLicence: FormControl<boolean>;
  hasTowPilotLicence: FormControl<boolean>;
  hasTmgLicence: FormControl<boolean>;
  hasWinchOperatorLicence: FormControl<boolean>;
  hasPartMLicence: FormControl<boolean>;
  licenceNumber: FormControl<string>;
  medicalClass1ExpireDate: FormControl<string>;
  medicalClass2ExpireDate: FormControl<string>;
  medicalLaplExpireDate: FormControl<string>;
  gliderInstructorLicenceExpireDate: FormControl<string>;
  motorInstructorLicenceExpireDate: FormControl<string>;
  partMLicenceExpireDate: FormControl<string>;
  hasGliderTowingStartPermission: FormControl<boolean>;
  hasGliderSelfStartPermission: FormControl<boolean>;
  hasGliderWinchStartPermission: FormControl<boolean>;
  receiveOwnedAircraftStatisticReports: FormControl<boolean>;
}>;

@Component({
  selector: 'af-profile-pilot-tab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    ReactiveFormsModule,
    TranslocoDirective,
    AfButtonComponent,
    AfInputComponent,
    AfFormFieldComponent,
  ],
  template: `
    <ng-container *transloco="let t; read: 'profile.pilot'">
      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        novalidate
        data-testid="profile-pilot-form"
        class="max-w-2xl flex flex-col gap-6"
      >
        <!-- Licences ------------------------------------------------------- -->
        <fieldset class="flex flex-col gap-2">
          <legend class="mb-2 text-sm font-medium text-slate-900">{{ t('licencesGroup') }}</legend>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasGliderPilotLicence"
              data-testid="profile-pilot-licence-glider"
            />
            {{ t('hasGliderPilotLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasGliderTraineeLicence"
              data-testid="profile-pilot-licence-trainee"
            />
            {{ t('hasGliderTraineeLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasGliderInstructorLicence"
              data-testid="profile-pilot-licence-gliderInstructor"
            />
            {{ t('hasGliderInstructorLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasGliderPaxLicence"
              data-testid="profile-pilot-licence-gliderPax"
            />
            {{ t('hasGliderPaxLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasMotorPilotLicence"
              data-testid="profile-pilot-licence-motor"
            />
            {{ t('hasMotorPilotLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasMotorInstructorLicence"
              data-testid="profile-pilot-licence-motorInstructor"
            />
            {{ t('hasMotorInstructorLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasTowPilotLicence"
              data-testid="profile-pilot-licence-tow"
            />
            {{ t('hasTowPilotLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasTmgLicence"
              data-testid="profile-pilot-licence-tmg"
            />
            {{ t('hasTmgLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasWinchOperatorLicence"
              data-testid="profile-pilot-licence-winchOperator"
            />
            {{ t('hasWinchOperatorLicence') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasPartMLicence"
              data-testid="profile-pilot-licence-partM"
            />
            {{ t('hasPartMLicence') }}
          </label>

          <af-form-field
            [label]="t('licenceNumber')"
            for="PilotLicenceNumber"
            [errors]="licenceNumberErrors()"
            class="mt-2"
          >
            <af-input
              inputId="PilotLicenceNumber"
              formControlName="licenceNumber"
              data-testid="profile-pilot-licenceNumber"
            />
          </af-form-field>
        </fieldset>

        <!-- Medical + licence expiries ------------------------------------- -->
        <fieldset class="flex flex-col gap-3">
          <legend class="mb-2 text-sm font-medium text-slate-900">{{ t('medicalGroup') }}</legend>

          <af-form-field [label]="t('medicalClass2ExpireDate')" for="PilotMedicalClass2">
            <af-input
              inputId="PilotMedicalClass2"
              type="date"
              formControlName="medicalClass2ExpireDate"
              data-testid="profile-pilot-medical-expiry"
            />
          </af-form-field>

          <af-form-field [label]="t('medicalClass1ExpireDate')" for="PilotMedicalClass1">
            <af-input
              inputId="PilotMedicalClass1"
              type="date"
              formControlName="medicalClass1ExpireDate"
              data-testid="profile-pilot-medical-class1-expiry"
            />
          </af-form-field>

          <af-form-field [label]="t('medicalLaplExpireDate')" for="PilotMedicalLapl">
            <af-input
              inputId="PilotMedicalLapl"
              type="date"
              formControlName="medicalLaplExpireDate"
              data-testid="profile-pilot-medical-lapl-expiry"
            />
          </af-form-field>

          <af-form-field
            [label]="t('gliderInstructorLicenceExpireDate')"
            for="PilotGliderInstructorExpiry"
          >
            <af-input
              inputId="PilotGliderInstructorExpiry"
              type="date"
              formControlName="gliderInstructorLicenceExpireDate"
              data-testid="profile-pilot-gliderInstructor-expiry"
            />
          </af-form-field>

          <af-form-field
            [label]="t('motorInstructorLicenceExpireDate')"
            for="PilotMotorInstructorExpiry"
          >
            <af-input
              inputId="PilotMotorInstructorExpiry"
              type="date"
              formControlName="motorInstructorLicenceExpireDate"
              data-testid="profile-pilot-motorInstructor-expiry"
            />
          </af-form-field>

          <af-form-field [label]="t('partMLicenceExpireDate')" for="PilotPartMExpiry">
            <af-input
              inputId="PilotPartMExpiry"
              type="date"
              formControlName="partMLicenceExpireDate"
              data-testid="profile-pilot-partM-expiry"
            />
          </af-form-field>
        </fieldset>

        <!-- Permissions + reports ------------------------------------------ -->
        <fieldset class="flex flex-col gap-2">
          <legend class="mb-2 text-sm font-medium text-slate-900">
            {{ t('permissionsGroup') }}
          </legend>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasGliderTowingStartPermission"
              data-testid="profile-pilot-permission-towing"
            />
            {{ t('hasGliderTowingStartPermission') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasGliderSelfStartPermission"
              data-testid="profile-pilot-permission-self"
            />
            {{ t('hasGliderSelfStartPermission') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="hasGliderWinchStartPermission"
              data-testid="profile-pilot-permission-winch"
            />
            {{ t('hasGliderWinchStartPermission') }}
          </label>

          <label class="mt-2 flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="receiveOwnedAircraftStatisticReports"
              data-testid="profile-pilot-receiveOwnedAircraftStatisticReports"
            />
            {{ t('receiveOwnedAircraftStatisticReports') }}
          </label>
        </fieldset>

        @if (store.hasError()) {
          <div class="text-sm text-red-600" role="alert" data-testid="profile-pilot-error">
            {{ t('saveError') }}
          </div>
        } @else if (store.savedOnce() && !store.isSaving()) {
          <div class="text-sm text-slate-600" role="status" data-testid="profile-pilot-saved">
            {{ t('saved') }}
          </div>
        }

        <div class="flex justify-end pt-4 border-t border-slate-200">
          <af-button
            type="primary"
            htmlType="submit"
            [disabled]="!store.canSave()"
            data-testid="profile-pilot-save"
          >
            {{ t('save') }}
          </af-button>
        </div>
      </form>
    </ng-container>
  `,
})
export class ProfilePilotTab {
  protected readonly store = inject(PilotStore);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly form: PilotForm = this.fb.group({
    hasGliderPilotLicence: this.fb.control(false),
    hasGliderTraineeLicence: this.fb.control(false),
    hasGliderInstructorLicence: this.fb.control(false),
    hasGliderPaxLicence: this.fb.control(false),
    hasMotorPilotLicence: this.fb.control(false),
    hasMotorInstructorLicence: this.fb.control(false),
    hasTowPilotLicence: this.fb.control(false),
    hasTmgLicence: this.fb.control(false),
    hasWinchOperatorLicence: this.fb.control(false),
    hasPartMLicence: this.fb.control(false),
    licenceNumber: this.fb.control('', { validators: [Validators.maxLength(20)] }),
    medicalClass1ExpireDate: this.fb.control(''),
    medicalClass2ExpireDate: this.fb.control(''),
    medicalLaplExpireDate: this.fb.control(''),
    gliderInstructorLicenceExpireDate: this.fb.control(''),
    motorInstructorLicenceExpireDate: this.fb.control(''),
    partMLicenceExpireDate: this.fb.control(''),
    hasGliderTowingStartPermission: this.fb.control(false),
    hasGliderSelfStartPermission: this.fb.control(false),
    hasGliderWinchStartPermission: this.fb.control(false),
    receiveOwnedAircraftStatisticReports: this.fb.control(false),
  });

  protected readonly licenceNumberErrors = liveFieldErrors(this.form.controls.licenceNumber);

  constructor() {
    effect(() => {
      const view = this.store.view();
      if (view !== null) {
        this.form.patchValue(
          {
            hasGliderPilotLicence: view.hasGliderPilotLicence,
            hasGliderTraineeLicence: view.hasGliderTraineeLicence,
            hasGliderInstructorLicence: view.hasGliderInstructorLicence,
            hasGliderPaxLicence: view.hasGliderPaxLicence,
            hasMotorPilotLicence: view.hasMotorPilotLicence,
            hasMotorInstructorLicence: view.hasMotorInstructorLicence,
            hasTowPilotLicence: view.hasTowPilotLicence,
            hasTmgLicence: view.hasTmgLicence,
            hasWinchOperatorLicence: view.hasWinchOperatorLicence,
            hasPartMLicence: view.hasPartMLicence,
            licenceNumber: view.licenceNumber,
            medicalClass1ExpireDate: view.medicalClass1ExpireDate,
            medicalClass2ExpireDate: view.medicalClass2ExpireDate,
            medicalLaplExpireDate: view.medicalLaplExpireDate,
            gliderInstructorLicenceExpireDate: view.gliderInstructorLicenceExpireDate,
            motorInstructorLicenceExpireDate: view.motorInstructorLicenceExpireDate,
            partMLicenceExpireDate: view.partMLicenceExpireDate,
            hasGliderTowingStartPermission: view.hasGliderTowingStartPermission,
            hasGliderSelfStartPermission: view.hasGliderSelfStartPermission,
            hasGliderWinchStartPermission: view.hasGliderWinchStartPermission,
            receiveOwnedAircraftStatisticReports: view.receiveOwnedAircraftStatisticReports,
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
    const req: MePersonLicencesUpdateRequest = {
      hasGliderPilotLicence: v.hasGliderPilotLicence,
      hasGliderTraineeLicence: v.hasGliderTraineeLicence,
      hasGliderInstructorLicence: v.hasGliderInstructorLicence,
      hasGliderPaxLicence: v.hasGliderPaxLicence,
      hasMotorPilotLicence: v.hasMotorPilotLicence,
      hasMotorInstructorLicence: v.hasMotorInstructorLicence,
      hasTowPilotLicence: v.hasTowPilotLicence,
      hasTmgLicence: v.hasTmgLicence,
      hasWinchOperatorLicence: v.hasWinchOperatorLicence,
      hasPartMLicence: v.hasPartMLicence,
      hasGliderTowingStartPermission: v.hasGliderTowingStartPermission,
      hasGliderSelfStartPermission: v.hasGliderSelfStartPermission,
      hasGliderWinchStartPermission: v.hasGliderWinchStartPermission,
      receiveOwnedAircraftStatisticReports: v.receiveOwnedAircraftStatisticReports,
    };
    setIfNonBlank(req, 'licenceNumber', v.licenceNumber);
    setIfNonBlank(req, 'medicalClass1ExpireDate', v.medicalClass1ExpireDate);
    setIfNonBlank(req, 'medicalClass2ExpireDate', v.medicalClass2ExpireDate);
    setIfNonBlank(req, 'medicalLaplExpireDate', v.medicalLaplExpireDate);
    setIfNonBlank(req, 'gliderInstructorLicenceExpireDate', v.gliderInstructorLicenceExpireDate);
    setIfNonBlank(req, 'motorInstructorLicenceExpireDate', v.motorInstructorLicenceExpireDate);
    setIfNonBlank(req, 'partMLicenceExpireDate', v.partMLicenceExpireDate);
    this.store.save(req);
  }
}

function setIfNonBlank(
  req: MePersonLicencesUpdateRequest,
  key: keyof MePersonLicencesUpdateRequest,
  value: string,
): void {
  const trimmed = value.trim();
  if (trimmed.length > 0) {
    (req[key] as string) = trimmed;
  }
}
