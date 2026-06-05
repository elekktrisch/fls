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
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';

import type { MeProfileUpdateRequest } from '@api/generated/model';
import { LANGUAGE_OPTIONS } from '@shared/ui/locale';

import { AccountStore } from './account.store';

type AccountForm = FormGroup<{
  friendlyName: FormControl<string>;
  notificationEmail: FormControl<string>;
  phoneNumber: FormControl<string>;
  languageId: FormControl<string>;
}>;

/**
 * Account tab of `/profile` (J-4 T-05). Edits the caller's own User aggregate
 * self-fields — friendlyName, notificationEmail, phoneNumber, languageId — via
 * {@code PATCH /api/v1/me/profile}. Username + clubId render read-only (they
 * are identity-binding / admin-owned). A saved language change flips the SPA's
 * active locale (the {@link AccountStore} drives {@code LocaleService}).
 *
 * testid contract (T-01 spec): the editable controls carry
 * `profile-account-{friendlyName,notificationEmail,phone,language}`.
 */
@Component({
  selector: 'af-profile-account-tab',
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
    <ng-container *transloco="let t; read: 'profile.account'">
      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        novalidate
        data-testid="profile-account-form"
        class="max-w-2xl flex flex-col gap-3"
      >
        <!-- Read-only identity fields (admin / Keycloak owned). -->
        <af-form-field [label]="t('username')" for="ProfileUsername">
          <af-input
            inputId="ProfileUsername"
            [ngModel]="store.view()?.username ?? ''"
            [ngModelOptions]="{ standalone: true }"
            [disabled]="true"
            data-testid="profile-account-username"
          />
          <span class="block text-xs text-slate-500 mt-1">{{ t('readOnlyHint') }}</span>
        </af-form-field>

        <af-form-field [label]="t('clubId')" for="ProfileClubId">
          <af-input
            inputId="ProfileClubId"
            [ngModel]="store.view()?.clubId ?? ''"
            [ngModelOptions]="{ standalone: true }"
            [disabled]="true"
            data-testid="profile-account-clubId"
          />
        </af-form-field>

        <!-- Editable self-fields. -->
        <af-form-field
          [label]="t('friendlyName')"
          for="ProfileFriendlyName"
          [required]="true"
          [errors]="form.controls.friendlyName.touched ? form.controls.friendlyName.errors : null"
        >
          <af-input
            inputId="ProfileFriendlyName"
            formControlName="friendlyName"
            autocomplete="name"
            data-testid="profile-account-friendlyName"
          />
        </af-form-field>

        <af-form-field
          [label]="t('notificationEmail')"
          for="ProfileNotificationEmail"
          [required]="true"
          [errors]="
            form.controls.notificationEmail.touched ? form.controls.notificationEmail.errors : null
          "
        >
          <af-input
            inputId="ProfileNotificationEmail"
            type="email"
            formControlName="notificationEmail"
            autocomplete="email"
            data-testid="profile-account-notificationEmail"
          />
          <span class="block text-xs text-slate-500 mt-1">{{ t('notificationEmailHelp') }}</span>
        </af-form-field>

        <af-form-field [label]="t('phone')" for="ProfilePhone">
          <af-input
            inputId="ProfilePhone"
            formControlName="phoneNumber"
            autocomplete="tel"
            data-testid="profile-account-phone"
          />
        </af-form-field>

        <af-form-field [label]="t('language')" for="ProfileLanguage">
          <af-select
            inputId="ProfileLanguage"
            formControlName="languageId"
            [options]="languageOptions"
            data-testid="profile-account-language"
          />
        </af-form-field>

        @if (store.hasError()) {
          <div class="text-sm text-red-600" role="alert" data-testid="profile-account-error">
            {{ t('saveError') }}
          </div>
        } @else if (store.savedOnce() && !store.isSaving()) {
          <div class="text-sm text-slate-600" role="status" data-testid="profile-account-saved">
            {{ t('saved') }}
          </div>
        }

        <div class="flex justify-end mt-4 pt-4 border-t border-slate-200">
          <af-button
            type="primary"
            htmlType="submit"
            [disabled]="!store.canSave()"
            data-testid="profile-account-save"
          >
            {{ t('save') }}
          </af-button>
        </div>
      </form>
    </ng-container>
  `,
})
export class ProfileAccountTab {
  protected readonly store = inject(AccountStore);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly languageOptions: readonly AfSelectOption<string>[] = LANGUAGE_OPTIONS.map(
    (o) => ({ value: o.id, label: o.label }),
  );

  protected readonly form: AccountForm = this.fb.group({
    friendlyName: this.fb.control('', {
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    notificationEmail: this.fb.control('', {
      validators: [Validators.required, Validators.email, Validators.maxLength(256)],
    }),
    phoneNumber: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    languageId: this.fb.control(''),
  });

  constructor() {
    // Hydrate the form whenever the store's view lands (initial load + after a
    // save reflects the persisted projection).
    effect(() => {
      const view = this.store.view();
      if (view !== null) {
        this.form.patchValue(
          {
            friendlyName: view.friendlyName,
            notificationEmail: view.notificationEmail,
            phoneNumber: view.phoneNumber,
            languageId: view.languageId,
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
    const req: MeProfileUpdateRequest = {
      friendlyName: v.friendlyName.trim(),
      notificationEmail: v.notificationEmail.trim(),
      languageId: v.languageId,
    };
    const phone = v.phoneNumber.trim();
    if (phone.length > 0) {
      req.phoneNumber = phone;
    }
    this.store.save(req);
  }
}
