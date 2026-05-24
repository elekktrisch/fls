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
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import type {
  FlightTypeCreateRequest,
  FlightTypeDetail,
  FlightTypeUpdateRequest,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { SessionStore } from '../../../core/session/session.store';
import { FlightTypesStore } from '../flight-types.store';

type FlightTypeForm = FormGroup<{
  flightTypeName: FormControl<string>;
  flightCode: FormControl<string>;
  isInstructorRequired: FormControl<boolean>;
  isObserverPilotOrInstructorRequired: FormControl<boolean>;
  isCheckFlight: FormControl<boolean>;
  isPassengerFlight: FormControl<boolean>;
  isSoloFlight: FormControl<boolean>;
  isForGliderFlights: FormControl<boolean>;
  isForTowFlights: FormControl<boolean>;
  isForMotorFlights: FormControl<boolean>;
  isFlightCostBalanceSelectable: FormControl<boolean>;
  isCouponNumberRequired: FormControl<boolean>;
  isForAircraftReservationType: FormControl<boolean>;
  // String-typed because the form uses <af-input type="number"> which writes
  // empty string for "no value". `null` cannot survive a manual blank-out
  // through the typed FormControl without forcing nullable; the wire mapper
  // translates empty → undefined back to `null` semantics for the API.
  minNrOfAircraftSeatsRequired: FormControl<number | null>;
}>;

@Component({
  selector: 'af-flight-types-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfFormFieldComponent,
    AfInputComponent,
    AfButtonComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
  ],
  template: `
    <af-page>
      <af-page-header [title]="isCreate() ? 'New flight type' : 'Edit flight type'" />

      <af-page-error
        [message]="store.saveError()"
        [retryLabel]="null"
        data-testid="flight-types-save-error"
      />

      @if (store.isLoadingDetail() && !isCreate()) {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
          data-testid="flight-types-loading"
          role="status"
          aria-live="polite"
        >
          Loading flight type…
        </div>
      } @else {
        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          data-testid="flight-types-edit-form"
          class="flex flex-col gap-6"
          novalidate
        >
          <section class="flex flex-col gap-2" data-testid="flight-types-section-master">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              Master data
            </h2>
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
              <af-form-field
                label="Name"
                for="FlightTypeName"
                [required]="true"
                [errors]="
                  form.controls.flightTypeName.touched ? form.controls.flightTypeName.errors : null
                "
              >
                <af-input
                  inputId="FlightTypeName"
                  formControlName="flightTypeName"
                  autocomplete="off"
                  placeholder="Schulflug"
                />
              </af-form-field>
              <af-form-field label="Code" for="FlightCode">
                <af-input
                  inputId="FlightCode"
                  formControlName="flightCode"
                  autocomplete="off"
                  placeholder="S"
                />
              </af-form-field>
              <af-form-field
                label="Min. aircraft seats"
                for="MinSeats"
                [errors]="
                  form.controls.minNrOfAircraftSeatsRequired.touched
                    ? form.controls.minNrOfAircraftSeatsRequired.errors
                    : null
                "
              >
                <af-input
                  inputId="MinSeats"
                  type="number"
                  formControlName="minNrOfAircraftSeatsRequired"
                  autocomplete="off"
                  placeholder="(no constraint)"
                />
              </af-form-field>
            </div>
          </section>

          <section class="flex flex-col gap-2" data-testid="flight-types-section-applicability">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              Applicable to
            </h2>
            <div class="flex flex-col gap-1">
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isForGliderFlights"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                  data-testid="flight-types-flag-glider"
                />
                <span>Glider flights</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isForTowFlights"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                  data-testid="flight-types-flag-tow"
                />
                <span>Tow flights</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isForMotorFlights"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                  data-testid="flight-types-flag-motor"
                />
                <span>Motor flights</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isForAircraftReservationType"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Aircraft reservation type (S-068)</span>
              </label>
            </div>
          </section>

          <section class="flex flex-col gap-2" data-testid="flight-types-section-roles">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              Crew / mission flags
            </h2>
            <div class="flex flex-col gap-1">
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isInstructorRequired"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Instructor required</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isObserverPilotOrInstructorRequired"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Observer pilot or instructor required</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isCheckFlight"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Check flight</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isPassengerFlight"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Passenger flight</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isSoloFlight"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Solo flight</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isFlightCostBalanceSelectable"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Flight-cost-balance selectable</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isCouponNumberRequired"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Coupon number required</span>
              </label>
            </div>
          </section>

          <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
            <af-button htmlType="button" (clicked)="router.navigateByUrl('/flight-types')">
              Cancel
            </af-button>
            @if (canMutate()) {
              <af-button
                type="primary"
                htmlType="submit"
                [disabled]="form.invalid || saveSubmitted()"
                data-testid="flight-types-save-button"
              >
                Save
              </af-button>
            }
          </div>
        </form>
      }
    </af-page>
  `,
})
export class FlightTypesEditPage {
  protected readonly store = inject(FlightTypesStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly flightTypeId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.flightTypeId() === null);
  protected readonly canMutate = this.session.isClubAdmin;

  protected readonly form: FlightTypeForm = this.fb.group({
    flightTypeName: this.fb.nonNullable.control('', [
      Validators.required,
      Validators.maxLength(100),
    ]),
    flightCode: this.fb.nonNullable.control('', [Validators.maxLength(30)]),
    isInstructorRequired: this.fb.nonNullable.control(false),
    isObserverPilotOrInstructorRequired: this.fb.nonNullable.control(false),
    isCheckFlight: this.fb.nonNullable.control(false),
    isPassengerFlight: this.fb.nonNullable.control(false),
    isSoloFlight: this.fb.nonNullable.control(false),
    isForGliderFlights: this.fb.nonNullable.control(false),
    isForTowFlights: this.fb.nonNullable.control(false),
    isForMotorFlights: this.fb.nonNullable.control(false),
    isFlightCostBalanceSelectable: this.fb.nonNullable.control(false),
    isCouponNumberRequired: this.fb.nonNullable.control(false),
    isForAircraftReservationType: this.fb.nonNullable.control(false),
    minNrOfAircraftSeatsRequired: this.fb.control<number | null>(null, [Validators.min(1)]),
  });

  protected readonly saveSubmitted = signal(false);

  constructor() {
    effect(() => {
      const id = this.flightTypeId();
      if (!id) {
        this.store.select(null);
        return;
      }
      this.store.select(id);
      this.store.loadOne(id);
    });

    effect(() => {
      const detail = this.store.selectedFlightType();
      if (!detail) return;
      this.form.patchValue(detailToFormValue(detail));
    });

    effect(() => {
      const err = this.store.saveError();
      if (!err) return;
      this.saveSubmitted.set(false);
      if (this.store.saveErrorKind() === 'name-duplicate') {
        this.form.controls.flightTypeName.setErrors({
          ...(this.form.controls.flightTypeName.errors ?? {}),
          duplicate: true,
        });
        this.form.controls.flightTypeName.markAsTouched();
      }
    });

    const destroyRef = inject(DestroyRef);
    this.form.controls.flightTypeName.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe(() => {
        if (this.form.controls.flightTypeName.hasError('duplicate')) {
          const errs = { ...this.form.controls.flightTypeName.errors };
          delete errs['duplicate'];
          this.form.controls.flightTypeName.setErrors(Object.keys(errs).length ? errs : null);
        }
        if (this.store.saveErrorKind() === 'name-duplicate') {
          this.store.clearSaveError();
        }
      });

    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (evt.kind === 'flightType.created' || evt.kind === 'flightType.updated') {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/flight-types');
      }
    });
  }

  protected onSubmit(): void {
    if (!this.canMutate()) return;
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saveSubmitted.set(true);
    const id = this.flightTypeId();
    if (id === null) {
      this.store.create(formToCreateRequest(this.form));
    } else {
      this.store.update({ id, req: formToUpdateRequest(this.form) });
    }
  }
}

function detailToFormValue(d: FlightTypeDetail): Partial<{
  flightTypeName: string;
  flightCode: string;
  isInstructorRequired: boolean;
  isObserverPilotOrInstructorRequired: boolean;
  isCheckFlight: boolean;
  isPassengerFlight: boolean;
  isSoloFlight: boolean;
  isForGliderFlights: boolean;
  isForTowFlights: boolean;
  isForMotorFlights: boolean;
  isFlightCostBalanceSelectable: boolean;
  isCouponNumberRequired: boolean;
  isForAircraftReservationType: boolean;
  minNrOfAircraftSeatsRequired: number | null;
}> {
  return {
    flightTypeName: d.flightTypeName,
    flightCode: d.flightCode ?? '',
    isInstructorRequired: d.isInstructorRequired,
    isObserverPilotOrInstructorRequired: d.isObserverPilotOrInstructorRequired,
    isCheckFlight: d.isCheckFlight,
    isPassengerFlight: d.isPassengerFlight,
    isSoloFlight: d.isSoloFlight,
    isForGliderFlights: d.isForGliderFlights,
    isForTowFlights: d.isForTowFlights,
    isForMotorFlights: d.isForMotorFlights,
    isFlightCostBalanceSelectable: d.isFlightCostBalanceSelectable,
    isCouponNumberRequired: d.isCouponNumberRequired,
    isForAircraftReservationType: d.isForAircraftReservationType,
    minNrOfAircraftSeatsRequired: d.minNrOfAircraftSeatsRequired ?? null,
  };
}

function formToCreateRequest(form: FlightTypeForm): FlightTypeCreateRequest {
  const v = form.getRawValue();
  const req: FlightTypeCreateRequest = {
    flightTypeName: v.flightTypeName.trim(),
    isInstructorRequired: v.isInstructorRequired,
    isObserverPilotOrInstructorRequired: v.isObserverPilotOrInstructorRequired,
    isCheckFlight: v.isCheckFlight,
    isPassengerFlight: v.isPassengerFlight,
    isSoloFlight: v.isSoloFlight,
    isForGliderFlights: v.isForGliderFlights,
    isForTowFlights: v.isForTowFlights,
    isForMotorFlights: v.isForMotorFlights,
    isFlightCostBalanceSelectable: v.isFlightCostBalanceSelectable,
    isCouponNumberRequired: v.isCouponNumberRequired,
    isForAircraftReservationType: v.isForAircraftReservationType,
  };
  if (v.flightCode.trim() !== '') req.flightCode = v.flightCode.trim();
  if (v.minNrOfAircraftSeatsRequired !== null) {
    req.minNrOfAircraftSeatsRequired = v.minNrOfAircraftSeatsRequired;
  }
  return req;
}

function formToUpdateRequest(form: FlightTypeForm): FlightTypeUpdateRequest {
  // FlightTypeUpdateRequest has the same shape as Create (no operating_club_id);
  // share the body builder.
  return formToCreateRequest(form) as FlightTypeUpdateRequest;
}
