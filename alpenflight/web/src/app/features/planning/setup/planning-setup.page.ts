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
import { Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import type { PlanningDayRuleRequest } from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';
import { liveFieldErrors, withOptionals } from '@shared/util/form';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { PlanningStore } from '../planning.store';

type SetupForm = FormGroup<{
  startDate: FormControl<string>;
  endDate: FormControl<string>;
  locationId: FormControl<string>;
  everyMonday: FormControl<boolean>;
  everyTuesday: FormControl<boolean>;
  everyWednesday: FormControl<boolean>;
  everyThursday: FormControl<boolean>;
  everyFriday: FormControl<boolean>;
  everySaturday: FormControl<boolean>;
  everySunday: FormControl<boolean>;
  info: FormControl<string>;
}>;

const WEEKDAYS: readonly {
  testIdSuffix: string;
  control: keyof SetupForm['controls'];
  labelKey: string;
}[] = [
  { testIdSuffix: 'mon', control: 'everyMonday', labelKey: 'weekday.mon' },
  { testIdSuffix: 'tue', control: 'everyTuesday', labelKey: 'weekday.tue' },
  { testIdSuffix: 'wed', control: 'everyWednesday', labelKey: 'weekday.wed' },
  { testIdSuffix: 'thu', control: 'everyThursday', labelKey: 'weekday.thu' },
  { testIdSuffix: 'fri', control: 'everyFriday', labelKey: 'weekday.fri' },
  { testIdSuffix: 'sat', control: 'everySaturday', labelKey: 'weekday.sat' },
  { testIdSuffix: 'sun', control: 'everySunday', labelKey: 'weekday.sun' },
];

@Component({
  selector: 'af-planning-setup',
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
      <ng-container *transloco="let t; read: 'planning.setupWizard'">
        <af-page-header [title]="t('title')" />

        <af-page-error
          [message]="store.saveError()"
          [retryLabel]="null"
          data-testid="planning-setup-error"
        />

        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          data-testid="planning-setup-form"
          class="flex flex-col gap-6"
          novalidate
        >
          <!-- Section 1: Range -->
          <section class="flex flex-col gap-2" data-testid="planning-setup-section-range">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              {{ t('sections.range') }}
            </h2>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <af-form-field
                [label]="t('startDate')"
                for="SetupStart"
                [required]="true"
                [errors]="startDateErrors()"
              >
                <af-input
                  inputId="SetupStart"
                  type="date"
                  formControlName="startDate"
                  data-testid="planning-setup-start"
                />
              </af-form-field>
              <af-form-field
                [label]="t('endDate')"
                for="SetupEnd"
                [required]="true"
                [errors]="endDateErrors()"
              >
                <af-input
                  inputId="SetupEnd"
                  type="date"
                  formControlName="endDate"
                  data-testid="planning-setup-end"
                />
              </af-form-field>
            </div>
            <af-form-field
              [label]="t('location')"
              for="SetupLocation"
              [required]="true"
              [errors]="locationIdErrors()"
            >
              <af-select
                inputId="SetupLocation"
                formControlName="locationId"
                [placeholder]="t('selectLocation')"
                [options]="locationOptions()"
                data-testid="planning-setup-location-select"
              />
            </af-form-field>
          </section>

          <!-- Section 2: Weekdays -->
          <section class="flex flex-col gap-2" data-testid="planning-setup-section-weekdays">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              {{ t('sections.weekdays') }}
            </h2>
            <div class="flex flex-wrap gap-x-6 gap-y-2">
              @for (wd of weekdays; track wd.testIdSuffix) {
                <label
                  class="flex items-center gap-2 cursor-pointer select-none"
                  [attr.data-testid]="'planning-setup-weekday-' + wd.testIdSuffix"
                >
                  <input
                    type="checkbox"
                    [formControlName]="wd.control"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                  />
                  <span>{{ t(wd.labelKey) }}</span>
                </label>
              }
            </div>
          </section>

          <!-- Section 3: Notes -->
          <section class="flex flex-col gap-2" data-testid="planning-setup-section-notes">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              {{ t('sections.notes') }}
            </h2>
            <af-form-field [label]="t('remarks')" for="SetupRemarks">
              <af-input
                inputId="SetupRemarks"
                formControlName="info"
                autocomplete="off"
                data-testid="planning-setup-remarks"
              />
            </af-form-field>
          </section>

          <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
            <af-button
              htmlType="button"
              (clicked)="cancel()"
              data-testid="planning-setup-cancel-button"
            >
              {{ t('cancel') }}
            </af-button>
            <af-button
              type="primary"
              htmlType="submit"
              [disabled]="form.invalid || submitted()"
              data-testid="planning-setup-generate-button"
            >
              {{ t('generate') }}
            </af-button>
          </div>
        </form>
      </ng-container>
    </af-page>
  `,
})
export class PlanningSetupPage {
  protected readonly store = inject(PlanningStore);
  protected readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  protected readonly weekdays = WEEKDAYS;
  protected readonly submitted = signal(false);

  protected readonly form: SetupForm = this.fb.group({
    startDate: this.fb.nonNullable.control('', [Validators.required]),
    endDate: this.fb.nonNullable.control('', [Validators.required]),
    locationId: this.fb.nonNullable.control('', [Validators.required]),
    everyMonday: this.fb.nonNullable.control(false),
    everyTuesday: this.fb.nonNullable.control(false),
    everyWednesday: this.fb.nonNullable.control(false),
    everyThursday: this.fb.nonNullable.control(false),
    everyFriday: this.fb.nonNullable.control(false),
    everySaturday: this.fb.nonNullable.control(true),
    everySunday: this.fb.nonNullable.control(true),
    info: this.fb.nonNullable.control(''),
  });

  protected readonly startDateErrors = liveFieldErrors(this.form.controls.startDate);
  protected readonly endDateErrors = liveFieldErrors(this.form.controls.endDate);
  protected readonly locationIdErrors = liveFieldErrors(this.form.controls.locationId);

  protected readonly locationOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.locations().map((l) => ({ value: l.id, label: l.locationName })),
  );

  constructor() {
    this.store.loadDecorations();
    this.store.clearSaveError();

    effect(() => {
      const defaultLocation = this.store.locations()[0];
      if (defaultLocation && this.form.controls.locationId.value === '') {
        this.form.controls.locationId.setValue(defaultLocation.id, { emitEvent: false });
      }
    });

    const destroyRef = inject(DestroyRef);
    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.submitted()) return;
      if (evt.kind === 'planningDay.bulkCreated') {
        this.submitted.set(false);
        void this.router.navigateByUrl('/planning');
      }
    });
  }

  protected onSubmit(): void {
    if (this.form.invalid || this.submitted()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitted.set(true);
    this.store.bulkCreate(formToRuleRequest(this.form));
  }

  protected cancel(): void {
    void this.router.navigateByUrl('/planning');
  }
}

function formToRuleRequest(form: SetupForm): PlanningDayRuleRequest {
  const v = form.getRawValue();
  return withOptionals(
    {
      startDate: v.startDate,
      endDate: v.endDate,
      locationId: v.locationId,
      everyMonday: v.everyMonday,
      everyTuesday: v.everyTuesday,
      everyWednesday: v.everyWednesday,
      everyThursday: v.everyThursday,
      everyFriday: v.everyFriday,
      everySaturday: v.everySaturday,
      everySunday: v.everySunday,
    },
    { info: v.info },
  );
}
