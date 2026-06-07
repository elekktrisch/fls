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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import type {
  AircraftReservationListItem,
  PlanningDayCreateRequest,
  PlanningDayDetail,
  PlanningDayUpdateRequest,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';
import { withOptionals } from '@shared/util/form';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { SessionStore } from '../../../core/session/session.store';
import { PlanningStore } from '../planning.store';

type PlanningForm = FormGroup<{
  planningDate: FormControl<string>;
  locationId: FormControl<string>;
  instructorPersonId: FormControl<string>;
  towingPilotPersonId: FormControl<string>;
  flightOperatorPersonId: FormControl<string>;
  info: FormControl<string>;
}>;

/**
 * Planning-day create / edit / view form (J-6 T-08).
 *
 * Built on J-5's reservation edit-form idiom — typed reactive `FormGroup`,
 * load-on-`:id`, submit→store, inline save-error — and LOW-CRAP via the shared
 * `@shared/util/form` helper (`withOptionals` prunes the empty crew/info
 * optionals in one pass; the store maps the 409 via `mapApiSaveError`). The
 * flagged `formToUpdateRequest` / `finalSubmit` / `errorPatch` cascade is
 * deliberately NOT replicated (the low-CRAP rider — J-6 Notes / `_BOYSCOUT.md`).
 *
 * Modes (`:mode` route param): `edit` editable, `view` read-only (controls
 * disabled, no save), create when there is no `:id` (`/planning/new/edit`).
 * The 3 person pickers map to the 3 nullable person-id DTO fields; an empty
 * picker omits the field (`withOptionals`) → the backend clears that role
 * (oracle: null/empty removes the assignment row). Duplicate (date, location)
 * → 409 surfaces inline via `store.saveError()`; the route navigates only on
 * the mutation-bus success event (the SPA-nav-evicts-response-body trap).
 *
 * The per-day reservations panel reuses the J-5 read-side (`day/{date}`,
 * filtered to the day's location) — each row links into J-5's reservation
 * editor; "new reservation" pre-seeds date + location into J-5's create form
 * (legacy `PlanningDayEditController.js:96-104,128-132`).
 */
@Component({
  selector: 'af-planning-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    RouterLink,
    AfFormFieldComponent,
    AfInputComponent,
    AfSelectComponent,
    AfButtonComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'planning.form'">
        <af-page-header [title]="title(t)" />

        <af-page-error
          [message]="store.saveError()"
          [retryLabel]="null"
          data-testid="planning-save-error"
        />

        @if (store.isLoadingDetail() && !isCreate()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
            data-testid="planning-loading"
            role="status"
            aria-live="polite"
          >
            {{ t('loading') }}
          </div>
        } @else {
          <form
            [formGroup]="form"
            (ngSubmit)="onSubmit()"
            data-testid="planning-edit-form"
            class="flex flex-col gap-6"
            novalidate
          >
            <!-- Section 1: Day -->
            <section class="flex flex-col gap-2" data-testid="planning-section-day">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('sections.day') }}
              </h2>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
                <af-form-field
                  [label]="t('date')"
                  for="PlanningDate"
                  [required]="true"
                  [errors]="
                    form.controls.planningDate.touched ? form.controls.planningDate.errors : null
                  "
                >
                  <af-input
                    inputId="PlanningDate"
                    type="date"
                    formControlName="planningDate"
                    data-testid="planning-date"
                  />
                </af-form-field>
                <af-form-field
                  [label]="t('location')"
                  for="PlanningLocation"
                  [required]="true"
                  [errors]="
                    form.controls.locationId.touched ? form.controls.locationId.errors : null
                  "
                >
                  <af-select
                    inputId="PlanningLocation"
                    formControlName="locationId"
                    [placeholder]="t('selectLocation')"
                    [options]="locationOptions()"
                    data-testid="planning-location-select"
                  />
                </af-form-field>
              </div>
            </section>

            <!-- Section 2: Crew -->
            <section class="flex flex-col gap-2" data-testid="planning-section-crew">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('sections.crew') }}
              </h2>
              <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
                <af-form-field [label]="t('instructor')" for="PlanningInstructor">
                  <af-select
                    inputId="PlanningInstructor"
                    formControlName="instructorPersonId"
                    [placeholder]="t('selectCrew')"
                    [options]="crewOptions()"
                    data-testid="planning-instructor-select"
                  />
                </af-form-field>
                <af-form-field [label]="t('towPilot')" for="PlanningTowPilot">
                  <af-select
                    inputId="PlanningTowPilot"
                    formControlName="towingPilotPersonId"
                    [placeholder]="t('selectCrew')"
                    [options]="crewOptions()"
                    data-testid="planning-towpilot-select"
                  />
                </af-form-field>
                <af-form-field [label]="t('flightOperator')" for="PlanningFlightOp">
                  <af-select
                    inputId="PlanningFlightOp"
                    formControlName="flightOperatorPersonId"
                    [placeholder]="t('selectCrew')"
                    [options]="crewOptions()"
                    data-testid="planning-flightop-select"
                  />
                </af-form-field>
              </div>
            </section>

            <!-- Section 3: Notes -->
            <section class="flex flex-col gap-2" data-testid="planning-section-notes">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('sections.notes') }}
              </h2>
              <af-form-field [label]="t('remarks')" for="PlanningRemarks">
                <af-input
                  inputId="PlanningRemarks"
                  formControlName="info"
                  autocomplete="off"
                  data-testid="planning-remarks"
                />
              </af-form-field>
            </section>

            <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
              <af-button htmlType="button" (clicked)="router.navigateByUrl('/planning')">
                {{ isView() ? t('back') : t('cancel') }}
              </af-button>
              @if (!isView() && canMutate()) {
                <af-button
                  type="primary"
                  htmlType="submit"
                  [disabled]="form.invalid || saveSubmitted()"
                  data-testid="planning-save-button"
                >
                  {{ t('save') }}
                </af-button>
              }
            </div>
          </form>

          <!-- Per-day reservations (J-5 read-side join) — only for a saved day. -->
          @if (!isCreate()) {
            <section
              class="flex flex-col gap-2 mt-8 pt-4 border-t border-slate-200"
              data-testid="planning-reservations-panel"
            >
              <div class="flex items-center justify-between">
                <h2 class="text-xs font-medium text-slate-600 uppercase tracking-wide">
                  {{ t('reservations.title') }}
                </h2>
                <af-button
                  type="default"
                  htmlType="button"
                  (clicked)="newReservation()"
                  data-testid="planning-new-reservation-button"
                >
                  <af-icon name="plus" [size]="16" class="mr-1.5 inline-flex align-middle" />
                  {{ t('reservations.new') }}
                </af-button>
              </div>

              <div class="border border-slate-200" data-testid="planning-reservations-list">
                @for (r of store.dayReservations(); track r.id) {
                  <div
                    class="flex items-center justify-between border-b border-slate-200 last:border-b-0 px-3 py-2.5 text-sm hover:bg-slate-50"
                    [attr.data-testid]="'planning-reservation-' + r.id"
                  >
                    <div class="flex items-center gap-3">
                      <span class="tabular font-medium text-slate-900">{{ timeLabel(r) }}</span>
                      <span class="text-slate-600">{{ immat(r.aircraftId) }}</span>
                      @if (r.reservationTypeName) {
                        <span class="text-xs text-slate-500">{{ r.reservationTypeName }}</span>
                      }
                    </div>
                    <a
                      class="text-brand-600 no-underline hover:text-brand-700"
                      [routerLink]="['/reservations', r.id, 'edit']"
                      [attr.data-testid]="'planning-reservation-edit-' + r.id"
                    >
                      {{ t('reservations.open') }}
                    </a>
                  </div>
                }
                @if (store.dayReservations().length === 0) {
                  <div
                    class="px-3 py-3 text-sm text-slate-500"
                    data-testid="planning-reservations-empty"
                  >
                    {{ t('reservations.empty') }}
                  </div>
                }
              </div>
            </section>
          }
        }
      </ng-container>
    </af-page>
  `,
})
export class PlanningEditPage {
  protected readonly store = inject(PlanningStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeParams = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly planningId = computed(() => this.routeParams().get('id'));
  protected readonly mode = computed(() => this.routeParams().get('mode') ?? 'edit');
  protected readonly isCreate = computed(() => this.planningId() === null);
  protected readonly isView = computed(() => this.mode() === 'view');
  protected readonly canMutate = computed(
    () => this.session.isClubAdmin() || this.session.isSystemAdmin(),
  );

  protected readonly form: PlanningForm = this.fb.group({
    planningDate: this.fb.nonNullable.control('', [Validators.required]),
    locationId: this.fb.nonNullable.control('', [Validators.required]),
    instructorPersonId: this.fb.nonNullable.control(''),
    towingPilotPersonId: this.fb.nonNullable.control(''),
    flightOperatorPersonId: this.fb.nonNullable.control(''),
    info: this.fb.nonNullable.control(''),
  });

  protected readonly saveSubmitted = signal(false);

  protected readonly locationOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.locations().map((l) => ({ value: l.id, label: l.locationName })),
  );
  // Crew pickers are optional — a leading blank clears the role assignment.
  protected readonly crewOptions = computed<readonly AfSelectOption<string>[]>(() => [
    { value: '', label: '— None —' },
    ...this.store
      .persons()
      .map((p) => ({ value: p.id, label: `${p.firstname} ${p.lastname}`.trim() })),
  ]);

  constructor() {
    // Picker payloads (locations + persons) are loaded by the store on init.
    this.store.loadDecorations();

    // View mode disables the whole form (read-only render).
    effect(() => {
      if (this.isView()) this.form.disable({ emitEvent: false });
      else this.form.enable({ emitEvent: false });
    });

    effect(() => {
      const id = this.planningId();
      if (id === null) {
        this.store.selectNew();
        return;
      }
      this.store.loadDetail(id);
    });

    effect(() => {
      const detail = this.store.selectedDetail();
      if (!detail) return;
      this.form.patchValue(detailToFormValue(detail));
      // Load that day's reservations (J-5 read-side) for the inline panel.
      this.store.loadDayReservations({
        date: detail.planningDate,
        locationId: detail.locationId,
      });
    });

    const destroyRef = inject(DestroyRef);
    // Navigate only on the bus success event — a 409/422/403 leaves us on the
    // form with the inline error visible (no nav-evicts-response-body race).
    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (evt.kind === 'planningDay.created' || evt.kind === 'planningDay.updated') {
        this.saveSubmitted.set(false);
        void this.router.navigateByUrl('/planning');
      }
    });
  }

  protected title(t: (key: string) => string): string {
    if (this.isCreate()) return t('titleNew');
    return this.isView() ? t('titleView') : t('title');
  }

  protected onSubmit(): void {
    if (this.isView() || !this.canMutate()) return;
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saveSubmitted.set(true);
    const id = this.planningId();
    if (id === null) {
      this.store.create(formToCreateRequest(this.form));
    } else {
      this.store.update({ id, req: formToCreateRequest(this.form) });
    }
  }

  /** Pre-seed J-5's reservation create form with this day's date + location. */
  protected newReservation(): void {
    const detail = this.store.selectedDetail();
    if (!detail) return;
    void this.router.navigate(['/reservations', 'new'], {
      queryParams: { date: detail.planningDate, locationId: detail.locationId },
    });
  }

  protected immat(aircraftId: string): string {
    return this.store.immatById()[aircraftId] ?? aircraftId;
  }

  /** `HH:mm–HH:mm`, or the all-day label, from the reservation's instants. */
  protected timeLabel(r: AircraftReservationListItem): string {
    if (r.isAllDay) return '00:00–24:00';
    return `${isoTime(r.start)}–${isoTime(r.end)}`;
  }
}

function detailToFormValue(d: PlanningDayDetail): Partial<{
  planningDate: string;
  locationId: string;
  instructorPersonId: string;
  towingPilotPersonId: string;
  flightOperatorPersonId: string;
  info: string;
}> {
  return {
    planningDate: d.planningDate,
    locationId: d.locationId,
    instructorPersonId: d.instructorPersonId ?? '',
    towingPilotPersonId: d.towingPilotPersonId ?? '',
    flightOperatorPersonId: d.flightOperatorPersonId ?? '',
    info: d.info ?? '',
  };
}

/**
 * Form → create/update request. Both verbs share the identical body shape, so
 * ONE builder — `withOptionals` prunes the empty crew/info optionals in a
 * single pass (no per-field `if (v.x !== '') req.x = …` cascade). An omitted
 * crew field clears that role on the backend (oracle: null/empty removes the
 * assignment row).
 */
function formToCreateRequest(
  form: PlanningForm,
): PlanningDayCreateRequest & PlanningDayUpdateRequest {
  const v = form.getRawValue();
  return withOptionals(
    {
      planningDate: v.planningDate,
      locationId: v.locationId,
    },
    {
      instructorPersonId: v.instructorPersonId,
      towingPilotPersonId: v.towingPilotPersonId,
      flightOperatorPersonId: v.flightOperatorPersonId,
      info: v.info,
    },
  );
}

function isoTime(iso: string): string {
  // `2026-07-01T10:00:00Z` → `10:00`.
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m?.[1] ?? '';
}
