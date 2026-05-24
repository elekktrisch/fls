import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
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
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import type {
  PersonCreateRequest,
  PersonResponse,
  PersonUpdateRequest,
} from '@api/generated/model';

import { SessionStore } from '../../../core/session/session.store';
import { MemberStatesStore } from '../member-states.store';
import { PersonsStore } from '../persons.store';

type PersonForm = FormGroup<{
  firstname: FormControl<string>;
  lastname: FormControl<string>;
  email: FormControl<string>;
  mobilePhone: FormControl<string>;
  city: FormControl<string>;
  memberNumber: FormControl<string>;
  memberStateId: FormControl<string>;
  isMotorPilot: FormControl<boolean>;
  isGliderPilot: FormControl<boolean>;
  isTowPilot: FormControl<boolean>;
}>;

@Component({
  selector: 'af-persons-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfButtonComponent,
    AfFormFieldComponent,
    AfInputComponent,
    AfSelectComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
  ],
  template: `
    <af-page>
      <af-page-header [title]="isCreate() ? 'New person' : 'Edit person'" />

      <af-page-error
        [message]="store.saveError()"
        (retry)="store.clearSaveError()"
        data-testid="person-form-error"
      />

      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        data-testid="person-form"
        class="max-w-2xl flex flex-col gap-3"
      >
        <af-form-field label="First name" for="Firstname">
          <af-input
            inputId="Firstname"
            formControlName="firstname"
            data-testid="firstname-input"
            autocomplete="given-name"
          />
        </af-form-field>

        <af-form-field label="Last name" for="Lastname">
          <af-input
            inputId="Lastname"
            formControlName="lastname"
            data-testid="lastname-input"
            autocomplete="family-name"
          />
        </af-form-field>

        <af-form-field label="Email" for="Email">
          <af-input
            inputId="Email"
            type="email"
            formControlName="email"
            data-testid="email-input"
            autocomplete="email"
          />
        </af-form-field>

        <af-form-field label="Mobile phone" for="MobilePhone">
          <af-input
            inputId="MobilePhone"
            formControlName="mobilePhone"
            data-testid="mobile-input"
            autocomplete="tel"
          />
        </af-form-field>

        <af-form-field label="City" for="City">
          <af-input
            inputId="City"
            formControlName="city"
            data-testid="city-input"
            autocomplete="address-level2"
          />
        </af-form-field>

        <af-form-field label="Member number" for="MemberNumber">
          <af-input
            inputId="MemberNumber"
            formControlName="memberNumber"
            data-testid="member-number-input"
          />
        </af-form-field>

        <af-form-field label="Member state" for="MemberStateId">
          <af-select
            inputId="MemberStateId"
            [value]="form.controls.memberStateId.value || null"
            (valueChange)="form.controls.memberStateId.setValue($event ?? '')"
            [options]="memberStateOptions()"
            data-testid="member-state-select"
          />
        </af-form-field>

        <fieldset class="border border-slate-200 p-3 flex flex-col gap-2">
          <legend class="text-sm text-slate-600 px-1">Roles in this club</legend>
          <label class="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              formControlName="isGliderPilot"
              data-testid="role-glider-pilot"
            />
            Glider pilot
          </label>
          <label class="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              formControlName="isMotorPilot"
              data-testid="role-motor-pilot"
            />
            Motor pilot
          </label>
          <label class="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              formControlName="isTowPilot"
              data-testid="role-tow-pilot"
            />
            Tow pilot
          </label>
        </fieldset>

        <div class="flex gap-2 mt-3">
          <af-button
            type="primary"
            htmlType="submit"
            [disabled]="form.invalid || saving()"
            data-testid="person-save-button"
          >
            {{ isCreate() ? 'Create' : 'Save' }}
          </af-button>
          <af-button
            type="default"
            htmlType="button"
            (clicked)="router.navigateByUrl('/persons')"
            data-testid="person-cancel-button"
          >
            Cancel
          </af-button>
        </div>
      </form>
    </af-page>
  `,
})
export class PersonsEditPage {
  protected readonly store = inject(PersonsStore);
  protected readonly memberStatesStore = inject(MemberStatesStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder).nonNullable;

  private readonly routeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  protected readonly isCreate = computed(() => this.routeId() === null);
  protected readonly saving = signal(false);

  protected readonly memberStateOptions = computed<AfSelectOption<string>[]>(() => [
    { value: '', label: '—' },
    ...this.memberStatesStore.entities().map((m) => ({ value: m.id, label: m.name })),
  ]);

  protected readonly form: PersonForm = this.fb.group({
    firstname: this.fb.control('', { validators: [Validators.required, Validators.maxLength(100)] }),
    lastname: this.fb.control('', { validators: [Validators.required, Validators.maxLength(100)] }),
    email: this.fb.control('', { validators: [Validators.email, Validators.maxLength(256)] }),
    mobilePhone: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    city: this.fb.control('', { validators: [Validators.maxLength(100)] }),
    memberNumber: this.fb.control('', { validators: [Validators.maxLength(20)] }),
    memberStateId: this.fb.control(''),
    isMotorPilot: this.fb.control(false),
    isGliderPilot: this.fb.control(false),
    isTowPilot: this.fb.control(false),
  });

  constructor() {
    // Hydrate the form when an existing detail is loaded.
    effect(() => {
      const detail = this.store.selectedPerson();
      if (detail && !this.isCreate()) {
        this.hydrate(detail);
      }
    });

    // Trigger detail load when route param has an id.
    effect(
      () => {
        const id = this.routeId();
        if (id !== null) {
          this.store.loadOne(id);
        }
      },
      { allowSignalWrites: true } as { allowSignalWrites?: boolean },
    );

    // Reset saving spinner when a save error surfaces.
    effect(() => {
      if (this.store.saveError() !== null) {
        this.saving.set(false);
      }
    });
  }

  protected onSubmit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    this.saving.set(true);
    if (this.isCreate()) {
      const req: PersonCreateRequest = {
        firstname: v.firstname.trim(),
        lastname: v.lastname.trim(),
        emailPrivate: v.email.trim() || undefined,
        mobilePhone: v.mobilePhone.trim() || undefined,
        city: v.city.trim() || undefined,
        preferMailToBusinessMail: false,
        receiveOwnedAircraftStatisticReports: false,
        enableAddress: false,
        initialClubMembership: {
          memberNumber: v.memberNumber.trim() || undefined,
          memberStateId: v.memberStateId || undefined,
          isMotorPilot: v.isMotorPilot,
          isTowPilot: v.isTowPilot,
          isGliderInstructor: false,
          isGliderPilot: v.isGliderPilot,
          isGliderTrainee: false,
          isPassenger: false,
          isWinchOperator: false,
          isMotorInstructor: false,
          receiveFlightReports: false,
          receiveAircraftReservationNotifications: false,
          receivePlanningDayRoleReminder: false,
          isActive: true,
        },
      };
      this.store.create(req);
      // The store's bus event + loadAll() refresh fires when the response
      // lands; navigate back optimistically.
      this.router.navigateByUrl('/persons');
    } else {
      const id = this.routeId();
      if (id === null) return;
      const req: PersonUpdateRequest = {
        firstname: v.firstname.trim(),
        lastname: v.lastname.trim(),
        emailPrivate: v.email.trim() || undefined,
        mobilePhone: v.mobilePhone.trim() || undefined,
        city: v.city.trim() || undefined,
        preferMailToBusinessMail: false,
        receiveOwnedAircraftStatisticReports: false,
        enableAddress: false,
      };
      this.store.update({ id, req });
      this.router.navigateByUrl('/persons');
    }
  }

  private hydrate(detail: PersonResponse): void {
    const pc = detail.memberships?.[0];
    this.form.patchValue({
      firstname: detail.firstname,
      lastname: detail.lastname,
      email: detail.emailPrivate ?? '',
      mobilePhone: detail.mobilePhone ?? '',
      city: detail.city ?? '',
      memberNumber: pc?.memberNumber ?? '',
      memberStateId: pc?.memberStateId ?? '',
      isMotorPilot: pc?.isMotorPilot ?? false,
      isGliderPilot: pc?.isGliderPilot ?? false,
      isTowPilot: pc?.isTowPilot ?? false,
    });
  }
}
