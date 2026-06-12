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

  // Live form-validity STATUS as a signal — `'VALID' | 'INVALID' | 'PENDING' |
  // 'DISABLED'`. The save-disable binding tracks THIS (not the `form.invalid`
  // getter, which the OnPush template only re-read on a CD tick, so the button
  // could render enabled for a beat after the second-crew validator flipped the
  // form back to invalid — J-7 T-20). `statusChanges` fires on every validity
  // transition INCLUDING entering/leaving `'PENDING'`, so an async validator's
  // pending window keeps Save disabled. Seeded with the current status so it is
  // correct synchronously before the first emission.
  private readonly formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });
  protected readonly saveDisabled = computed(() =>
    saveDisabledFor(this.formStatus(), this.saveSubmitted(), this.store.overlapMessage() !== null),
  );

  // Raw form value as a signal (includes disabled controls — all-day disables
  // the time fields). Drives the async overlap-validate effect so it re-runs on
  // every keystroke; the store debounces the actual HTTP call.
  private readonly formValue = toSignal(
    this.form.valueChanges.pipe(map(() => this.form.getRawValue())),
    { initialValue: this.form.getRawValue() },
  );

  // Inline validation WHILE TYPING (T-06, via T-03 `liveFieldErrors`): each
  // required field shows its client-side message debounced ~200ms and clears on
  // valid — replacing the touched-only `[errors]="ctl.touched ? …"` wiring.
  protected readonly aircraftErrors = liveFieldErrors(this.form.controls.aircraftId);
  protected readonly typeErrors = liveFieldErrors(this.form.controls.reservationTypeId);
  protected readonly pilotErrors = liveFieldErrors(this.form.controls.pilotPersonId);
  protected readonly locationErrors = liveFieldErrors(this.form.controls.locationId);
  protected readonly dateErrors = liveFieldErrors(this.form.controls.date);
  protected readonly secondCrewErrors = liveFieldErrors(this.form.controls.secondCrewPersonId);
  // The slot's async overlap leg (T-04 `…/validate`) merges into the start-time
  // control's live errors via `liveFieldErrors`'s `asyncErrors$` hook — the
  // SAME inline slot the client rules render in. The dedicated
  // `reservation-start-server-error` element (template) carries the stable
  // testid; the visible slot message + submit-block derive from the same store
  // overlap signal.
  protected readonly startTimeErrors = liveFieldErrors(this.form.controls.startTime, {
    asyncErrors$: toObservable(this.store.overlapErrors),
  });
  protected readonly endTimeErrors = liveFieldErrors(this.form.controls.endTime);

  // Conditional Second-Crew (oracle: required when the selected TYPE requires a
  // second crew member OR the selected AIRCRAFT has `NrOfSeats > 1`). T-18 put
  // `instructorRequired` on `AircraftReservationTypeListItem` (AlpenFlight
  // collapses legacy's three FlightType flags into that one) + `nrOfSeats` on
  // `AircraftPickerItem`, so this derived signal now reads the real picker data
  // (T-06 wired the dynamic validator; this is the live driver). Re-derives when
  // the type/aircraft selection changes or the pickers load.
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

    // Conditional Second-Crew validator — toggle `required` on the control as the
    // derived condition flips (`secondCrewRequired`, live since T-18 exposed the
    // driving instructor/seat flags on the picker projections). The
    // `updateValueAndValidity()` EMITS (no `emitEvent: false`) so two things
    // re-derive the instant the requirement flips: the control's
    // `liveFieldErrors` stream (the inline message surfaces immediately, never a
    // silent gap) and the form `statusChanges` the save-disable binding tracks
    // (so the button flips disabled in lock-step — closes the J-7 T-20 race
    // where the validator flipped but the disable binding lagged a CD tick).
    effect(() => {
      const ctl = this.form.controls.secondCrewPersonId;
      ctl.setValidators(this.secondCrewRequired() ? [Validators.required] : []);
      ctl.updateValueAndValidity();
    });

    // Async overlap pre-check (T-06): when aircraft + start + end + all-day are
    // set/changed, the STORE calls the T-04 `…/validate` endpoint (debounced in
    // the store) — the SAME J-5 overlap probe the save path runs, surfaced inline
    // earlier. `excludeReservationId` self-excludes the edited reservation. The
    // component injects the store (no HttpClient here, CLAUDE.md §4). Driven off
    // the form value SIGNAL so the effect re-tracks on every keystroke.
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
    // Gate on the live STATUS, not the `form.invalid` getter: a click that lands
    // in the `'PENDING'` window (an async validator still resolving) must NOT
    // dead-end silently. Surface inline feedback — mark every control touched AND
    // re-emit each control's value so the debounced `liveFieldErrors` streams
    // re-derive and the inline messages appear (they key off `valueChanges`, so
    // `markAllAsTouched` alone leaves a freshly-required control's error silent —
    // exactly the J-7 T-20 no-op this fixes).
    if (this.form.status !== 'VALID' || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      surfaceFieldErrors(this.form);
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

  /**
   * Cancel returns to where the user came from: when the planning inline list
   * opened this reservation it passed `?returnUrl=/planning/:id/<mode>` (J-6b
   * T-10), so Cancel goes back to that exact planning-day form. Standalone edit
   * (no returnUrl) keeps the original `/reservations` overview target. The
   * returnUrl is sanitized to an internal same-app path — an absent/external/
   * protocol-relative value falls back to `/reservations` (no open-redirect).
   */
  protected onCancel(): void {
    const target = sanitizeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
    void this.router.navigateByUrl(target);
  }
}

/**
 * Resolve a Cancel target from an optional `returnUrl` query param. Accepts only
 * an internal, same-app absolute path (`/planning/…`); rejects anything that
 * could navigate off-app — an external URL, a protocol-relative `//host`, or a
 * non-rooted value — and falls back to `/reservations`. Pure → unit-tested
 * without a `TestBed`.
 */
export function sanitizeReturnUrl(raw: string | null | undefined): string {
  const fallback = '/reservations';
  if (raw === null || raw === undefined || raw === '') return fallback;
  // Must be a single rooted path, not a protocol-relative `//host` and not a
  // scheme (`http:`, `javascript:`, ...). `/foo` is internal; `//foo`, `http://`,
  // `javascript:` are not.
  if (!raw.startsWith('/') || raw.startsWith('//')) return fallback;
  // A backslash or control char right after the leading `/` is a known
  // open-redirect bypass: browsers normalise `\\` to `/`, so `/\\evil.com`
  // resolves like the protocol-relative `//evil.com`. Reject any backslash or
  // C0/C1 control char anywhere in the path -- only a normal rooted internal
  // path passes.
  // eslint-disable-next-line no-control-regex
  if (/[\\\u0000-\u001f\u007f-\u009f]/.test(raw)) return fallback;
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

/** Raw reservation form value (disabled controls included via `getRawValue`). */
type RawReservationValue = ReturnType<ReservationForm['getRawValue']>;

/**
 * Build the slot's `<date>T<time>` instants. All-day collapses
 * `start = end = midnight-of-date` (the aggregate normalizes the full-day
 * span); timed builds explicit `<date>T<time>` instants. Shared by the save
 * builder and the overlap-probe builder so both send the SAME span the J-5
 * save path / T-04 validate endpoint compare against.
 */
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

/**
 * Build the T-04 `…/validate` overlap-probe request from the live form, or
 * `null` when the slot is not yet complete enough to probe (no aircraft, no
 * date, or a timed slot missing a time). `editId` (the route `:id` on an edit)
 * is sent as `excludeReservationId` so the reservation does not self-conflict.
 * Pure → unit-tested without a `TestBed`.
 */
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

/**
 * Whether the Save control is disabled, derived from the form's live validity
 * STATUS (not the cheaper `form.invalid` getter the template used to read). The
 * status is one of `'VALID' | 'INVALID' | 'PENDING' | 'DISABLED'` — Save is
 * enabled ONLY on `'VALID'`, so a `'PENDING'` window (an async validator still
 * running, or the synchronous second-crew validator that flips AFTER the
 * aircraft picker resolves `nrOfSeats`) keeps the button disabled instead of
 * momentarily showing it enabled while the form is in fact still invalid
 * (J-7 T-20 race). Also disabled while a save is in flight (`saveSubmitted`) or
 * an overlap pre-check has flagged a conflict (`hasOverlap`).
 *
 * Pure → unit-tested across PENDING → INVALID → VALID transitions without a
 * `TestBed`.
 */
export function saveDisabledFor(
  formStatus: string | null,
  saveSubmitted: boolean,
  hasOverlap: boolean,
): boolean {
  return formStatus !== 'VALID' || saveSubmitted || hasOverlap;
}

/**
 * Re-emit every enabled control's value so the debounced `liveFieldErrors`
 * streams re-derive and any inline error message surfaces. The streams key off
 * `valueChanges` (not touched/status), so a control that became required AFTER
 * the user last typed (the conditional second-crew validator) would otherwise
 * keep a silent slot. Called on a dead-end submit (form not `'VALID'`) so a
 * genuine click during a pending/invalid beat produces VISIBLE feedback rather
 * than the old `markAllAsTouched`-only no-op (J-7 T-20).
 *
 * `setValue(value)` with the default `emitEvent: true` pushes the SAME value
 * through `valueChanges` (no data change) — the debounced stream re-reads
 * `control.errors` and renders the message. Disabled controls (the all-day time
 * fields) are skipped — they carry no validity.
 */
function surfaceFieldErrors(form: ReservationForm): void {
  for (const ctl of Object.values<AbstractControl>(form.controls)) {
    if (ctl.enabled) ctl.setValue(ctl.value);
  }
}

/**
 * Conditional Second-Crew requirement (oracle: required when the selected
 * reservation TYPE requires a second crew member, OR the selected AIRCRAFT has
 * `NrOfSeats > 1`).
 *
 * Legacy drove the type leg off three FlightType-derived flags
 * (`IsInstructorRequired || ObserverPilotOrInstructorRequired ||
 * IsPassengerRequired`). AlpenFlight's reservation-type model COLLAPSES those
 * into the single `instructorRequired` flag (`AircraftReservationType` carries
 * only `is_instructor_required`), so the type leg here is that one boolean.
 * T-18 exposed it on `AircraftReservationTypeListItem` + exposed `nrOfSeats` on
 * `AircraftPickerItem`, so this now evaluates against real picker data (T-06
 * wired the dynamic validator; this flips it live).
 *
 * Pure → unit-tested without a `TestBed`. `type`/`aircraft` are the currently
 * selected picker items (or `null` when none / not yet loaded).
 */
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
  // `2026-07-01T10:00:00Z` → `10:00`.
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m?.[1] ?? '';
}
