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
  protected readonly editLink = computed(() => planningEditLink(this.planningId()));
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

  protected readonly dateErrors = liveFieldErrors(this.form.controls.planningDate, {
    asyncErrors$: toObservable(this.store.uniquenessErrors),
  });
  protected readonly locationErrors = liveFieldErrors(this.form.controls.locationId);

  private readonly liveDateAndLocationKey = toSignal(
    this.form.valueChanges.pipe(
      map(() => this.form.getRawValue()),
      map((v) => ({ date: v.planningDate, locationId: v.locationId })),
      debounceTime(200),
      distinctUntilChanged((a, b) => a.date === b.date && a.locationId === b.locationId),
    ),
    { initialValue: { date: '', locationId: '' } },
  );
  protected readonly dateAndLocationKey = computed(() => {
    const live = this.liveDateAndLocationKey();
    if (live.date !== '' && live.locationId !== '') return live;
    const detail = this.store.selectedDetail();
    if (detail) return { date: detail.planningDate, locationId: detail.locationId };
    return live;
  });
  protected readonly showReservations = computed(() => {
    const { date, locationId } = this.dateAndLocationKey();
    return date !== '' && locationId !== '';
  });

  protected readonly locationOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.locations().map((l) => ({ value: l.id, label: l.locationName })),
  );
  protected readonly crewOptions = computed<readonly AfSelectOption<string>[]>(() => [
    { value: '', label: '— None —' },
    ...this.store
      .persons()
      .map((p) => ({ value: p.id, label: `${p.firstname} ${p.lastname}`.trim() })),
  ]);

  constructor() {
    this.store.loadDecorations();

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
    });

    effect(() => {
      const { date, locationId } = this.dateAndLocationKey();
      if (date === '' || locationId === '') {
        this.store.clearDayReservations();
        return;
      }
      this.store.loadDayReservations({ date, locationId });
    });

    effect(() => {
      const { date, locationId } = this.dateAndLocationKey();
      const probe = uniquenessProbe(date, locationId, this.planningId());
      if (!probe) {
        this.store.clearUniquenessValidation();
        return;
      }
      this.store.validateUniqueness(probe);
    });

    const destroyRef = inject(DestroyRef);
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

export function planningEditLink(id: string | null): string | null {
  return id === null ? null : `/planning/${id}/edit`;
}

export function planningDayLink(id: string | null, mode: string): string | null {
  return id === null ? null : `/planning/${id}/${mode}`;
}

export function uniquenessProbe(
  date: string,
  locationId: string,
  editId: string | null,
): PlanningDayValidateRequest | null {
  if (date === '' || locationId === '') return null;
  return withOptionals({ planningDate: date, locationId }, { excludePlanningDayId: editId ?? '' });
}

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
