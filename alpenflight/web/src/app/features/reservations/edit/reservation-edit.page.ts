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
import { TranslocoDirective } from '@jsverse/transloco';

import type {
  AircraftReservationCreateRequest,
  AircraftReservationDetail,
  AircraftReservationUpdateRequest,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';
import { withOptionals } from '@shared/util/form';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { SessionStore } from '../../../core/session/session.store';
import { ReservationsStore } from '../reservations.store';

type ReservationForm = FormGroup<{
  aircraftId: FormControl<string>;
  pilotPersonId: FormControl<string>;
  secondCrewPersonId: FormControl<string>;
  locationId: FormControl<string>;
  reservationTypeId: FormControl<string>;
  date: FormControl<string>;
  startTime: FormControl<string>;
  endTime: FormControl<string>;
  isAllDay: FormControl<boolean>;
  remarks: FormControl<string>;
}>;

/**
 * Reservation create / edit form (J-5 T-09).
 *
 * Mirrors the J-1 `aircraft-edit.page.ts` STRUCTURE — typed reactive
 * `FormGroup`, load-on-`:id/edit`, submit→store, inline save-error — but built
 * LOW-CRAP via the extracted `@shared/util/form` helpers (`withOptionals` for
 * the request body, `mapApiSaveError` lives in the store): no per-field
 * `if (v.x !== '') req.x = …` cascade, no `errorPatch` status switch
 * (the flagged `formToUpdateRequest` / `finalSubmit` / `errorPatch` hotspot we
 * deliberately do NOT replicate — see `_BOYSCOUT.md`).
 *
 * All-day: the time fields hide + disable; we submit `start = end = <date>`
 * (the aggregate normalizes the full-day span — journey assumption #2). Timed:
 * explicit `<date>T<time>` instants. Conflict 409 / duration 422 surface inline
 * via `store.saveError()`; the route does NOT navigate on error (the
 * SPA-nav-evicts-response-body trap — show the error, navigate only on the
 * mutation-bus success event).
 */
@Component({
  selector: 'af-reservation-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfFormFieldComponent,
    AfInputComponent,
    AfSelectComponent,
    AfButtonComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'reservations.form'">
        <af-page-header [title]="isCreate() ? t('titleNew') : t('title')" />

        <af-page-error
          [message]="store.saveError()"
          [retryLabel]="null"
          data-testid="reservation-save-error"
        />

        @if (store.isLoadingDetail() && !isCreate()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
            data-testid="reservation-loading"
            role="status"
            aria-live="polite"
          >
            {{ t('loading') }}
          </div>
        } @else {
          <form
            [formGroup]="form"
            (ngSubmit)="onSubmit()"
            data-testid="reservation-edit-form"
            class="flex flex-col gap-6"
            novalidate
          >
            <!-- Section 1: Reservation details -->
            <section class="flex flex-col gap-2" data-testid="reservation-section-details">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('sections.details') }}
              </h2>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
                <af-form-field
                  [label]="t('aircraft')"
                  for="ReservationAircraft"
                  [required]="true"
                  [errors]="
                    form.controls.aircraftId.touched ? form.controls.aircraftId.errors : null
                  "
                >
                  <af-select
                    inputId="ReservationAircraft"
                    formControlName="aircraftId"
                    [placeholder]="t('selectAircraft')"
                    [options]="aircraftOptions()"
                    data-testid="reservation-aircraft-select"
                  />
                </af-form-field>
                <af-form-field
                  [label]="t('type')"
                  for="ReservationType"
                  [required]="true"
                  [errors]="
                    form.controls.reservationTypeId.touched
                      ? form.controls.reservationTypeId.errors
                      : null
                  "
                >
                  <af-select
                    inputId="ReservationType"
                    formControlName="reservationTypeId"
                    [placeholder]="t('selectType')"
                    [options]="typeOptions()"
                    data-testid="reservation-type-select"
                  />
                </af-form-field>
              </div>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
                <af-form-field
                  [label]="t('pilot')"
                  for="ReservationPilot"
                  [required]="true"
                  [errors]="
                    form.controls.pilotPersonId.touched ? form.controls.pilotPersonId.errors : null
                  "
                >
                  <af-select
                    inputId="ReservationPilot"
                    formControlName="pilotPersonId"
                    [placeholder]="t('selectPilot')"
                    [options]="personOptions()"
                    data-testid="reservation-pilot-select"
                  />
                </af-form-field>
                <af-form-field [label]="t('secondCrew')" for="ReservationSecondCrew">
                  <af-select
                    inputId="ReservationSecondCrew"
                    formControlName="secondCrewPersonId"
                    [placeholder]="t('selectSecondCrew')"
                    [options]="secondCrewOptions()"
                    data-testid="reservation-second-crew-select"
                  />
                </af-form-field>
              </div>
              <af-form-field
                [label]="t('location')"
                for="ReservationLocation"
                [required]="true"
                [errors]="form.controls.locationId.touched ? form.controls.locationId.errors : null"
              >
                <af-select
                  inputId="ReservationLocation"
                  formControlName="locationId"
                  [placeholder]="t('selectLocation')"
                  [options]="locationOptions()"
                  data-testid="reservation-location-select"
                />
              </af-form-field>
            </section>

            <!-- Section 2: Timeframe -->
            <section class="flex flex-col gap-2" data-testid="reservation-section-timeframe">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('sections.timeframe') }}
              </h2>
              <div class="grid grid-cols-1 sm:grid-cols-3 gap-2 items-end">
                <af-form-field
                  [label]="t('date')"
                  for="ReservationDate"
                  [required]="true"
                  [errors]="form.controls.date.touched ? form.controls.date.errors : null"
                >
                  <af-input
                    inputId="ReservationDate"
                    type="date"
                    formControlName="date"
                    data-testid="reservation-date"
                  />
                </af-form-field>
                @if (!isAllDay()) {
                  <af-form-field
                    [label]="t('startTime')"
                    for="ReservationStartTime"
                    [required]="true"
                    [errors]="
                      form.controls.startTime.touched ? form.controls.startTime.errors : null
                    "
                  >
                    <af-input
                      inputId="ReservationStartTime"
                      type="time"
                      formControlName="startTime"
                      data-testid="reservation-start-time"
                    />
                  </af-form-field>
                  <af-form-field
                    [label]="t('endTime')"
                    for="ReservationEndTime"
                    [required]="true"
                    [errors]="form.controls.endTime.touched ? form.controls.endTime.errors : null"
                  >
                    <af-input
                      inputId="ReservationEndTime"
                      type="time"
                      formControlName="endTime"
                      data-testid="reservation-end-time"
                    />
                  </af-form-field>
                }
              </div>
              <label class="flex items-center gap-2 cursor-pointer select-none mt-1">
                <input
                  type="checkbox"
                  formControlName="isAllDay"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                  data-testid="reservation-allday-toggle"
                />
                <span>{{ t('allDay') }}</span>
              </label>
            </section>

            <!-- Section 3: Notes -->
            <section class="flex flex-col gap-2" data-testid="reservation-section-notes">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('sections.notes') }}
              </h2>
              <af-form-field [label]="t('remarks')" for="ReservationRemarks">
                <af-input
                  inputId="ReservationRemarks"
                  formControlName="remarks"
                  autocomplete="off"
                />
              </af-form-field>
            </section>

            <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
              <af-button htmlType="button" (clicked)="router.navigateByUrl('/reservations')">
                {{ t('cancel') }}
              </af-button>
              @if (canMutate()) {
                <af-button
                  type="primary"
                  htmlType="submit"
                  [disabled]="form.invalid || saveSubmitted()"
                  data-testid="reservation-save-button"
                >
                  {{ t('save') }}
                </af-button>
              }
            </div>
          </form>
        }
      </ng-container>
    </af-page>
  `,
})
export class ReservationEditPage {
  protected readonly store = inject(ReservationsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly reservationId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.reservationId() === null);
  protected readonly canMutate = computed(
    () => this.session.isClubAdmin() || this.session.isSystemAdmin(),
  );

  protected readonly form: ReservationForm = this.fb.group({
    aircraftId: this.fb.nonNullable.control('', [Validators.required]),
    pilotPersonId: this.fb.nonNullable.control('', [Validators.required]),
    secondCrewPersonId: this.fb.nonNullable.control(''),
    locationId: this.fb.nonNullable.control('', [Validators.required]),
    reservationTypeId: this.fb.nonNullable.control('', [Validators.required]),
    date: this.fb.nonNullable.control('', [Validators.required]),
    startTime: this.fb.nonNullable.control('', [Validators.required]),
    endTime: this.fb.nonNullable.control('', [Validators.required]),
    isAllDay: this.fb.nonNullable.control(false),
    remarks: this.fb.nonNullable.control(''),
  });

  protected readonly saveSubmitted = signal(false);
  protected readonly isAllDay = toSignal(this.form.controls.isAllDay.valueChanges, {
    initialValue: false,
  });

  protected readonly aircraftOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.aircraftPicker().map((a) => ({ value: a.id, label: a.immatriculation })),
  );
  protected readonly typeOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.reservationTypes().map((rt) => ({ value: rt.id, label: rt.name })),
  );
  protected readonly personOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store
      .persons()
      .map((p) => ({ value: p.id, label: `${p.firstname} ${p.lastname}`.trim() })),
  );
  protected readonly secondCrewOptions = computed<readonly AfSelectOption<string>[]>(() => [
    { value: '', label: '— None —' },
    ...this.personOptions(),
  ]);
  protected readonly locationOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.locations().map((l) => ({ value: l.id, label: l.locationName })),
  );

  constructor() {
    // Picker payloads + types load with the list decorations (TTL-gated/cheap).
    this.store.loadDecorations();

    // All-day toggle disables/enables the time controls (so `form.invalid`
    // doesn't gate on hidden required times). One effect, no manual subscribe.
    effect(() => {
      const controls = [this.form.controls.startTime, this.form.controls.endTime];
      if (this.isAllDay()) {
        for (const c of controls) c.disable({ emitEvent: false });
      } else {
        for (const c of controls) c.enable({ emitEvent: false });
      }
    });

    effect(() => {
      const id = this.reservationId();
      if (id === null) {
        this.store.selectNew();
        // Create mode: pre-seed `date` + `locationId` from the query params the
        // planning-day edit page's "New reservation" button passes (legacy
        // `PlanningDayEditController.js:128-132` parity). Absent → no-op.
        const params = this.route.snapshot.queryParamMap;
        const seed: Partial<{ date: string; locationId: string }> = {};
        const date = params.get('date');
        const locationId = params.get('locationId');
        if (date) seed.date = date;
        if (locationId) seed.locationId = locationId;
        if (date || locationId) this.form.patchValue(seed);
        return;
      }
      this.store.loadDetail(id);
    });

    effect(() => {
      const detail = this.store.selectedDetail();
      if (!detail) return;
      this.form.patchValue(detailToFormValue(detail));
    });

    const destroyRef = inject(DestroyRef);
    // Navigate only on the bus success event — guaranteed post-response, no race
    // (a 409/422 leaves us on the form with the inline error visible).
    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (evt.kind === 'reservation.created' || evt.kind === 'reservation.updated') {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/reservations');
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
    const id = this.reservationId();
    if (id === null) {
      this.store.create(formToRequest(this.form));
    } else {
      this.store.update({ id, req: formToRequest(this.form) });
    }
  }
}

function detailToFormValue(d: AircraftReservationDetail): Partial<{
  aircraftId: string;
  pilotPersonId: string;
  secondCrewPersonId: string;
  locationId: string;
  reservationTypeId: string;
  date: string;
  startTime: string;
  endTime: string;
  isAllDay: boolean;
  remarks: string;
}> {
  return {
    aircraftId: d.aircraftId,
    pilotPersonId: d.pilotPersonId,
    secondCrewPersonId: d.secondCrewPersonId ?? '',
    locationId: d.locationId,
    reservationTypeId: d.reservationTypeId ?? '',
    date: isoDate(d.start),
    startTime: isoTime(d.start),
    endTime: isoTime(d.end),
    isAllDay: d.isAllDay,
    remarks: d.remarks ?? '',
  };
}

/**
 * Form → create/update request. Both verbs share the identical body shape
 * (the generated create/update requests are structurally equal), so ONE builder
 * — `withOptionals` prunes the empty optionals in a single pass (no per-field
 * `if`). All-day collapses start=end=midnight-of-date; timed builds explicit
 * `<date>T<time>` instants.
 */
function formToRequest(
  form: ReservationForm,
): AircraftReservationCreateRequest & AircraftReservationUpdateRequest {
  const v = form.getRawValue();
  const start = v.isAllDay ? `${v.date}T00:00:00Z` : `${v.date}T${v.startTime}:00Z`;
  const end = v.isAllDay ? `${v.date}T00:00:00Z` : `${v.date}T${v.endTime}:00Z`;
  return withOptionals(
    {
      aircraftId: v.aircraftId,
      pilotPersonId: v.pilotPersonId,
      locationId: v.locationId,
      start,
      end,
      isAllDay: v.isAllDay,
    },
    {
      secondCrewPersonId: v.secondCrewPersonId,
      reservationTypeId: v.reservationTypeId,
      remarks: v.remarks,
    },
  );
}

function isoDate(iso: string): string {
  return iso.slice(0, 10);
}

function isoTime(iso: string): string {
  // `2026-07-01T10:00:00Z` → `10:00`.
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m?.[1] ?? '';
}
