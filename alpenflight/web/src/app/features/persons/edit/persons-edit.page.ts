import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
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
  memberStateId: FormControl<string | null>;
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

      @if (!isCreate() && store.isLoadingDetail()) {
        <div class="text-sm text-slate-500 my-4" data-testid="person-form-loading">
          Loading person…
        </div>
      } @else {
        @if (inOtherClubsCount() > 0) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50"
            data-testid="person-shared-banner"
          >
            This person is also a member of {{ inOtherClubsCount() }} other
            {{ inOtherClubsCount() === 1 ? 'club' : 'clubs' }}. Name and contact edits are visible
            there too.
          </div>
        }

        <af-page-error
          [message]="store.saveError()"
          (retry)="store.clearSaveError()"
          data-testid="person-form-error"
        />

        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          novalidate
          data-testid="person-form"
          class="max-w-2xl flex flex-col gap-3"
        >
          <af-form-field
            label="First name"
            for="Firstname"
            [required]="true"
            [errors]="form.controls.firstname.touched ? form.controls.firstname.errors : null"
          >
            <af-input
              inputId="Firstname"
              formControlName="firstname"
              data-testid="firstname-input"
              autocomplete="given-name"
            />
          </af-form-field>

          <af-form-field
            label="Last name"
            for="Lastname"
            [required]="true"
            [errors]="form.controls.lastname.touched ? form.controls.lastname.errors : null"
          >
            <af-input
              inputId="Lastname"
              formControlName="lastname"
              data-testid="lastname-input"
              autocomplete="family-name"
            />
          </af-form-field>

          <af-form-field
            label="Email"
            for="Email"
            [errors]="form.controls.email.touched ? form.controls.email.errors : null"
          >
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
              formControlName="memberStateId"
              [options]="memberStateOptions()"
              [placeholder]="memberStatePlaceholder()"
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
              <input type="checkbox" formControlName="isTowPilot" data-testid="role-tow-pilot" />
              Tow pilot
            </label>
          </fieldset>

          <div class="flex gap-2 justify-end mt-4 pt-4 border-t border-slate-200">
            <af-button
              type="default"
              htmlType="button"
              (clicked)="router.navigateByUrl('/persons')"
              data-testid="person-cancel-button"
            >
              Cancel
            </af-button>
            <af-button
              type="primary"
              htmlType="submit"
              [disabled]="saving()"
              data-testid="person-save-button"
            >
              Save
            </af-button>
          </div>
        </form>
      }
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
  private readonly bus = inject(MUTATION_BUS);
  private readonly destroyRef = inject(DestroyRef);

  private readonly routeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  protected readonly isCreate = computed(() => this.routeId() === null);
  protected readonly saving = signal(false);

  protected readonly memberStateOptions = computed<AfSelectOption<string>[]>(() =>
    this.memberStatesStore.entities().map((m) => ({ value: m.id, label: m.name })),
  );

  protected readonly memberStatePlaceholder = computed(() =>
    this.memberStatesStore.entities().length === 0
      ? 'No member states configured'
      : 'Select a member state',
  );

  protected readonly inOtherClubsCount = computed(
    () => this.store.selectedPerson()?.inOtherClubsCount ?? 0,
  );

  protected readonly form: PersonForm = this.fb.group({
    firstname: this.fb.control('', {
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    lastname: this.fb.control('', {
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    email: this.fb.control('', { validators: [Validators.email, Validators.maxLength(256)] }),
    mobilePhone: this.fb.control('', { validators: [Validators.maxLength(30)] }),
    city: this.fb.control('', { validators: [Validators.maxLength(100)] }),
    memberNumber: this.fb.control('', { validators: [Validators.maxLength(20)] }),
    memberStateId: this.fb.control<string | null>(null),
    isMotorPilot: this.fb.control(false),
    isGliderPilot: this.fb.control(false),
    isTowPilot: this.fb.control(false),
  });

  // Per-field error access is inlined in the template
  // (`[errors]="ctl.touched ? ctl.errors : null"`) mirroring the
  // locations / aircraft sibling pattern — ReactiveForms event listeners
  // drive view updates; no signal bridge needed.

  constructor() {
    // Hydrate the form when an existing detail is loaded.
    effect(() => {
      const detail = this.store.selectedPerson();
      if (detail && !this.isCreate()) {
        this.hydrate(detail);
      }
    });

    // Trigger detail load when route param has an id. `select(id)` clears
    // any stale detail from the previous Person before the GET response
    // lands — keeps the form blank during loading rather than showing the
    // previous Person's fields.
    effect(() => {
      const id = this.routeId();
      if (id !== null) {
        this.store.select(id);
        this.store.loadOne(id);
      } else {
        this.store.select(null);
      }
    });

    // Reset saving spinner when a save error surfaces.
    effect(() => {
      if (this.store.saveError() !== null) {
        this.saving.set(false);
      }
    });

    // Navigate back to the list ONLY when the save succeeds, signalled via
    // MUTATION_BUS. Optimistic navigation on submit would hide save errors
    // on an unmounted page.
    this.bus.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((evt) => {
      if (this.saving() && (evt.kind === 'person.created' || evt.kind === 'person.updated')) {
        this.saving.set(false);
        this.router.navigateByUrl('/persons');
      }
    });
  }

  protected onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    // exactOptionalPropertyTypes: true forbids `email?: string` being
    // assigned `string | undefined`; spread the optional fields only when
    // they have a non-empty value.
    const optionalContact: {
      emailPrivate?: string;
      mobilePhone?: string;
      city?: string;
    } = {};
    const trimmedEmail = v.email.trim();
    if (trimmedEmail.length > 0) optionalContact.emailPrivate = trimmedEmail;
    const trimmedMobile = v.mobilePhone.trim();
    if (trimmedMobile.length > 0) optionalContact.mobilePhone = trimmedMobile;
    const trimmedCity = v.city.trim();
    if (trimmedCity.length > 0) optionalContact.city = trimmedCity;

    if (this.isCreate()) {
      const optionalMembership: { memberNumber?: string; memberStateId?: string } = {};
      const trimmedMember = v.memberNumber.trim();
      if (trimmedMember.length > 0) optionalMembership.memberNumber = trimmedMember;
      if (v.memberStateId !== null && v.memberStateId.length > 0) {
        optionalMembership.memberStateId = v.memberStateId;
      }
      const req: PersonCreateRequest = {
        firstname: v.firstname.trim(),
        lastname: v.lastname.trim(),
        ...optionalContact,
        preferMailToBusinessMail: false,
        receiveOwnedAircraftStatisticReports: false,
        enableAddress: false,
        initialClubMembership: {
          ...optionalMembership,
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
    } else {
      const id = this.routeId();
      if (id === null) {
        this.saving.set(false);
        return;
      }
      const req: PersonUpdateRequest = {
        firstname: v.firstname.trim(),
        lastname: v.lastname.trim(),
        ...optionalContact,
        preferMailToBusinessMail: false,
        receiveOwnedAircraftStatisticReports: false,
        enableAddress: false,
      };
      this.store.update({ id, req });
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
      memberStateId: pc?.memberStateId ?? null,
      isMotorPilot: pc?.isMotorPilot ?? false,
      isGliderPilot: pc?.isGliderPilot ?? false,
      isTowPilot: pc?.isTowPilot ?? false,
    });
  }
}
