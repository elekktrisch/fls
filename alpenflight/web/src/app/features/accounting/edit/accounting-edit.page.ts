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
  type ValidationErrors,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import type {
  AccountingRuleFilterDetail,
  AccountingRuleFilterWriteRequest,
  FilterConfig,
  MatchList,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFieldErrorsComponent } from '@ui/molecules/af-field-errors';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { liveFieldErrors, withOptionals } from '@shared/util/form';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { SessionStore } from '../../../core/session/session.store';
import { AccountingStore } from '../accounting.store';
import { MatchListControlComponent } from './match-list-control.component';

const LEGACY_DO_NOT_INVOICE = 5;
const LEGACY_RECIPIENT = 10;
const LEGACY_NO_LANDING_TAX = 20;
const LEGACY_AIRCRAFT_FILTER = 30;

const LEGACY_UNLIMITED_DURATION_SECONDS = 2147483647;

type AccountingForm = FormGroup<{
  filterTypeLegacyId: FormControl<string>;
  ruleFilterName: FormControl<string>;
  description: FormControl<string>;
  active: FormControl<boolean>;
  stopRuleEngineWhenApplied: FormControl<boolean>;
  isRuleForGliderFlights: FormControl<boolean>;
  isRuleForTowingFlights: FormControl<boolean>;
  isRuleForMotorFlights: FormControl<boolean>;
  articleNumber: FormControl<string>;
  deliveryLineText: FormControl<string>;
  accountingUnitTypeId: FormControl<string>;
  recipientMemberNumber: FormControl<string>;
  recipientName: FormControl<string>;
  chargedToClubInternal: FormControl<boolean>;
  flightDurationUnlimited: FormControl<boolean>;
  minFlightTimeInSeconds: FormControl<number | null>;
  maxFlightTimeInSeconds: FormControl<number | null>;
  includeThresholdText: FormControl<boolean>;
  thresholdText: FormControl<string>;
  includeFlightTypeName: FormControl<boolean>;
  noLandingTaxForGlider: FormControl<boolean>;
  noLandingTaxForTowingAircraft: FormControl<boolean>;
  noLandingTaxForAircraft: FormControl<boolean>;
  matchAircraftImmatriculations: FormControl<MatchList>;
  matchStartTypes: FormControl<MatchList>;
  matchFlightTypeCodes: FormControl<MatchList>;
  matchStartLocations: FormControl<MatchList>;
  matchLdgLocations: FormControl<MatchList>;
  matchClubMemberNumbers: FormControl<MatchList>;
  matchFlightCrewTypes: FormControl<MatchList>;
  matchAircraftHomebases: FormControl<MatchList>;
  matchMemberStates: FormControl<MatchList>;
  extendMatchingFlightTypeCodesToGliderAndTowFlight: FormControl<boolean>;
}>;

interface MatchListSpec {
  readonly key: string;
  readonly control: keyof MatchListControlMap;
  readonly field: keyof MatchListMap;
  readonly labelKey: string;
  readonly optionSource?:
    | 'aircraftImmatriculations'
    | 'flightTypeCodes'
    | 'locations'
    | 'clubMemberNumbers'
    | 'flightCrewTypes';
}

type MatchListControlMap = Pick<
  ReturnType<AccountingForm['getRawValue']>,
  | 'matchAircraftImmatriculations'
  | 'matchStartTypes'
  | 'matchFlightTypeCodes'
  | 'matchStartLocations'
  | 'matchLdgLocations'
  | 'matchClubMemberNumbers'
  | 'matchFlightCrewTypes'
  | 'matchAircraftHomebases'
  | 'matchMemberStates'
>;

const MATCH_LIST_SPECS: readonly MatchListSpec[] = [
  {
    key: 'immatriculations',
    control: 'matchAircraftImmatriculations',
    field: 'aircraftImmatriculations',
    labelKey: 'edit.matchLists.immatriculations',
    optionSource: 'aircraftImmatriculations',
  },
  {
    key: 'start-types',
    control: 'matchStartTypes',
    field: 'startTypes',
    labelKey: 'edit.matchLists.startTypes',
  },
  {
    key: 'flight-type-codes',
    control: 'matchFlightTypeCodes',
    field: 'flightTypeCodes',
    labelKey: 'edit.matchLists.flightTypeCodes',
    optionSource: 'flightTypeCodes',
  },
  {
    key: 'start-locations',
    control: 'matchStartLocations',
    field: 'startLocations',
    labelKey: 'edit.matchLists.startLocations',
    optionSource: 'locations',
  },
  {
    key: 'landing-locations',
    control: 'matchLdgLocations',
    field: 'ldgLocations',
    labelKey: 'edit.matchLists.landingLocations',
    optionSource: 'locations',
  },
  {
    key: 'club-member-numbers',
    control: 'matchClubMemberNumbers',
    field: 'clubMemberNumbers',
    labelKey: 'edit.matchLists.clubMemberNumbers',
    optionSource: 'clubMemberNumbers',
  },
  {
    key: 'flight-crew-types',
    control: 'matchFlightCrewTypes',
    field: 'flightCrewTypes',
    labelKey: 'edit.matchLists.flightCrewTypes',
    optionSource: 'flightCrewTypes',
  },
  {
    key: 'aircraft-homebases',
    control: 'matchAircraftHomebases',
    field: 'aircraftHomebases',
    labelKey: 'edit.matchLists.aircraftHomebases',
    optionSource: 'locations',
  },
  {
    key: 'member-states',
    control: 'matchMemberStates',
    field: 'memberStates',
    labelKey: 'edit.matchLists.memberStates',
  },
];

@Component({
  selector: 'af-accounting-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfButtonComponent,
    AfInputComponent,
    AfFieldErrorsComponent,
    AfFormFieldComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
    MatchListControlComponent,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'accounting'">
        <af-page-header [title]="isCreate() ? t('edit.titleNew') : t('edit.title')" />

        <af-page-error
          [message]="store.saveError()"
          [retryLabel]="null"
          data-testid="accounting-rules-save-error"
        />

        @if (notFound()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-600 border border-slate-200 bg-slate-50"
            data-testid="accounting-rules-not-found"
            role="status"
          >
            {{ t('edit.notFound') }}
          </div>
        } @else if (store.isLoadingDetail() && !isCreate()) {
          <div
            class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
            data-testid="accounting-rules-loading"
            role="status"
            aria-live="polite"
          >
            {{ t('edit.loading') }}
          </div>
        } @else {
          <form
            [formGroup]="form"
            (ngSubmit)="onSubmit()"
            data-testid="accounting-rules-edit-form"
            class="flex flex-col gap-6"
            novalidate
          >
            <section class="flex flex-col gap-2" data-testid="accounting-rules-section-core">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('edit.sections.core') }}
              </h2>
              <af-form-field
                label="{{ t('edit.fields.filterType') }}"
                for="AccountingRuleFilterTypeId"
                [required]="true"
                [errors]="filterTypeErrors()"
              >
                <select
                  id="AccountingRuleFilterTypeId"
                  formControlName="filterTypeLegacyId"
                  class="w-full border border-slate-300 px-2 py-1.5 text-sm bg-white focus:border-brand-500 focus:outline-hidden"
                  data-testid="accounting-rules-filter-type"
                >
                  <option value="">{{ t('edit.fields.filterTypePlaceholder') }}</option>
                  @for (ty of filterTypeOptions(); track ty.legacyId) {
                    <option [value]="ty.legacyId">{{ ty.name }}</option>
                  }
                </select>
              </af-form-field>

              <af-form-field
                label="{{ t('edit.fields.name') }}"
                for="RuleFilterName"
                [required]="true"
              >
                <af-input
                  inputId="RuleFilterName"
                  formControlName="ruleFilterName"
                  autocomplete="off"
                />
                @if (nameErrors(); as errs) {
                  <span data-testid="accounting-rules-name-error" class="block">
                    <af-field-errors [errors]="errs" />
                  </span>
                }
              </af-form-field>

              <af-form-field label="{{ t('edit.fields.description') }}" for="Description">
                <af-input inputId="Description" formControlName="description" autocomplete="off" />
              </af-form-field>

              <div class="flex flex-col gap-1 mt-1">
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="active"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-flag-active"
                  />
                  <span>{{ t('edit.fields.active') }}</span>
                </label>
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="stopRuleEngineWhenApplied"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-flag-stop-rule-engine"
                  />
                  <span>{{ t('edit.fields.stopRuleEngine') }}</span>
                </label>
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="isRuleForGliderFlights"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-flag-glider"
                  />
                  <span>{{ t('edit.fields.forGlider') }}</span>
                </label>
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="isRuleForTowingFlights"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-flag-towing"
                  />
                  <span>{{ t('edit.fields.forTowing') }}</span>
                </label>
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="isRuleForMotorFlights"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-flag-motor"
                  />
                  <span>{{ t('edit.fields.forMotor') }}</span>
                </label>
              </div>
            </section>

            @if (showArticleTarget()) {
              <section
                class="flex flex-col gap-2"
                data-testid="accounting-rules-section-article-target"
              >
                <h2
                  class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
                >
                  {{ t('edit.sections.articleTarget') }}
                </h2>
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  <af-form-field label="{{ t('edit.fields.articleNumber') }}" for="ArticleNumber">
                    <af-input
                      inputId="ArticleNumber"
                      formControlName="articleNumber"
                      autocomplete="off"
                    />
                  </af-form-field>
                  <af-form-field
                    label="{{ t('edit.fields.deliveryLineText') }}"
                    for="DeliveryLineText"
                  >
                    <af-input
                      inputId="DeliveryLineText"
                      formControlName="deliveryLineText"
                      autocomplete="off"
                    />
                  </af-form-field>
                </div>
                <af-form-field
                  label="{{ t('edit.fields.accountingUnitType') }}"
                  for="AccountingUnitTypeId"
                >
                  <select
                    id="AccountingUnitTypeId"
                    formControlName="accountingUnitTypeId"
                    class="w-full border border-slate-300 px-2 py-1.5 text-sm bg-white focus:border-brand-500 focus:outline-hidden"
                    data-testid="accounting-rules-accounting-unit-type"
                  >
                    <option value="">{{ t('edit.fields.accountingUnitTypePlaceholder') }}</option>
                    @for (u of unitTypeOptions(); track u.id) {
                      <option [value]="u.id">{{ u.name }}</option>
                    }
                  </select>
                </af-form-field>
              </section>
            }

            @if (showRecipientTarget()) {
              <section
                class="flex flex-col gap-2"
                data-testid="accounting-rules-section-recipient-target"
              >
                <h2
                  class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
                >
                  {{ t('edit.sections.recipientTarget') }}
                </h2>
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  <af-form-field
                    label="{{ t('edit.fields.recipientMemberNumber') }}"
                    for="RecipientMemberNumber"
                  >
                    <af-input
                      inputId="RecipientMemberNumber"
                      formControlName="recipientMemberNumber"
                      autocomplete="off"
                    />
                  </af-form-field>
                  <af-form-field label="{{ t('edit.fields.recipientName') }}" for="RecipientName">
                    <af-input
                      inputId="RecipientName"
                      formControlName="recipientName"
                      autocomplete="off"
                    />
                  </af-form-field>
                </div>
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="chargedToClubInternal"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-flag-charged-club-internal"
                  />
                  <span>{{ t('edit.fields.chargedToClubInternal') }}</span>
                </label>
              </section>
            }

            @if (showAircraftFilter()) {
              <section
                class="flex flex-col gap-2"
                data-testid="accounting-rules-section-aircraft-filter"
              >
                <h2
                  class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
                >
                  {{ t('edit.sections.aircraftFilter') }}
                </h2>
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="flightDurationUnlimited"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-flight-duration-unlimited"
                  />
                  <span>{{ t('edit.fields.flightDurationUnlimited') }}</span>
                </label>
                @if (!flightDurationUnlimitedValue()) {
                  <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
                    <af-form-field
                      label="{{ t('edit.fields.minFlightDuration') }}"
                      for="MinFlightTime"
                    >
                      <af-input
                        inputId="MinFlightTime"
                        type="number"
                        formControlName="minFlightTimeInSeconds"
                        autocomplete="off"
                      />
                    </af-form-field>
                    <af-form-field
                      label="{{ t('edit.fields.maxFlightDuration') }}"
                      for="MaxFlightTime"
                    >
                      <af-input
                        inputId="MaxFlightTime"
                        type="number"
                        formControlName="maxFlightTimeInSeconds"
                        autocomplete="off"
                      />
                    </af-form-field>
                  </div>
                }
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="includeThresholdText"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-include-threshold-text"
                  />
                  <span>{{ t('edit.fields.includeThresholdText') }}</span>
                </label>
                @if (includeThresholdTextValue()) {
                  <af-form-field label="{{ t('edit.fields.thresholdText') }}" for="ThresholdText">
                    <af-input
                      inputId="ThresholdText"
                      formControlName="thresholdText"
                      autocomplete="off"
                    />
                  </af-form-field>
                }
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    formControlName="includeFlightTypeName"
                    class="w-4 h-4 accent-brand-500 cursor-pointer"
                    data-testid="accounting-rules-include-flight-type-name"
                  />
                  <span>{{ t('edit.fields.includeFlightTypeName') }}</span>
                </label>
              </section>
            }

            @if (showNoLandingTax()) {
              <section
                class="flex flex-col gap-2"
                data-testid="accounting-rules-section-no-landing-tax"
              >
                <h2
                  class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
                >
                  {{ t('edit.sections.noLandingTax') }}
                </h2>
                <div class="flex flex-col gap-1">
                  <label class="flex items-center gap-2 cursor-pointer select-none">
                    <input
                      type="checkbox"
                      formControlName="noLandingTaxForGlider"
                      class="w-4 h-4 accent-brand-500 cursor-pointer"
                      data-testid="accounting-rules-no-landing-tax-glider"
                    />
                    <span>{{ t('edit.fields.noLandingTaxForGlider') }}</span>
                  </label>
                  <label class="flex items-center gap-2 cursor-pointer select-none">
                    <input
                      type="checkbox"
                      formControlName="noLandingTaxForTowingAircraft"
                      class="w-4 h-4 accent-brand-500 cursor-pointer"
                      data-testid="accounting-rules-no-landing-tax-towing"
                    />
                    <span>{{ t('edit.fields.noLandingTaxForTowing') }}</span>
                  </label>
                  <label class="flex items-center gap-2 cursor-pointer select-none">
                    <input
                      type="checkbox"
                      formControlName="noLandingTaxForAircraft"
                      class="w-4 h-4 accent-brand-500 cursor-pointer"
                      data-testid="accounting-rules-no-landing-tax-aircraft"
                    />
                    <span>{{ t('edit.fields.noLandingTaxForAircraft') }}</span>
                  </label>
                </div>
              </section>
            }

            <section class="flex flex-col gap-3" data-testid="accounting-rules-section-match-lists">
              <h2
                class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
              >
                {{ t('edit.sections.matchLists') }}
              </h2>
              <label class="flex items-center gap-2 cursor-pointer select-none text-sm">
                <input
                  type="checkbox"
                  formControlName="extendMatchingFlightTypeCodesToGliderAndTowFlight"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                  data-testid="accounting-rules-extend-flight-type-codes"
                />
                <span>{{ t('edit.matchLists.extendFlightTypeCodes') }}</span>
              </label>
              <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
                @for (spec of matchListSpecs; track spec.key) {
                  <af-match-list-control
                    [listKey]="spec.key"
                    [label]="t(spec.labelKey)"
                    [options]="optionsFor(spec)"
                    [useAllExceptLabel]="t('edit.matchLists.useAllExcept')"
                    [addPlaceholder]="t('edit.matchLists.addPlaceholder')"
                    [emptyLabel]="t('edit.matchLists.empty')"
                    [removeLabel]="t('edit.matchLists.remove')"
                    [formControlName]="spec.control"
                  />
                }
              </div>
            </section>

            <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
              <af-button htmlType="button" (clicked)="router.navigateByUrl('/accountingrules')">
                {{ t('edit.cancel') }}
              </af-button>
              @if (canMutate()) {
                <af-button
                  type="primary"
                  htmlType="submit"
                  [disabled]="formInvalid() || saveSubmitted()"
                  data-testid="accounting-rules-save-button"
                >
                  {{ t('edit.save') }}
                </af-button>
              }
            </div>
          </form>
        }
      </ng-container>
    </af-page>
  `,
})
export class AccountingEditPage {
  protected readonly store = inject(AccountingStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly filterId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.filterId() === null);
  protected readonly canMutate = this.session.isClubAdmin;

  protected readonly filterTypeOptions = computed(() =>
    [...this.store.filterTypes()].sort((a, b) => a.legacyId - b.legacyId),
  );
  protected readonly unitTypeOptions = computed(() => this.store.accountingUnitTypes());

  private readonly matchListOptions = this.store.matchListOptions;
  protected optionsFor(spec: MatchListSpec): readonly string[] {
    const source = spec.optionSource;
    return source ? this.matchListOptions()[source] : [];
  }

  protected readonly form: AccountingForm = this.fb.nonNullable.group({
    filterTypeLegacyId: this.fb.nonNullable.control('', [Validators.required]),
    ruleFilterName: this.fb.nonNullable.control('', [
      Validators.required,
      Validators.maxLength(250),
    ]),
    description: this.fb.nonNullable.control(''),
    active: this.fb.nonNullable.control(true),
    stopRuleEngineWhenApplied: this.fb.nonNullable.control(false),
    isRuleForGliderFlights: this.fb.nonNullable.control(false),
    isRuleForTowingFlights: this.fb.nonNullable.control(false),
    isRuleForMotorFlights: this.fb.nonNullable.control(false),
    articleNumber: this.fb.nonNullable.control('', [Validators.maxLength(50)]),
    deliveryLineText: this.fb.nonNullable.control(''),
    accountingUnitTypeId: this.fb.nonNullable.control(''),
    recipientMemberNumber: this.fb.nonNullable.control('', [Validators.maxLength(50)]),
    recipientName: this.fb.nonNullable.control(''),
    chargedToClubInternal: this.fb.nonNullable.control(false),
    flightDurationUnlimited: this.fb.nonNullable.control(true),
    minFlightTimeInSeconds: this.fb.control<number | null>(null, [Validators.min(0)]),
    maxFlightTimeInSeconds: this.fb.control<number | null>(null, [Validators.min(0)]),
    includeThresholdText: this.fb.nonNullable.control(false),
    thresholdText: this.fb.nonNullable.control(''),
    includeFlightTypeName: this.fb.nonNullable.control(false),
    noLandingTaxForGlider: this.fb.nonNullable.control(false),
    noLandingTaxForTowingAircraft: this.fb.nonNullable.control(false),
    noLandingTaxForAircraft: this.fb.nonNullable.control(false),
    matchAircraftImmatriculations: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchStartTypes: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchFlightTypeCodes: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchStartLocations: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchLdgLocations: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchClubMemberNumbers: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchFlightCrewTypes: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchAircraftHomebases: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    matchMemberStates: this.fb.nonNullable.control<MatchList>(emptyMatchList()),
    extendMatchingFlightTypeCodesToGliderAndTowFlight: this.fb.nonNullable.control(false),
  });

  protected readonly matchListSpecs = MATCH_LIST_SPECS;

  protected readonly saveSubmitted = signal(false);
  protected readonly notFound = signal(false);

  private readonly loadedMatchLists = signal<MatchListSlice>(defaultMatchListSlice());

  private readonly formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });
  protected readonly formInvalid = computed(() => this.formStatus() !== 'VALID');

  private readonly nameServerError = signal<ValidationErrors | null>(null);

  protected readonly nameErrors = liveFieldErrors(this.form.controls.ruleFilterName, {
    asyncErrors$: toObservable(this.nameServerError),
  });
  protected readonly filterTypeErrors = liveFieldErrors(this.form.controls.filterTypeLegacyId);

  private readonly legacyIdValue = toSignal(this.form.controls.filterTypeLegacyId.valueChanges, {
    initialValue: '',
  });
  private readonly selectedLegacyId = computed(() => {
    const raw = this.legacyIdValue();
    return raw === '' ? null : Number(raw);
  });
  protected readonly showArticleTarget = computed(() => {
    const id = this.selectedLegacyId();
    return id !== null && id !== LEGACY_DO_NOT_INVOICE && id !== LEGACY_RECIPIENT;
  });
  protected readonly showRecipientTarget = computed(
    () => this.selectedLegacyId() === LEGACY_RECIPIENT,
  );
  protected readonly showAircraftFilter = computed(
    () => this.selectedLegacyId() === LEGACY_AIRCRAFT_FILTER,
  );
  protected readonly showNoLandingTax = computed(
    () => this.selectedLegacyId() === LEGACY_NO_LANDING_TAX,
  );

  protected readonly flightDurationUnlimitedValue = toSignal(
    this.form.controls.flightDurationUnlimited.valueChanges,
    { initialValue: true },
  );
  protected readonly includeThresholdTextValue = toSignal(
    this.form.controls.includeThresholdText.valueChanges,
    { initialValue: false },
  );

  constructor() {
    this.store.loadFilterTypes();
    this.store.loadUnitTypes();
    this.store.loadMatchListReferences();

    effect(() => {
      const id = this.filterId();
      this.notFound.set(false);
      if (!id) {
        this.store.select(null);
        return;
      }
      this.store.select(id);
      this.store.getDetail(id);
    });

    effect(() => {
      const detail = this.store.selectedFilter();
      if (!detail) return;
      const legacyId = this.store
        .filterTypes()
        .find((ty) => ty.id === detail.filterTypeId)?.legacyId;
      const slice = extractMatchListSlice(detail.filterConfig);
      this.loadedMatchLists.set(slice);
      this.form.patchValue({
        ...detailToFormValue(detail, legacyId ?? null),
        ...matchListSliceToFormValue(slice),
      });
      if (!this.canMutate()) {
        this.form.disable({ emitEvent: false });
      }
    });

    effect(() => {
      if (!this.store.saveError()) return;
      if (this.store.saveErrorKind() === 'not-found' && !this.isCreate()) {
        this.notFound.set(true);
      }
    });

    const destroyRef = inject(DestroyRef);

    effect(() => {
      const err = this.store.saveError();
      if (!err) return;
      this.saveSubmitted.set(false);
      if (this.store.saveErrorKind() === 'conflict') {
        this.nameServerError.set({ duplicate: true });
        this.form.controls.ruleFilterName.markAsTouched();
      }
    });

    this.form.controls.ruleFilterName.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe(() => {
        if (this.nameServerError() !== null) this.nameServerError.set(null);
        if (this.store.saveErrorKind() === 'conflict') this.store.clearSaveError();
      });

    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (
        evt.kind === 'accounting-rule-filter.created' ||
        evt.kind === 'accounting-rule-filter.updated'
      ) {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/accountingrules');
      }
    });
  }

  protected onSubmit(): void {
    if (!this.canMutate()) return;
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    const filterTypeId = this.selectedFilterTypeId();
    if (!filterTypeId) {
      this.form.controls.filterTypeLegacyId.markAsTouched();
      return;
    }
    this.saveSubmitted.set(true);
    const req = this.formToRequest(filterTypeId);
    const id = this.filterId();
    if (id === null) {
      this.store.create(req);
    } else {
      this.store.update({ id, req });
    }
  }

  private selectedFilterTypeId(): string | null {
    const legacyId = this.selectedLegacyId();
    if (legacyId === null) return null;
    return this.store.filterTypes().find((ty) => ty.legacyId === legacyId)?.id ?? null;
  }

  private formToRequest(filterTypeId: string): AccountingRuleFilterWriteRequest {
    const v = this.form.getRawValue();
    const legacyId = Number(v.filterTypeLegacyId);
    const isRecipient = legacyId === LEGACY_RECIPIENT;
    const isArticle = legacyId !== LEGACY_DO_NOT_INVOICE && !isRecipient;
    const isAircraftFilter = legacyId === LEGACY_AIRCRAFT_FILTER;
    const isNoLandingTax = legacyId === LEGACY_NO_LANDING_TAX;

    const filterConfig: FilterConfig = {
      isRuleForGliderFlights: v.isRuleForGliderFlights,
      isRuleForTowingFlights: v.isRuleForTowingFlights,
      isRuleForMotorFlights: v.isRuleForMotorFlights,
      noLandingTaxForGlider: isNoLandingTax && v.noLandingTaxForGlider,
      noLandingTaxForTowingAircraft: isNoLandingTax && v.noLandingTaxForTowingAircraft,
      noLandingTaxForAircraft: isNoLandingTax && v.noLandingTaxForAircraft,
      includeFlightTypeName: isAircraftFilter && v.includeFlightTypeName,
      extendMatchingFlightTypeCodesToGliderAndTowFlight:
        v.extendMatchingFlightTypeCodesToGliderAndTowFlight,
      includeThresholdText: isAircraftFilter && v.includeThresholdText,
      ...formValueToMatchListMap(v),
      personCategories: this.loadedMatchLists().lists.personCategories,
    };

    if (isAircraftFilter && v.includeThresholdText && v.thresholdText.trim() !== '') {
      filterConfig.thresholdText = v.thresholdText.trim();
    }
    if (isAircraftFilter && !v.flightDurationUnlimited) {
      filterConfig.minFlightTimeInSecondsMatchingValue = v.minFlightTimeInSeconds ?? 0;
      filterConfig.maxFlightTimeInSecondsMatchingValue =
        v.maxFlightTimeInSeconds ?? LEGACY_UNLIMITED_DURATION_SECONDS;
    }
    if (isArticle && v.deliveryLineText.trim() !== '') {
      filterConfig.deliveryLineText = v.deliveryLineText.trim();
    }
    if (isRecipient && v.recipientName.trim() !== '') {
      filterConfig.recipientName = v.recipientName.trim();
    }

    const base = {
      filterTypeId,
      filterTypeLegacyId: legacyId,
      ruleFilterName: v.ruleFilterName.trim(),
      active: v.active,
      stopRuleEngineWhenApplied: v.stopRuleEngineWhenApplied,
      filterConfig,
    };

    return withOptionals(base, {
      description: v.description,
      accountingUnitTypeId: isArticle ? v.accountingUnitTypeId : '',
      articleNumber: isArticle ? v.articleNumber.trim() : '',
      recipientMemberNumber: isRecipient ? v.recipientMemberNumber.trim() : '',
      chargedToClubInternal: isRecipient ? v.chargedToClubInternal : undefined,
    }) as AccountingRuleFilterWriteRequest;
  }
}

type MatchListMap = Record<
  | 'aircraftImmatriculations'
  | 'startTypes'
  | 'flightTypeCodes'
  | 'startLocations'
  | 'ldgLocations'
  | 'clubMemberNumbers'
  | 'flightCrewTypes'
  | 'aircraftHomebases'
  | 'memberStates'
  | 'personCategories',
  MatchList
>;

interface MatchListSlice {
  extendMatchingFlightTypeCodesToGliderAndTowFlight: boolean;
  lists: MatchListMap;
}

function emptyMatchList(): MatchList {
  return { useAllExcept: true, matched: [] };
}

function defaultMatchListSlice(): MatchListSlice {
  return {
    extendMatchingFlightTypeCodesToGliderAndTowFlight: false,
    lists: {
      aircraftImmatriculations: emptyMatchList(),
      startTypes: emptyMatchList(),
      flightTypeCodes: emptyMatchList(),
      startLocations: emptyMatchList(),
      ldgLocations: emptyMatchList(),
      clubMemberNumbers: emptyMatchList(),
      flightCrewTypes: emptyMatchList(),
      aircraftHomebases: emptyMatchList(),
      memberStates: emptyMatchList(),
      personCategories: emptyMatchList(),
    },
  };
}

function extractMatchListSlice(config: FilterConfig): MatchListSlice {
  const def = defaultMatchListSlice();
  return {
    extendMatchingFlightTypeCodesToGliderAndTowFlight:
      config.extendMatchingFlightTypeCodesToGliderAndTowFlight ?? false,
    lists: {
      aircraftImmatriculations:
        config.aircraftImmatriculations ?? def.lists.aircraftImmatriculations,
      startTypes: config.startTypes ?? def.lists.startTypes,
      flightTypeCodes: config.flightTypeCodes ?? def.lists.flightTypeCodes,
      startLocations: config.startLocations ?? def.lists.startLocations,
      ldgLocations: config.ldgLocations ?? def.lists.ldgLocations,
      clubMemberNumbers: config.clubMemberNumbers ?? def.lists.clubMemberNumbers,
      flightCrewTypes: config.flightCrewTypes ?? def.lists.flightCrewTypes,
      aircraftHomebases: config.aircraftHomebases ?? def.lists.aircraftHomebases,
      memberStates: config.memberStates ?? def.lists.memberStates,
      personCategories: config.personCategories ?? def.lists.personCategories,
    },
  };
}

function matchListSliceToFormValue(
  slice: MatchListSlice,
): Partial<ReturnType<AccountingForm['getRawValue']>> {
  const l = slice.lists;
  return {
    matchAircraftImmatriculations: l.aircraftImmatriculations,
    matchStartTypes: l.startTypes,
    matchFlightTypeCodes: l.flightTypeCodes,
    matchStartLocations: l.startLocations,
    matchLdgLocations: l.ldgLocations,
    matchClubMemberNumbers: l.clubMemberNumbers,
    matchFlightCrewTypes: l.flightCrewTypes,
    matchAircraftHomebases: l.aircraftHomebases,
    matchMemberStates: l.memberStates,
    extendMatchingFlightTypeCodesToGliderAndTowFlight:
      slice.extendMatchingFlightTypeCodesToGliderAndTowFlight,
  };
}

function formValueToMatchListMap(
  v: ReturnType<AccountingForm['getRawValue']>,
): Omit<MatchListMap, 'personCategories'> {
  return {
    aircraftImmatriculations: v.matchAircraftImmatriculations,
    startTypes: v.matchStartTypes,
    flightTypeCodes: v.matchFlightTypeCodes,
    startLocations: v.matchStartLocations,
    ldgLocations: v.matchLdgLocations,
    clubMemberNumbers: v.matchClubMemberNumbers,
    flightCrewTypes: v.matchFlightCrewTypes,
    aircraftHomebases: v.matchAircraftHomebases,
    memberStates: v.matchMemberStates,
  };
}

function detailToFormValue(
  d: AccountingRuleFilterDetail,
  legacyId: number | null,
): Partial<ReturnType<AccountingForm['getRawValue']>> {
  const c = d.filterConfig;
  const min = c.minFlightTimeInSecondsMatchingValue;
  const max = c.maxFlightTimeInSecondsMatchingValue;
  const unlimited = !(
    (min ?? 0) > 0 || (max ?? LEGACY_UNLIMITED_DURATION_SECONDS) < LEGACY_UNLIMITED_DURATION_SECONDS
  );
  return {
    filterTypeLegacyId: legacyId === null ? '' : String(legacyId),
    ruleFilterName: d.ruleFilterName,
    description: d.description ?? '',
    active: d.active,
    stopRuleEngineWhenApplied: d.stopRuleEngineWhenApplied,
    isRuleForGliderFlights: c.isRuleForGliderFlights ?? false,
    isRuleForTowingFlights: c.isRuleForTowingFlights ?? false,
    isRuleForMotorFlights: c.isRuleForMotorFlights ?? false,
    articleNumber: d.articleTarget ?? '',
    deliveryLineText: c.deliveryLineText ?? '',
    accountingUnitTypeId: d.accountingUnitTypeId ?? '',
    recipientMemberNumber: d.recipientTarget ?? '',
    recipientName: c.recipientName ?? '',
    chargedToClubInternal: d.chargedToClubInternal,
    flightDurationUnlimited: unlimited,
    minFlightTimeInSeconds: unlimited ? null : (min ?? null),
    maxFlightTimeInSeconds: unlimited ? null : (max ?? null),
    includeThresholdText: c.includeThresholdText ?? !!c.thresholdText,
    thresholdText: c.thresholdText ?? '',
    includeFlightTypeName: c.includeFlightTypeName ?? false,
    noLandingTaxForGlider: c.noLandingTaxForGlider ?? false,
    noLandingTaxForTowingAircraft: c.noLandingTaxForTowingAircraft ?? false,
    noLandingTaxForAircraft: c.noLandingTaxForAircraft ?? false,
  };
}
