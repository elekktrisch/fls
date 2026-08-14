import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import {
  type AbstractControl,
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { map } from 'rxjs';

import type {
  AircraftPickerItem,
  AircraftReservationCreateRequest,
  AircraftReservationDetail,
  AircraftReservationTypeListItem,
  AircraftReservationUpdateRequest,
  AircraftReservationValidateRequest,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';
import { liveFieldErrors, withOptionals } from '@shared/util/form';

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
                  [errors]="aircraftErrors()"
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
                  [errors]="typeErrors()"
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
                  [errors]="pilotErrors()"
                >
                  <af-select
                    inputId="ReservationPilot"
                    formControlName="pilotPersonId"
                    [placeholder]="t('selectPilot')"
                    [options]="personOptions()"
                    data-testid="reservation-pilot-select"
                  />
                </af-form-field>
                <af-form-field
                  [label]="t('secondCrew')"
                  for="ReservationSecondCrew"
                  [required]="secondCrewRequired()"
                  [errors]="secondCrewErrors()"
                >
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
                [errors]="locationErrors()"
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
                  [errors]="dateErrors()"
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
                    [errors]="startTimeErrors()"
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
                    [errors]="endTimeErrors()"
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

              <!--
                Async overlap pre-check (T-06): the STORE calls the T-04
                validate endpoint (debounced) when aircraft + start + end +
                all-day are set; its result surfaces inline here on the slot —
                the SAME J-5 overlap the save-path 409 enforces, shown earlier
                WITHOUT a save round-trip. The required-field messages above use
                the shared debounced liveFieldErrors; this is the async leg.
              -->
              @if (store.overlapValidating()) {
                <span
                  class="block text-sm text-slate-500"
                  data-testid="reservation-start-validating"
                  role="status"
                  aria-live="polite"
                >
                  {{ t('validating') }}
                </span>
              } @else if (store.overlapMessage() !== null) {
                <span
                  class="block text-sm text-red-600"
                  data-testid="reservation-start-server-error"
                  role="alert"
                >
                  {{ t('overlap') }}
                </span>
              }
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
              <af-button
                htmlType="button"
                (clicked)="onCancel()"
                data-testid="reservation-edit-cancel"
              >
                {{ t('cancel') }}
              </af-button>
              @if (canMutate()) {
                <af-button
                  type="primary"
                  htmlType="submit"
                  [disabled]="saveDisabled()"
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

  private readonly formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });
  protected readonly saveDisabled = computed(() =>
    saveDisabledFor(this.formStatus(), this.saveSubmitted(), this.store.overlapMessage() !== null),
  );

  private readonly formValue = toSignal(
    this.form.valueChanges.pipe(map(() => this.form.getRawValue())),
    { initialValue: this.form.getRawValue() },
  );

  protected readonly aircraftErrors = liveFieldErrors(this.form.controls.aircraftId);
  protected readonly typeErrors = liveFieldErrors(this.form.controls.reservationTypeId);
  protected readonly pilotErrors = liveFieldErrors(this.form.controls.pilotPersonId);
  protected readonly locationErrors = liveFieldErrors(this.form.controls.locationId);
  protected readonly dateErrors = liveFieldErrors(this.form.controls.date);
  protected readonly secondCrewErrors = liveFieldErrors(this.form.controls.secondCrewPersonId);
  protected readonly startTimeErrors = liveFieldErrors(this.form.controls.startTime, {
    asyncErrors$: toObservable(this.store.overlapErrors),
  });
  protected readonly endTimeErrors = liveFieldErrors(this.form.controls.endTime);

  protected readonly selectedType = computed<AircraftReservationTypeListItem | null>(
    () =>
      this.store.reservationTypes().find((t) => t.id === this.formValue().reservationTypeId) ??
      null,
  );
  protected readonly selectedAircraft = computed<AircraftPickerItem | null>(
    () => this.store.aircraftPicker().find((a) => a.id === this.formValue().aircraftId) ?? null,
  );
  protected readonly secondCrewRequired = computed(() =>
    secondCrewRequiredFor(this.selectedType(), this.selectedAircraft()),
  );

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
    this.store.loadDecorations();

    effect(() => {
      const controls = [this.form.controls.startTime, this.form.controls.endTime];
      if (this.isAllDay()) {
        for (const c of controls) c.disable({ emitEvent: false });
      } else {
        for (const c of controls) c.enable({ emitEvent: false });
      }
      this.republishFormStatusAfterSilentControlToggle();
    });

    effect(() => {
      const ctl = this.form.controls.secondCrewPersonId;
      ctl.setValidators(this.secondCrewRequired() ? [Validators.required] : []);
      ctl.updateValueAndValidity();
    });

    effect(() => {
      const v = this.formValue();
      const probe = overlapProbe(v, this.reservationId());
      if (!probe) {
        this.store.clearOverlapValidation();
        return;
      }
      this.store.validateOverlap(probe);
    });

    effect(() => {
      const id = this.reservationId();
      if (id === null) {
        this.store.selectNew();
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
    if (this.form.status !== 'VALID' || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      reemitValuesToRefreshLiveFieldErrors(this.form);
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

  protected onCancel(): void {
    const target = sanitizeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
    void this.router.navigateByUrl(target);
  }

  private republishFormStatusAfterSilentControlToggle(): void {
    this.form.updateValueAndValidity();
  }
}

// eslint-disable-next-line no-control-regex
const OPEN_REDIRECT_BYPASS_CHARS = /[\\\u0000-\u001f\u007f-\u009f]/;

export function sanitizeReturnUrl(raw: string | null | undefined): string {
  const fallback = '/reservations';
  if (raw === null || raw === undefined || raw === '') return fallback;
  const isRootedPath = raw.startsWith('/');
  const isProtocolRelativeUrl = raw.startsWith('//');
  if (!isRootedPath || isProtocolRelativeUrl) return fallback;
  if (OPEN_REDIRECT_BYPASS_CHARS.test(raw)) return fallback;
  return raw;
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

function formToRequest(
  form: ReservationForm,
): AircraftReservationCreateRequest & AircraftReservationUpdateRequest {
  const v = form.getRawValue();
  const span = buildSpan(v.date, v.startTime, v.endTime, v.isAllDay);
  return withOptionals(
    {
      aircraftId: v.aircraftId,
      pilotPersonId: v.pilotPersonId,
      locationId: v.locationId,
      start: span.start,
      end: span.end,
      isAllDay: v.isAllDay,
    },
    {
      secondCrewPersonId: v.secondCrewPersonId,
      reservationTypeId: v.reservationTypeId,
      remarks: v.remarks,
    },
  );
}

type RawReservationValue = ReturnType<ReservationForm['getRawValue']>;

function buildSpan(
  date: string,
  startTime: string,
  endTime: string,
  isAllDay: boolean,
): { start: string; end: string } {
  const start = isAllDay ? `${date}T00:00:00Z` : `${date}T${startTime}:00Z`;
  const end = isAllDay ? `${date}T00:00:00Z` : `${date}T${endTime}:00Z`;
  return { start, end };
}

export function overlapProbe(
  v: RawReservationValue,
  editId: string | null,
): AircraftReservationValidateRequest | null {
  if (v.aircraftId === '' || v.date === '') return null;
  if (!v.isAllDay && (v.startTime === '' || v.endTime === '')) return null;
  const span = buildSpan(v.date, v.startTime, v.endTime, v.isAllDay);
  return withOptionals(
    {
      aircraftId: v.aircraftId,
      start: span.start,
      end: span.end,
      isAllDay: v.isAllDay,
    },
    { excludeReservationId: editId ?? '' },
  );
}

export function saveDisabledFor(
  formStatus: string | null,
  saveSubmitted: boolean,
  hasOverlap: boolean,
): boolean {
  return formStatus !== 'VALID' || saveSubmitted || hasOverlap;
}

function reemitValuesToRefreshLiveFieldErrors(form: ReservationForm): void {
  for (const ctl of Object.values<AbstractControl>(form.controls)) {
    if (ctl.enabled) ctl.setValue(ctl.value);
  }
}

export function secondCrewRequiredFor(
  type: AircraftReservationTypeListItem | null,
  aircraft: AircraftPickerItem | null,
): boolean {
  const typeRequiresCrew = type?.instructorRequired === true;
  const multiSeat = (aircraft?.nrOfSeats ?? 0) > 1;
  return typeRequiresCrew || multiSeat;
}

function isoDate(iso: string): string {
  return iso.slice(0, 10);
}

function isoTime(iso: string): string {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m?.[1] ?? '';
}
