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
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { debounceTime, distinctUntilChanged, map } from 'rxjs';

import type {
  PlanningDayCreateRequest,
  PlanningDayDetail,
  PlanningDayUpdateRequest,
  PlanningDayValidateRequest,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfReservationRowComponent } from '@ui/molecules/af-reservation-row';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';
import { liveFieldErrors, withOptionals } from '@shared/util/form';

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
 * filtered to the day's location) and renders each row through the shared
 * `<af-reservation-row>` molecule (J-6 T-08b — one reservation-row UI, not a
 * bespoke copy); each row links into J-5's reservation editor; "new
 * reservation" pre-seeds date + location into J-5's create form (legacy
 * `PlanningDayEditController.js:96-104,128-132`).
 *
 * The panel is keyed on **date + location**, NOT a saved planning-day id (the
 * deliberate improvement over legacy's `id==='new' → []` gate, T-08c). It loads
 * + renders reactively whenever the form's `planningDate` AND `locationId` are
 * both set — in CREATE mode (a new day, before save) and EDIT mode alike — so
 * the operator sees that date+location's reservations the moment they pick them,
 * no save required. The read is the J-5 `aircraft-reservations day/{date}` join
 * filtered to the location; the store owns the call (§4 — no HTTP in components),
 * bridged off the form's value changes (a debounced `toSignal`).
 */
@Component({
  selector: 'af-planning-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfFormFieldComponent,
    AfInputComponent,
    AfSelectComponent,
    AfButtonComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfReservationRowComponent,
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
                <div class="flex flex-col gap-1">
                  <af-form-field
                    [label]="t('date')"
                    for="PlanningDate"
                    [required]="true"
                    [errors]="dateErrors()"
                  >
                    <af-input
                      inputId="PlanningDate"
                      type="date"
                      formControlName="planningDate"
                      data-testid="planning-date"
                    />
                  </af-form-field>

                  <!--
                    Async (date, location) uniqueness pre-check (T-07): the STORE
                    calls the T-05 validate endpoint (debounced) when planningDate
                    + locationId are set; its result surfaces inline here on the
                    date field — the SAME J-6 ux_pln_club_date_loc uniqueness the
                    save-path 409 enforces, shown earlier WITHOUT a save round-trip.
                    The required-field message above uses the shared debounced
                    liveFieldErrors; this is the async leg.
                  -->
                  @if (store.uniquenessValidating()) {
                    <span
                      class="block text-sm text-slate-500"
                      data-testid="planning-date-validating"
                      role="status"
                      aria-live="polite"
                    >
                      {{ t('validating') }}
                    </span>
                  } @else if (store.uniquenessMessage() !== null) {
                    <span
                      class="block text-sm text-red-600"
                      data-testid="planning-date-server-error"
                      role="alert"
                    >
                      {{ t('duplicate') }}
                    </span>
                  }
                </div>
                <af-form-field
                  [label]="t('location')"
                  for="PlanningLocation"
                  [required]="true"
                  [errors]="locationErrors()"
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
              <!--
                Read-only to edit affordance (T-09, operator #11). In VIEW mode
                an editor (canMutate) gets an Edit button that navigates to the
                same day's /edit route; mode=edit flips isView() false so the
                form re-enables cleanly (the disable effect runs on the route
                change) and Save returns. Navigation (not a local mode signal)
                keeps isView()/canMutate() driven by the single route source.
              -->
              @if (isView() && canMutate() && editLink() !== null) {
                <af-button
                  type="primary"
                  htmlType="button"
                  (clicked)="router.navigateByUrl(editLink()!)"
                  data-testid="planning-edit-toggle"
                >
                  {{ t('edit') }}
                </af-button>
              }
              @if (!isView() && canMutate()) {
                <af-button
                  type="primary"
                  htmlType="submit"
                  [disabled]="form.invalid || saveSubmitted() || store.uniquenessMessage() !== null"
                  data-testid="planning-save-button"
                >
                  {{ t('save') }}
                </af-button>
              }
            </div>
          </form>

          <!--
            Per-day reservations (J-5 read-side join) — keyed on date + location
            (T-08c), shown the moment both are picked, no save required.
          -->
          @if (showReservations()) {
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
                  <af-reservation-row
                    [reservation]="r"
                    [aircraftLabel]="immat(r.aircraftId)"
                    [openLink]="['/reservations', r.id, 'edit']"
                    [openQueryParams]="reservationReturnParams()"
                    [openLabel]="t('reservations.open')"
                    testIdPrefix="planning-reservation"
                  />
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
  // Read-only → edit target (T-09): the same day's `/edit` route, or null in
  // create mode (no saved id to edit). Pure-derived so it's unit-testable.
  protected readonly editLink = computed(() => planningEditLink(this.planningId()));
  // Query params handed to the inline reservation rows' open-link (T-10): a
  // `returnUrl` pointing at THIS planning-day's current url (view OR edit), so
  // the reservation editor's Cancel returns the user exactly here, not to the
  // /reservations overview. `null` in create mode (no saved id, no day url to
  // return to). Pure-derived → unit-testable via `planningDayLink`.
  protected readonly reservationReturnParams = computed<Record<string, string> | null>(() => {
    const returnUrl = planningDayLink(this.planningId(), this.mode());
    return returnUrl === null ? null : { returnUrl };
  });

  protected readonly form: PlanningForm = this.fb.group({
    planningDate: this.fb.nonNullable.control('', [Validators.required]),
    locationId: this.fb.nonNullable.control('', [Validators.required]),
    instructorPersonId: this.fb.nonNullable.control(''),
    towingPilotPersonId: this.fb.nonNullable.control(''),
    flightOperatorPersonId: this.fb.nonNullable.control(''),
    info: this.fb.nonNullable.control(''),
  });

  protected readonly saveSubmitted = signal(false);

  // Inline validation WHILE TYPING (T-07, via T-03 `liveFieldErrors`): the two
  // required fields (Date + Location) show their client-side message debounced
  // ~200ms and clear on valid — replacing the touched-only `[errors]="ctl.touched
  // ? …"` wiring. Towing/operator/instructor stay OPTIONAL (oracle: no required).
  // The date control also carries the async (date, location) uniqueness leg
  // (T-05 `…/validate`), merged into the SAME inline slot via `liveFieldErrors`'s
  // `asyncErrors$` hook; the dedicated `planning-date-server-error` element
  // (template) carries the stable testid + the submit-block derive from the same
  // store uniqueness signal.
  protected readonly dateErrors = liveFieldErrors(this.form.controls.planningDate, {
    asyncErrors$: toObservable(this.store.uniquenessErrors),
  });
  protected readonly locationErrors = liveFieldErrors(this.form.controls.locationId);

  // The reservations panel is keyed on date + location (T-08c), not the saved
  // day id. Bridge the two form fields to a signal (debounced — typing a date
  // emits per keystroke) so the panel render-gate + the store fetch both derive
  // from the live form, in create AND edit mode. `getRawValue()` reads disabled
  // controls too, so a VIEW-mode (read-only) day still keys correctly. The
  // loaded-detail key is merged in as a fallback: patching a disabled (view)
  // form does not re-emit `valueChanges`, so seed the key from `selectedDetail`
  // when it lands — either source completing the (date, location) pair shows
  // the panel.
  private readonly liveFormKey = toSignal(
    this.form.valueChanges.pipe(
      map(() => this.form.getRawValue()),
      map((v) => ({ date: v.planningDate, locationId: v.locationId })),
      debounceTime(200),
      distinctUntilChanged((a, b) => a.date === b.date && a.locationId === b.locationId),
    ),
    { initialValue: { date: '', locationId: '' } },
  );
  protected readonly formKey = computed(() => {
    const live = this.liveFormKey();
    if (live.date !== '' && live.locationId !== '') return live;
    // Fall back to the loaded detail (view-mode patch suppresses valueChanges).
    const detail = this.store.selectedDetail();
    if (detail) return { date: detail.planningDate, locationId: detail.locationId };
    return live;
  });
  protected readonly showReservations = computed(() => {
    const { date, locationId } = this.formKey();
    return date !== '' && locationId !== '';
  });

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
      // Patching the form re-emits valueChanges → the date+location bridge picks
      // up the loaded day's key and the reservations load fires from there (the
      // single reactive path below), so an edit-mode day shows its reservations
      // the same way a create-mode date pick does — no separate load call.
      this.form.patchValue(detailToFormValue(detail));
    });

    // Reactive inline-reservations load (T-08c): whenever date + location are
    // BOTH set — create mode (new day, pre-save) or edit mode — (re)fetch that
    // date+location's reservations (the J-5 `day/{date}` join, store-owned).
    // Keyed on (date, location), debounced upstream; no saved-day id required.
    effect(() => {
      const { date, locationId } = this.formKey();
      if (date === '' || locationId === '') {
        this.store.clearDayReservations();
        return;
      }
      this.store.loadDayReservations({ date, locationId });
    });

    // Async (date, location) uniqueness pre-check (T-07): when planningDate +
    // locationId are BOTH set/changed, the STORE calls the T-05 `…/validate`
    // endpoint (debounced in the store) — the SAME J-6 `ux_pln_club_date_loc`
    // uniqueness the save path enforces, surfaced inline earlier on the date
    // field. `excludePlanningDayId` self-excludes the edited day. No HttpClient
    // here (CLAUDE.md §4). Keyed on the same debounced (date, location) signal
    // the reservations panel uses; the store debounces the HTTP itself.
    effect(() => {
      const { date, locationId } = this.formKey();
      const probe = uniquenessProbe(date, locationId, this.planningId());
      if (!probe) {
        this.store.clearUniquenessValidation();
        return;
      }
      this.store.validateUniqueness(probe);
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

  /**
   * Pre-seed J-5's reservation create form with this day's date + location.
   * Reads the live form (not the saved detail) so it works in create mode too —
   * the panel renders the moment date+location are picked (T-08c).
   */
  protected newReservation(): void {
    const { planningDate: date, locationId } = this.form.getRawValue();
    if (date === '' || locationId === '') return;
    void this.router.navigate(['/reservations', 'new'], {
      queryParams: { date, locationId },
    });
  }

  protected immat(aircraftId: string): string {
    return this.store.immatById()[aircraftId] ?? aircraftId;
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
 * Read-only → edit navigation target for the planning-day at `id`: the same
 * day's `:mode=edit` route, or `null` when there is no saved id (create mode —
 * nothing to edit-toggle). Navigating flips the route `:mode` to `edit`, which
 * drives `isView()` false → the disable effect re-enables the form (J-6b T-09).
 * Pure → unit-tested without a `TestBed`.
 */
export function planningEditLink(id: string | null): string | null {
  return id === null ? null : `/planning/${id}/edit`;
}

/**
 * The planning-day route URL for `id` in the given `:mode` (`view`/`edit`) — the
 * page the user is currently on. Handed to the inline reservation rows as a
 * `returnUrl` so the reservation editor's Cancel returns exactly here (J-6b
 * T-10). `null` in create mode (no saved id → no day url to return to). Pure →
 * unit-tested without a `TestBed`.
 */
export function planningDayLink(id: string | null, mode: string): string | null {
  return id === null ? null : `/planning/${id}/${mode}`;
}

/**
 * Build the T-05 `…/validate` uniqueness-probe request, or `null` when the
 * (date, location) key is not yet complete enough to probe. `editId` (the route
 * `:id` on an edit) is sent as `excludePlanningDayId` so the planning day does
 * not self-conflict on the uniqueness check. Pure → unit-tested without a
 * `TestBed`.
 */
export function uniquenessProbe(
  date: string,
  locationId: string,
  editId: string | null,
): PlanningDayValidateRequest | null {
  if (date === '' || locationId === '') return null;
  return withOptionals({ planningDate: date, locationId }, { excludePlanningDayId: editId ?? '' });
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
