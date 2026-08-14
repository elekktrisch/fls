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
  type FormControl,
  type FormGroup,
  ReactiveFormsModule,
  type ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import type {
  DeliveryCreationTestDetail,
  DeliveryCreationTestWriteRequest,
  DeliveryItemDetails,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { liveFieldErrors, withOptionals } from '@shared/util/form';

import { MUTATION_BUS } from '../../../../core/mutation-bus/mutation-bus';
import { SessionStore } from '../../../../core/session/session.store';
import { DeliveryCreationTestsStore } from '../delivery-creation-tests.store';
import { type DiffRow, deliveryItemDiff } from './delivery-item-diff';

type DctForm = FormGroup<{
  testName: FormControl<string>;
  flightId: FormControl<string>;
  description: FormControl<string>;
  active: FormControl<boolean>;
  mustNotCreateDeliveryForFlight: FormControl<boolean>;
  ignoreRecipientName: FormControl<boolean>;
  ignoreRecipientAddress: FormControl<boolean>;
  ignoreRecipientPersonId: FormControl<boolean>;
  ignoreRecipientClubMemberNumber: FormControl<boolean>;
  ignoreDeliveryInformation: FormControl<boolean>;
  ignoreAdditionalInformation: FormControl<boolean>;
  ignoreItemPositioning: FormControl<boolean>;
  ignoreItemText: FormControl<boolean>;
  ignoreItemAdditionalInformation: FormControl<boolean>;
}>;

interface IgnoreFlagSpec {
  readonly control: keyof DctForm['controls'];
  readonly testid: string;
  readonly labelKey: string;
}

const IGNORE_FLAG_SPECS: readonly IgnoreFlagSpec[] = [
  {
    control: 'ignoreRecipientName',
    testid: 'ignore-recipient-name',
    labelKey: 'edit.ignoreFlags.recipientName',
  },
  {
    control: 'ignoreRecipientAddress',
    testid: 'ignore-recipient-address',
    labelKey: 'edit.ignoreFlags.recipientAddress',
  },
  {
    control: 'ignoreRecipientPersonId',
    testid: 'ignore-recipient-person-id',
    labelKey: 'edit.ignoreFlags.recipientPersonId',
  },
  {
    control: 'ignoreRecipientClubMemberNumber',
    testid: 'ignore-recipient-club-member-number',
    labelKey: 'edit.ignoreFlags.recipientClubMemberNumber',
  },
  {
    control: 'ignoreDeliveryInformation',
    testid: 'ignore-delivery-information',
    labelKey: 'edit.ignoreFlags.deliveryInformation',
  },
  {
    control: 'ignoreAdditionalInformation',
    testid: 'ignore-additional-information',
    labelKey: 'edit.ignoreFlags.additionalInformation',
  },
  {
    control: 'ignoreItemPositioning',
    testid: 'ignore-item-positioning',
    labelKey: 'edit.ignoreFlags.itemPositioning',
  },
  { control: 'ignoreItemText', testid: 'ignore-item-text', labelKey: 'edit.ignoreFlags.itemText' },
  {
    control: 'ignoreItemAdditionalInformation',
    testid: 'ignore-item-additional-information',
    labelKey: 'edit.ignoreFlags.itemAdditionalInformation',
  },
];

@Component({
  selector: 'af-delivery-creation-tests-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    RouterLink,
    AfButtonComponent,
    AfInputComponent,
    AfFormFieldComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'deliveryCreationTests'">
        <af-page-header [title]="isCreate() ? t('edit.titleNew') : t('edit.title')" />

        <af-page-error [message]="store.saveError()" [retryLabel]="null" data-testid="dct-error" />

        @if (notFound()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border border-slate-200 bg-slate-50"
            data-testid="dct-not-found"
            role="status"
          >
            {{ t('edit.notFound') }}
          </div>
        } @else if (store.isLoadingDetail() && !isCreate()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
            data-testid="dct-loading"
            role="status"
            aria-live="polite"
          >
            {{ t('edit.loading') }}
          </div>
        } @else {
          <form
            [formGroup]="form"
            (ngSubmit)="onSubmit()"
            data-testid="dct-edit-form"
            class="flex flex-col gap-6"
            novalidate
          >
            <section class="flex flex-col gap-2">
              <af-form-field
                label="{{ t('edit.fields.name') }}"
                for="DctName"
                [required]="true"
                [errors]="nameErrors()"
              >
                <af-input
                  inputId="DctName"
                  formControlName="testName"
                  data-testid="dct-name"
                  autocomplete="off"
                />
              </af-form-field>

              <af-form-field
                label="{{ t('edit.fields.flight') }}"
                for="DctFlight"
                [required]="true"
                [errors]="flightErrors()"
              >
                <select
                  id="DctFlight"
                  formControlName="flightId"
                  class="w-full border border-slate-300 px-2 py-1.5 text-sm bg-white focus:border-brand-500 focus:outline-hidden"
                  data-testid="dct-flight-picker"
                >
                  <option value="">{{ t('edit.fields.flightPlaceholder') }}</option>
                  @for (f of flightOptions(); track f.id) {
                    <option [value]="f.id">{{ f.label }}</option>
                  }
                </select>
              </af-form-field>

              <af-form-field label="{{ t('edit.fields.description') }}" for="DctDescription">
                <af-input
                  inputId="DctDescription"
                  formControlName="description"
                  autocomplete="off"
                />
              </af-form-field>

              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="active"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                  data-testid="dct-active"
                />
                <span>{{ t('edit.fields.active') }}</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="mustNotCreateDeliveryForFlight"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                  data-testid="dct-must-not-create-delivery"
                />
                <span>{{ t('edit.fields.mustNotCreateDelivery') }}</span>
              </label>
            </section>

            <section class="flex flex-col gap-2">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('edit.sections.ignoreFlags') }}
              </h2>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-1">
                @for (flag of ignoreFlagSpecs; track flag.control) {
                  <label class="flex items-center gap-2 cursor-pointer select-none text-sm">
                    <input
                      type="checkbox"
                      [formControlName]="flag.control"
                      class="w-4 h-4 accent-brand-500 cursor-pointer"
                      [attr.data-testid]="'dct-' + flag.testid"
                    />
                    <span>{{ t(flag.labelKey) }}</span>
                  </label>
                }
              </div>
            </section>

            <!-- Dry-run: "Create test delivery" runs the engine WITHOUT persisting
                 and fills the expected DeliveryItem set (the saved expectation). -->
            <section class="flex flex-col gap-2" data-testid="dct-expected-section">
              <div class="flex items-center justify-between border-b border-slate-200 pb-1">
                <h2 class="text-xs font-medium text-slate-600 uppercase tracking-wide">
                  {{ t('edit.sections.expected') }}
                </h2>
                <af-button
                  htmlType="button"
                  [loading]="store.isExampleLoading()"
                  [disabled]="!flightControl().value"
                  (clicked)="createTestDelivery()"
                  data-testid="dct-create-test-delivery"
                >
                  {{ t('edit.createTestDelivery') }}
                </af-button>
              </div>
              @if (expectedItems().length === 0) {
                <p class="text-sm text-slate-500" data-testid="dct-expected-empty">
                  {{ t('edit.expectedEmpty') }}
                </p>
              } @else {
                <ul class="flex flex-col gap-1">
                  @for (item of expectedItems(); track $index) {
                    <li
                      class="flex flex-wrap gap-x-3 text-sm tabular border border-slate-200 px-2 py-1"
                      [attr.data-testid]="'dct-expected-item-' + $index"
                    >
                      <span class="font-medium text-slate-900">{{ item.articleNumber }}</span>
                      <span class="text-slate-600">{{ item.itemText }}</span>
                      <span class="text-slate-500">{{ item.quantity }} {{ item.unitType }}</span>
                    </li>
                  }
                </ul>
              }
            </section>

            <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
              <af-button
                htmlType="button"
                (clicked)="router.navigateByUrl('/deliverycreationtests')"
              >
                {{ t('edit.cancel') }}
              </af-button>
              @if (!isCreate() && canMutate()) {
                <af-button
                  htmlType="button"
                  [loading]="store.isRunning()"
                  (clicked)="runTest()"
                  data-testid="dct-run"
                >
                  {{ t('edit.run') }}
                </af-button>
              }
              @if (canMutate()) {
                <af-button
                  type="primary"
                  htmlType="submit"
                  [disabled]="formInvalid() || saveSubmitted()"
                  data-testid="dct-save-button"
                >
                  {{ t('edit.save') }}
                </af-button>
              }
            </div>
          </form>

          <!-- Run verdict + matched-rule links. The per-item cell diff (T-18)
               mounts into this area below the verdict on a Failure. -->
          @if (store.runResult(); as run) {
            <section
              class="mt-6 flex flex-col gap-2 border border-slate-200 p-4"
              data-testid="dct-result"
            >
              @if (run.lastTestSuccessful) {
                <p class="text-sm font-medium text-emerald-700">{{ t('edit.result.success') }}</p>
              } @else {
                <p class="text-sm font-medium text-red-700">{{ t('edit.result.failure') }}</p>
              }
              @if (run.lastTestResultMessage) {
                <pre
                  class="text-xs text-slate-600 whitespace-pre-wrap"
                  data-testid="dct-result-message"
                  >{{ run.lastTestResultMessage }}</pre
                >
              }
              <!-- Cell-level diff: on a Failure, the engine output differed from
                   the stored expectation — show which item fields diverged, with
                   the engine's (actual) value flagged red. The operator's daily
                   rule-tuning read. -->
              @if (!run.lastTestSuccessful && runDiff().length > 0) {
                <div class="flex flex-col gap-2" data-testid="dct-diff">
                  <span class="text-xs font-medium text-slate-600 uppercase tracking-wide">
                    {{ t('edit.diff.heading') }}
                  </span>
                  @for (row of runDiff(); track row.position) {
                    <div
                      class="flex flex-col gap-1 text-sm tabular border border-red-200 bg-red-50 px-2 py-1"
                      [attr.data-testid]="'dct-diff-item-' + row.position"
                    >
                      <span class="text-xs font-medium text-slate-600">
                        {{ t('edit.diff.position', { position: row.position + 1 }) }}
                      </span>
                      @for (cell of row.cells; track cell.field) {
                        <div class="flex flex-wrap items-baseline gap-x-2">
                          <span class="text-slate-500">{{ t(cell.labelKey) }}:</span>
                          <span class="text-slate-500 line-through">{{ cell.expected }}</span>
                          <span class="font-medium text-red-700">{{ cell.created }}</span>
                        </div>
                      }
                    </div>
                  }
                </div>
              }
              @if (run.lastTestMatchedFilterIds.length > 0) {
                <div class="flex flex-col gap-1">
                  <span class="text-xs font-medium text-slate-600 uppercase tracking-wide">
                    {{ t('edit.result.matchedRules') }}
                  </span>
                  @for (ruleId of run.lastTestMatchedFilterIds; track ruleId) {
                    <a
                      class="text-sm text-brand-600 no-underline hover:text-brand-700"
                      [routerLink]="['/accountingrules', ruleId, 'edit']"
                      [attr.data-testid]="'dct-matched-rule-' + ruleId"
                    >
                      {{ ruleId }}
                    </a>
                  }
                </div>
              }
            </section>
          }
        }
      </ng-container>
    </af-page>
  `,
})
export class DeliveryCreationTestsEditPage {
  protected readonly store = inject(DeliveryCreationTestsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly testId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.testId() === null);
  protected readonly canMutate = this.session.isClubAdmin;
  protected readonly flightOptions = this.store.flightOptions;
  protected readonly notFound = signal(false);
  protected readonly saveSubmitted = signal(false);
  protected readonly ignoreFlagSpecs = IGNORE_FLAG_SPECS;

  protected readonly form: DctForm = this.fb.nonNullable.group({
    testName: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(250)]),
    flightId: this.fb.nonNullable.control('', [Validators.required]),
    description: this.fb.nonNullable.control(''),
    active: this.fb.nonNullable.control(true),
    mustNotCreateDeliveryForFlight: this.fb.nonNullable.control(false),
    ignoreRecipientName: this.fb.nonNullable.control(false),
    ignoreRecipientAddress: this.fb.nonNullable.control(false),
    ignoreRecipientPersonId: this.fb.nonNullable.control(false),
    ignoreRecipientClubMemberNumber: this.fb.nonNullable.control(false),
    ignoreDeliveryInformation: this.fb.nonNullable.control(true),
    ignoreAdditionalInformation: this.fb.nonNullable.control(true),
    ignoreItemPositioning: this.fb.nonNullable.control(false),
    ignoreItemText: this.fb.nonNullable.control(false),
    ignoreItemAdditionalInformation: this.fb.nonNullable.control(false),
  });

  protected flightControl(): FormControl<string> {
    return this.form.controls.flightId;
  }

  protected readonly expectedItems = computed<DeliveryItemDetails[]>(() => {
    const example = this.store.exampleResult();
    if (example) return example.delivery.items ?? [];
    return this.store.selectedTest()?.expectedDelivery.items ?? [];
  });

  protected readonly runDiff = computed<DiffRow[]>(() => {
    const run = this.store.runResult();
    if (!run || run.lastTestSuccessful) return [];
    const created = run.lastTestCreatedDelivery.items ?? [];
    const expected = this.store.selectedTest()?.expectedDelivery.items ?? [];
    return deliveryItemDiff(expected, created);
  });

  private readonly formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });
  protected readonly formInvalid = computed(() => this.formStatus() !== 'VALID');

  private readonly nameServerError = signal<ValidationErrors | null>(null);
  protected readonly nameErrors = liveFieldErrors(this.form.controls.testName, {
    asyncErrors$: toObservable(this.nameServerError),
  });
  protected readonly flightErrors = liveFieldErrors(this.form.controls.flightId);

  constructor() {
    this.store.loadFlightOptions();

    effect(() => {
      const id = this.testId();
      this.notFound.set(false);
      if (!id) {
        this.store.select(null);
        return;
      }
      this.store.select(id);
      this.store.getDetail(id);
    });

    effect(() => {
      const detail = this.store.selectedTest();
      if (!detail) return;
      this.form.patchValue(detailToFormValue(detail));
      if (!this.canMutate()) {
        this.form.disable({ emitEvent: false });
      }
    });

    effect(() => {
      if (this.store.notFound() && !this.isCreate()) {
        this.notFound.set(true);
      }
    });

    const destroyRef = inject(DestroyRef);

    effect(() => {
      if (this.store.saveError()) this.saveSubmitted.set(false);
    });

    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (
        evt.kind === 'delivery-creation-test.created' ||
        evt.kind === 'delivery-creation-test.updated'
      ) {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/deliverycreationtests');
      }
    });
  }

  protected createTestDelivery(): void {
    const flightId = this.flightControl().value;
    if (!flightId) return;
    this.store.exampleForFlight(flightId);
  }

  protected runTest(): void {
    const id = this.testId();
    if (id !== null) this.store.run(id);
  }

  protected onSubmit(): void {
    if (!this.canMutate()) return;
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saveSubmitted.set(true);
    const req = this.formToRequest();
    const id = this.testId();
    if (id === null) {
      this.store.create(req);
    } else {
      this.store.update({ id, req });
    }
  }

  private formToRequest(): DeliveryCreationTestWriteRequest {
    const v = this.form.getRawValue();
    const base = {
      flightId: v.flightId,
      testName: v.testName.trim(),
      active: v.active,
      mustNotCreateDeliveryForFlight: v.mustNotCreateDeliveryForFlight,
      ignoreRecipientName: v.ignoreRecipientName,
      ignoreRecipientAddress: v.ignoreRecipientAddress,
      ignoreRecipientPersonId: v.ignoreRecipientPersonId,
      ignoreRecipientClubMemberNumber: v.ignoreRecipientClubMemberNumber,
      ignoreDeliveryInformation: v.ignoreDeliveryInformation,
      ignoreAdditionalInformation: v.ignoreAdditionalInformation,
      ignoreItemPositioning: v.ignoreItemPositioning,
      ignoreItemText: v.ignoreItemText,
      ignoreItemAdditionalInformation: v.ignoreItemAdditionalInformation,
    };
    return withOptionals(base, {
      description: v.description.trim(),
    }) as DeliveryCreationTestWriteRequest;
  }
}

function detailToFormValue(
  d: DeliveryCreationTestDetail,
): Partial<ReturnType<DctForm['getRawValue']>> {
  return {
    testName: d.testName,
    flightId: d.flightId,
    description: d.description ?? '',
    active: d.active,
    mustNotCreateDeliveryForFlight: d.mustNotCreateDeliveryForFlight,
    ignoreRecipientName: d.ignoreRecipientName,
    ignoreRecipientAddress: d.ignoreRecipientAddress,
    ignoreRecipientPersonId: d.ignoreRecipientPersonId,
    ignoreRecipientClubMemberNumber: d.ignoreRecipientClubMemberNumber,
    ignoreDeliveryInformation: d.ignoreDeliveryInformation,
    ignoreAdditionalInformation: d.ignoreAdditionalInformation,
    ignoreItemPositioning: d.ignoreItemPositioning,
    ignoreItemText: d.ignoreItemText,
    ignoreItemAdditionalInformation: d.ignoreItemAdditionalInformation,
  };
}
