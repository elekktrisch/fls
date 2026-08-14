import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { debounceTime, merge } from 'rxjs';
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDialogComponent } from '@ui/organisms/af-dialog';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import type {
  PersonLookupMatch,
  UserInviteRequest,
  UserResponse,
  UserUpdateRequest,
  UserUpdateRequestRolesItem,
} from '@api/generated/model';
import { LANGUAGE_BY_LOCALE, LANGUAGE_OPTIONS, LocaleService } from '@shared/ui/locale';
import { liveFieldErrors, withOptionals } from '@shared/util/form';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import {
  CLUB_ADMIN_GRANTABLE_ROLES,
  MANAGED_ROLE_NAMES,
  mergeManagedRoles,
  roleLabel,
} from '../role-catalog';
import { UsersStore } from '../users.store';
import { UserPersonPickerComponent } from './person-picker.component';

type UserForm = FormGroup<{
  username: FormControl<string>;
  friendlyName: FormControl<string>;
  notificationEmail: FormControl<string>;
  phoneNumber: FormControl<string>;
  remarks: FormControl<string>;
  languageId: FormControl<string>;
  CLUB_ADMINISTRATOR: FormControl<boolean>;
  FLIGHT_OPERATOR: FormControl<boolean>;
  PILOT: FormControl<boolean>;
  OFFICE_USER: FormControl<boolean>;
  GUEST: FormControl<boolean>;
}>;

type RoleKey = 'CLUB_ADMINISTRATOR' | 'FLIGHT_OPERATOR' | 'PILOT' | 'OFFICE_USER' | 'GUEST';
const ROLE_KEYS: readonly RoleKey[] = [
  'CLUB_ADMINISTRATOR',
  'FLIGHT_OPERATOR',
  'PILOT',
  'OFFICE_USER',
  'GUEST',
];

const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,256}$/;
const USERNAME_HELP = 'Letters, digits, dot, underscore, dash; 3-256 chars.';

@Component({
  selector: 'af-users-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfButtonComponent,
    AfDialogComponent,
    AfFormFieldComponent,
    AfInputComponent,
    AfSelectComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
    UserPersonPickerComponent,
  ],
  template: `
    <af-page>
      <af-page-header [title]="isCreate() ? 'New user' : 'Edit user'" />

      @if (!isCreate() && store.isLoadingDetail()) {
        <div class="text-sm text-slate-500 my-4" data-testid="user-form-loading">Loading user…</div>
      } @else {
        @if (selectedUser()?.invitePending) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50 flex items-center justify-between"
            data-testid="user-invite-pending-banner"
          >
            <span>
              Invitation not yet accepted — sent to {{ selectedUser()?.notificationEmail }}
            </span>
            <af-button
              type="default"
              htmlType="button"
              [disabled]="store.isResendingInvite()"
              (clicked)="onResend()"
              data-testid="user-resend-invite-banner-button"
            >
              Resend invite
            </af-button>
          </div>
        }

        @if (outOfBandRoles().length > 0) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-slate-400 bg-slate-50"
            data-testid="user-out-of-band-roles-banner"
          >
            Additional roles managed outside this screen:
            <span class="tabular">{{ outOfBandRoleLabels() }}</span>
          </div>
        }

        <af-page-error
          [message]="formError()"
          retryLabel="Dismiss"
          (retry)="clearError()"
          data-testid="user-form-error"
        />

        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          novalidate
          data-testid="user-form"
          class="max-w-2xl flex flex-col gap-3"
        >
          @if (isCreate()) {
            <af-form-field
              label="Username"
              for="Username"
              [required]="true"
              [errors]="usernameErrors()"
            >
              <af-input
                inputId="Username"
                formControlName="username"
                data-testid="username-input"
                autocomplete="username"
              />
              <span class="block text-xs text-slate-500 mt-1">{{ usernameHelp }}</span>
            </af-form-field>
          }

          <af-form-field
            label="Friendly name"
            for="FriendlyName"
            [required]="true"
            [errors]="friendlyNameErrors()"
          >
            <af-input
              inputId="FriendlyName"
              formControlName="friendlyName"
              data-testid="friendlyName-input"
              autocomplete="name"
            />
          </af-form-field>

          <af-form-field
            label="Notification email"
            for="NotificationEmail"
            [required]="true"
            [errors]="notificationEmailErrors()"
          >
            <af-input
              inputId="NotificationEmail"
              type="email"
              formControlName="notificationEmail"
              data-testid="notificationEmail-input"
              autocomplete="email"
            />
            <span class="block text-xs text-slate-500 mt-1">
              Used for in-app notifications. The login email is managed in Keycloak.
            </span>
          </af-form-field>

          <af-form-field label="Phone" for="PhoneNumber" [errors]="phoneNumberErrors()">
            <af-input
              inputId="PhoneNumber"
              formControlName="phoneNumber"
              data-testid="phone-input"
              autocomplete="tel"
            />
          </af-form-field>

          <af-form-field label="Remarks" for="Remarks" [errors]="remarksErrors()">
            <af-input inputId="Remarks" formControlName="remarks" data-testid="remarks-input" />
          </af-form-field>

          <af-form-field label="Language" for="LanguageId">
            <af-select
              inputId="LanguageId"
              formControlName="languageId"
              [options]="languageOptions"
              data-testid="language-select"
            />
          </af-form-field>

          @if (isCreate()) {
            <af-form-field label="Linked person (optional)" for="PersonPicker">
              <af-user-person-picker [(pinned)]="pinnedPerson" data-testid="user-person-picker" />
            </af-form-field>
          }

          <fieldset
            class="border border-slate-200 p-3 flex flex-col gap-2"
            [class.border-red-600]="rolesEmptyError()"
          >
            <legend class="text-sm text-slate-600 px-1">Roles</legend>
            @for (role of grantableRoles; track role) {
              <label class="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  [formControlName]="role"
                  [attr.data-testid]="'role-' + role"
                />
                {{ roleLabel(role) }}
              </label>
            }
            @if (rolesEmptyError()) {
              <span class="text-xs text-red-600" data-testid="roles-empty-error">
                Pick at least one role.
              </span>
            }
          </fieldset>

          <div class="flex gap-2 justify-end mt-4 pt-4 border-t border-slate-200">
            @if (!isCreate()) {
              <af-button
                type="default"
                htmlType="button"
                [disabled]="saving()"
                (clicked)="openDeactivate()"
                data-testid="user-deactivate-button"
              >
                Deactivate
              </af-button>
            }
            <af-button
              type="default"
              htmlType="button"
              (clicked)="router.navigateByUrl('/users')"
              data-testid="user-cancel-button"
            >
              Cancel
            </af-button>
            <af-button
              type="primary"
              htmlType="submit"
              [disabled]="saving()"
              data-testid="user-save-button"
            >
              Save
            </af-button>
          </div>
        </form>
      }

      <af-dialog
        [visible]="deactivateOpen()"
        title="Deactivate user"
        [message]="dialogMessage()"
        [confirmLabel]="deactivateError() ? 'Close' : 'Deactivate'"
        dismissLabel="Cancel"
        (confirm)="onConfirmDeactivate()"
        (dismiss)="onDismissDeactivate()"
      />
    </af-page>
  `,
})
export class UsersEditPage {
  protected readonly store = inject(UsersStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly bus = inject(MUTATION_BUS);
  private readonly destroyRef = inject(DestroyRef);
  private readonly locale = inject(LocaleService);

  protected readonly grantableRoles = CLUB_ADMIN_GRANTABLE_ROLES;
  protected readonly roleLabel = roleLabel;
  protected readonly usernameHelp = USERNAME_HELP;
  protected readonly languageOptions: readonly AfSelectOption<string>[] = LANGUAGE_OPTIONS.map(
    (o) => ({ value: o.id, label: o.label }),
  );

  private readonly routeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  protected readonly isCreate = computed(() => this.routeId() === null);
  protected readonly saving = signal(false);
  protected readonly deactivateOpen = signal(false);
  protected readonly pinnedPerson = signal<PersonLookupMatch | null>(null);

  private readonly deactivateErrorMessage = signal<string | null>(null);
  protected readonly deactivateError = computed(() => this.deactivateErrorMessage());

  protected readonly selectedUser = computed(() => this.store.selectedUser());

  protected readonly outOfBandRoles = computed(() => {
    const detail = this.store.selectedUser();
    if (!detail || this.isCreate()) return [];
    return detail.roles.filter((r) => !MANAGED_ROLE_NAMES.has(r));
  });

  protected readonly outOfBandRoleLabels = computed(() =>
    this.outOfBandRoles().map(roleLabel).join(', '),
  );

  protected readonly form: UserForm = this.fb.group({
    username: this.fb.control('', {
      validators: [
        Validators.required,
        Validators.minLength(3),
        Validators.maxLength(256),
        Validators.pattern(USERNAME_PATTERN),
      ],
    }),
    friendlyName: this.fb.control('', {
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    notificationEmail: this.fb.control('', {
      validators: [Validators.required, Validators.email, Validators.maxLength(256)],
    }),
    phoneNumber: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    remarks: this.fb.control('', { validators: [Validators.maxLength(250)] }),
    languageId: this.fb.control(LANGUAGE_BY_LOCALE[this.locale.current()] ?? LANGUAGE_BY_LOCALE.de),
    CLUB_ADMINISTRATOR: this.fb.control(false),
    FLIGHT_OPERATOR: this.fb.control(false),
    PILOT: this.fb.control(false),
    OFFICE_USER: this.fb.control(false),
    GUEST: this.fb.control(false),
  });

  protected readonly usernameErrors = liveFieldErrors(this.form.controls.username);
  protected readonly friendlyNameErrors = liveFieldErrors(this.form.controls.friendlyName);
  protected readonly notificationEmailErrors = liveFieldErrors(
    this.form.controls.notificationEmail,
  );
  protected readonly phoneNumberErrors = liveFieldErrors(this.form.controls.phoneNumber);
  protected readonly remarksErrors = liveFieldErrors(this.form.controls.remarks);

  private readonly rolesInteracted = toSignal(
    merge(...ROLE_KEYS.map((r) => this.form.controls[r].valueChanges)).pipe(debounceTime(200)),
    { initialValue: undefined },
  );
  protected readonly rolesEmptyError = computed(
    () =>
      (this.submissionAttempted() || this.rolesInteracted() !== undefined) &&
      this.checkedRoles().length === 0,
  );
  private readonly submissionAttempted = signal(false);

  protected readonly formError = computed(() => this.store.saveError());

  protected readonly dialogMessage = computed(() => {
    const err = this.deactivateErrorMessage();
    if (err !== null) {
      return err;
    }
    const detail = this.store.selectedUser();
    return `Deactivate user ${detail?.friendlyName ?? ''}? Login access will be revoked.`;
  });

  constructor() {
    effect(() => {
      const detail = this.store.selectedUser();
      if (detail && !this.isCreate()) {
        this.hydrate(detail);
      }
    });

    effect(() => {
      const id = this.routeId();
      if (id !== null) {
        this.store.select(id);
        this.store.loadOne(id);
      } else {
        this.store.select(null);
      }
    });

    effect(() => {
      const err = this.store.saveError();
      if (err !== null) {
        this.saving.set(false);
        if (this.deactivateOpen()) {
          this.deactivateErrorMessage.set(err);
        }
      }
    });

    this.bus.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((evt) => {
      if (
        this.saving() &&
        (evt.kind === 'user.created' || evt.kind === 'user.updated' || evt.kind === 'user.deleted')
      ) {
        this.saving.set(false);
        this.deactivateOpen.set(false);
        this.deactivateErrorMessage.set(null);
        this.router.navigateByUrl('/users');
      }
    });
  }

  protected clearError(): void {
    this.store.clearSaveError();
  }

  protected openDeactivate(): void {
    this.store.clearSaveError();
    this.deactivateErrorMessage.set(null);
    this.deactivateOpen.set(true);
  }

  protected onDismissDeactivate(): void {
    this.deactivateOpen.set(false);
    this.deactivateErrorMessage.set(null);
    this.store.clearSaveError();
  }

  protected onConfirmDeactivate(): void {
    if (this.deactivateErrorMessage() !== null) {
      this.onDismissDeactivate();
      return;
    }
    const id = this.routeId();
    if (id === null) return;
    this.saving.set(true);
    this.store.deactivate(id);
  }

  protected onResend(): void {
    const id = this.routeId();
    if (id === null) return;
    this.store.resendInvite(id);
  }

  protected onSubmit(): void {
    this.submissionAttempted.set(true);
    const checked = this.checkedRoles();
    if (this.form.invalid || checked.length === 0) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    if (this.isCreate()) {
      this.store.invite(this.buildInviteRequest(checked));
      return;
    }
    const id = this.routeId();
    if (id === null) {
      this.saving.set(false);
      return;
    }
    this.store.update({ id, req: this.buildUpdateRequest(checked) });
  }

  private optionalContactFields(): { phoneNumber: string; remarks: string } {
    const v = this.form.getRawValue();
    return { phoneNumber: v.phoneNumber.trim(), remarks: v.remarks.trim() };
  }

  private buildInviteRequest(roles: UserUpdateRequestRolesItem[]): UserInviteRequest {
    const v = this.form.getRawValue();
    const pinned = this.pinnedPerson();
    return withOptionals(
      {
        username: v.username.trim(),
        friendlyName: v.friendlyName.trim(),
        notificationEmail: v.notificationEmail.trim(),
        languageId: v.languageId,
        roles,
      },
      { ...this.optionalContactFields(), personId: pinned?.id ?? undefined },
    ) as UserInviteRequest;
  }

  private buildUpdateRequest(roles: UserUpdateRequestRolesItem[]): UserUpdateRequest {
    const v = this.form.getRawValue();
    const merged = mergeManagedRoles(this.store.selectedUser()?.roles ?? [], roles);
    return withOptionals(
      {
        friendlyName: v.friendlyName.trim(),
        notificationEmail: v.notificationEmail.trim(),
        languageId: v.languageId,
        roles: merged,
      },
      this.optionalContactFields(),
    ) as UserUpdateRequest;
  }

  private checkedRoles(): UserUpdateRequestRolesItem[] {
    const v = this.form.getRawValue();
    return ROLE_KEYS.filter((r) => v[r]);
  }

  private hydrate(detail: UserResponse): void {
    this.form.patchValue({
      username: detail.username,
      friendlyName: detail.friendlyName,
      notificationEmail: detail.notificationEmail,
      phoneNumber: detail.phoneNumber ?? '',
      remarks: detail.remarks ?? '',
      languageId: detail.languageId,
      CLUB_ADMINISTRATOR: detail.roles.includes('CLUB_ADMINISTRATOR'),
      FLIGHT_OPERATOR: detail.roles.includes('FLIGHT_OPERATOR'),
      PILOT: detail.roles.includes('PILOT'),
      OFFICE_USER: detail.roles.includes('OFFICE_USER'),
      GUEST: detail.roles.includes('GUEST'),
    });
    this.form.controls.username.disable({ emitEvent: false });
  }
}
