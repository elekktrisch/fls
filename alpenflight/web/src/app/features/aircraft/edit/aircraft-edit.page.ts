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
  type AbstractControl,
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  type ValidatorFn,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import type {
  AircraftCreateRequest,
  AircraftDetail,
  AircraftUpdateRequest,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfInputGroupComponent } from '@ui/molecules/af-input-group';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { ClubsStore } from '../../clubs/clubs.store';
import { LocationsStore } from '../../locations/locations.store';
import { ReferenceDataStore } from '../../../core/reference-data/reference-data.store';
import { SessionStore } from '../../../core/session/session.store';
import { AircraftStore } from '../aircraft.store';

const IMMAT_PATTERN = /^[A-Z0-9-]{2,15}$/;
const FLARM_PATTERN = /^[A-Fa-f0-9]{6}$/;
const COMP_SIGN_PATTERN = /^[A-Za-z0-9]{1,5}$/;

type AircraftForm = FormGroup<{
  aircraftTypeId: FormControl<string>;
  immatriculation: FormControl<string>;
  ownerClubId: FormControl<string>;
  manufacturerName: FormControl<string>;
  aircraftModel: FormControl<string>;
  competitionSign: FormControl<string>;
  flarmId: FormControl<string>;
  aircraftSerialNumber: FormControl<string>;
  yearOfManufacture: FormControl<string>;
  noiseClass: FormControl<string>;
  noiseLevel: FormControl<number | null>;
  mtom: FormControl<number | null>;
  nrOfSeats: FormControl<number | null>;
  daecIndex: FormControl<number | null>;
  homebaseId: FormControl<string>;
  // Preserved from the loaded detail and echoed back on update. Not user-
  // editable on the master-data form (legacy parity — the legacy form has no
  // flight-counter dropdown; the value is set elsewhere in the legacy stack).
  flightOperatingCounterUnitTypeId: FormControl<string>;
  engineOperatingCounterUnitTypeId: FormControl<string>;
  spotLink: FormControl<string>;
  isTowingOrWinchRequired: FormControl<boolean>;
  isTowingStartAllowed: FormControl<boolean>;
  isWinchStartAllowed: FormControl<boolean>;
  isTowingAircraft: FormControl<boolean>;
  comment: FormControl<string>;
}>;

@Component({
  selector: 'af-aircraft-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    AfFormFieldComponent,
    AfInputGroupComponent,
    AfInputComponent,
    AfSelectComponent,
    AfButtonComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
  ],
  host: { class: 'block' },
  template: `
    <af-page>
      <af-page-header [title]="isCreate() ? 'New aircraft' : 'Edit aircraft'" />

      <af-page-error
        [message]="store.saveError()"
        [retryLabel]="null"
        data-testid="aircraft-save-error"
      />
      <af-page-error
        [message]="referenceData.loadError() ? 'Reference data unavailable.' : null"
        (retry)="referenceData.loadAll()"
        data-testid="aircraft-ref-data-error"
      />

      @if (!isCreate() && currentState(); as state) {
        <div
          class="mb-3 px-3 py-2 text-sm border border-slate-200 bg-slate-50"
          data-testid="aircraft-current-state"
        >
          <span class="text-slate-500">Current state:</span>
          <span class="ml-1 font-medium text-slate-900">{{ state.aircraftStateCode }}</span>
          <span class="ml-2 text-slate-500">since {{ state.validFromDisplay }}</span>
        </div>
      }
      @if (!isCreate() && latestCounter(); as counter) {
        <div
          class="mb-4 px-3 py-2 text-sm border border-slate-200 bg-slate-50 tabular"
          data-testid="aircraft-latest-counter"
        >
          <span class="text-slate-500">Latest counter:</span>
          <span class="ml-1 text-slate-900">{{ counter }}</span>
        </div>
      }

      @if (store.isLoadingDetail() && !isCreate()) {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
          data-testid="aircraft-loading"
          role="status"
          aria-live="polite"
        >
          Loading aircraft…
        </div>
      } @else {
        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          data-testid="aircraft-edit-form"
          class="flex flex-col gap-6"
          novalidate
        >
          <!-- Section 1: Master data -->
          <section class="flex flex-col gap-2" data-testid="aircraft-section-masterdata">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              Master data
            </h2>
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
              <af-form-field
                label="Immatriculation"
                for="Immatriculation"
                [required]="true"
                [errors]="
                  form.controls.immatriculation.touched
                    ? form.controls.immatriculation.errors
                    : null
                "
              >
                <af-input
                  inputId="Immatriculation"
                  formControlName="immatriculation"
                  autocomplete="off"
                  placeholder="HB-3000"
                />
              </af-form-field>
              <af-form-field
                label="Competition sign"
                for="CompetitionSign"
                [errors]="
                  form.controls.competitionSign.touched
                    ? form.controls.competitionSign.errors
                    : null
                "
              >
                <af-input
                  inputId="CompetitionSign"
                  formControlName="competitionSign"
                  autocomplete="off"
                  placeholder="A123"
                />
              </af-form-field>
              <af-form-field
                label="Type"
                for="AircraftTypeId"
                [required]="true"
                [errors]="
                  form.controls.aircraftTypeId.touched ? form.controls.aircraftTypeId.errors : null
                "
              >
                <af-select
                  inputId="AircraftTypeId"
                  formControlName="aircraftTypeId"
                  placeholder="Select type"
                  [options]="typeOptions()"
                  data-testid="aircraft-type-select"
                />
              </af-form-field>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
              <af-form-field label="Manufacturer" for="ManufacturerName">
                <af-input
                  inputId="ManufacturerName"
                  formControlName="manufacturerName"
                  autocomplete="off"
                />
              </af-form-field>
              <af-form-field label="Model" for="AircraftModel">
                <af-input
                  inputId="AircraftModel"
                  formControlName="aircraftModel"
                  autocomplete="off"
                />
              </af-form-field>
              <af-form-field label="Seats" for="NrOfSeats">
                <af-input
                  inputId="NrOfSeats"
                  type="number"
                  formControlName="nrOfSeats"
                  autocomplete="off"
                />
              </af-form-field>
            </div>
          </section>

          <!-- Section 2: Operational data -->
          <section class="flex flex-col gap-2" data-testid="aircraft-section-operational">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              Operational data
            </h2>
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
              @if (isCreate() && showOwnerSelect()) {
                <af-form-field label="Owner club" for="OwnerClubId">
                  <af-select
                    inputId="OwnerClubId"
                    formControlName="ownerClubId"
                    placeholder="Charter (no owner)"
                    [options]="ownerClubOptions()"
                    data-testid="aircraft-owner-select"
                  />
                </af-form-field>
              }
              <af-form-field label="Homebase" for="HomebaseId">
                <af-select
                  inputId="HomebaseId"
                  formControlName="homebaseId"
                  placeholder="No homebase"
                  [options]="homebaseOptions()"
                  data-testid="aircraft-homebase-select"
                />
              </af-form-field>
            </div>
            <af-form-field
              label="SPOT tracker link"
              for="SpotLink"
              [errors]="form.controls.spotLink.touched ? form.controls.spotLink.errors : null"
            >
              <af-input-group>
                <af-input
                  inputId="SpotLink"
                  formControlName="spotLink"
                  autocomplete="off"
                  placeholder="https://share.findmespot.com/…"
                />
                <af-button
                  htmlType="button"
                  [disabled]="!canTestSpotLink()"
                  (clicked)="openSpotLink()"
                  data-testid="aircraft-spotlink-test"
                >
                  Test link
                </af-button>
              </af-input-group>
            </af-form-field>
            <div class="flex flex-col gap-1 mt-1">
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isTowingAircraft"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>This aircraft is a towing aircraft</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isTowingOrWinchRequired"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Towing or winch required</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isTowingStartAllowed"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Towing start allowed</span>
              </label>
              <label class="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  formControlName="isWinchStartAllowed"
                  class="w-4 h-4 accent-brand-500 cursor-pointer"
                />
                <span>Winch start allowed</span>
              </label>
            </div>
          </section>

          <!-- Section 3: Technical data -->
          <section class="flex flex-col gap-2" data-testid="aircraft-section-technical">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              Technical data
            </h2>
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
              <af-form-field label="MTOM (kg)" for="Mtom">
                <af-input inputId="Mtom" type="number" formControlName="mtom" autocomplete="off" />
              </af-form-field>
              <af-form-field label="Serial number" for="SerialNumber">
                <af-input
                  inputId="SerialNumber"
                  formControlName="aircraftSerialNumber"
                  autocomplete="off"
                />
              </af-form-field>
              <af-form-field
                label="Year of manufacture"
                for="YearOfManufacture"
                [errors]="
                  form.controls.yearOfManufacture.touched
                    ? form.controls.yearOfManufacture.errors
                    : null
                "
              >
                <af-input
                  inputId="YearOfManufacture"
                  formControlName="yearOfManufacture"
                  autocomplete="off"
                  placeholder="YYYY-MM-DD (e.g. 2008-01-01)"
                />
              </af-form-field>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
              <af-form-field
                label="FLARM ID"
                for="FlarmId"
                [errors]="form.controls.flarmId.touched ? form.controls.flarmId.errors : null"
              >
                <af-input
                  inputId="FlarmId"
                  formControlName="flarmId"
                  autocomplete="off"
                  placeholder="DDAABB"
                />
              </af-form-field>
              <af-form-field label="DAEC index" for="DaecIndex">
                <af-input
                  inputId="DaecIndex"
                  type="number"
                  formControlName="daecIndex"
                  autocomplete="off"
                />
              </af-form-field>
              <af-form-field label="Noise class" for="NoiseClass">
                <af-input
                  inputId="NoiseClass"
                  formControlName="noiseClass"
                  autocomplete="off"
                  placeholder="A"
                />
              </af-form-field>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <af-form-field label="Noise level (dB)" for="NoiseLevel">
                <af-input
                  inputId="NoiseLevel"
                  type="number"
                  formControlName="noiseLevel"
                  autocomplete="off"
                />
              </af-form-field>
              @if (selectedTypeHasEngine()) {
                <af-form-field label="Engine counter unit" for="EngineCounterUnitTypeId">
                  <af-select
                    inputId="EngineCounterUnitTypeId"
                    formControlName="engineOperatingCounterUnitTypeId"
                    placeholder="Select unit"
                    [options]="counterUnitTypeOptions()"
                    data-testid="aircraft-engine-counter-unit-select"
                  />
                </af-form-field>
              }
            </div>
            <af-form-field label="Comment" for="Comment">
              <af-input inputId="Comment" formControlName="comment" autocomplete="off" />
            </af-form-field>
          </section>

          <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
            <af-button htmlType="button" (clicked)="router.navigateByUrl('/aircraft')">
              Cancel
            </af-button>
            @if (canMutate()) {
              <af-button
                type="primary"
                htmlType="submit"
                [disabled]="form.invalid || saveSubmitted()"
                data-testid="aircraft-save-button"
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
export class AircraftEditPage {
  protected readonly store = inject(AircraftStore);
  protected readonly referenceData = inject(ReferenceDataStore);
  protected readonly clubs = inject(ClubsStore);
  protected readonly locations = inject(LocationsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly aircraftId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.aircraftId() === null);
  protected readonly canMutate = this.session.isAnyAdmin;
  // Only SYSTEM_ADMIN can pick the owner; CLUB_ADMIN's aircraft are auto-owned
  // by their own club server-side (the controller fills it in from the JWT).
  protected readonly showOwnerSelect = this.session.isSystemAdmin;

  protected readonly typeOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.referenceData.aircraftTypes().map((t) => ({
      value: t.id,
      label: t.description ?? t.code ?? t.id,
    })),
  );

  protected readonly homebaseOptions = computed<readonly AfSelectOption<string>[]>(() => [
    { value: '', label: '— No homebase —' },
    ...this.locations.entities().map((l) => ({ value: l.id, label: l.locationName })),
  ]);

  // The flight + engine operating counters are time counters (seconds-typed
  // columns on aircraft_operating_counter); only `HOURS_*` units make sense
  // for them. The seeded `counter_unit_type` catalog also includes LANDINGS
  // + STARTS (used for the totalWinchLaunchStarts / totalSelfStarts integer
  // columns, NOT for the FK fields the form binds to) — filter those out at
  // the UI so users can't select a count-unit for a time-counter dropdown.
  // Documented in the story's `## Parity exclusions`.
  protected readonly counterUnitTypeOptions = computed<readonly AfSelectOption<string>[]>(() => [
    { value: '', label: '— Not set —' },
    ...this.referenceData
      .counterUnitTypes()
      .filter((u) => u.code.startsWith('HOURS_'))
      .map((u) => ({
        value: u.id,
        label: u.shortName ? `${u.name} (${u.shortName})` : u.name,
      })),
  ]);

  protected readonly ownerClubOptions = computed<readonly AfSelectOption<string>[]>(() => [
    { value: '', label: '— Charter (no owner) —' },
    ...this.clubs.entities().map((c) => ({ value: c.id, label: c.name ?? c.id })),
  ]);

  protected readonly currentState = computed(() => {
    const detail = this.store.selectedAircraft();
    if (!detail?.currentState) return null;
    const stateById = this.referenceData.aircraftStateById();
    const state = stateById.get(detail.currentState.aircraftStateId);
    return {
      aircraftStateCode: state?.code ?? detail.currentState.aircraftStateId,
      validFromDisplay: detail.currentState.validFrom,
    };
  });

  protected readonly latestCounter = computed(() => {
    const detail = this.store.selectedAircraft();
    const c = detail?.latestCounter;
    if (!c) return null;
    const parts: string[] = [];
    if (c.atDateTime) parts.push(`recorded ${c.atDateTime}`);
    if (c.flightOperatingCounterInSeconds != null) {
      parts.push(`flight ${formatHours(c.flightOperatingCounterInSeconds)}h`);
    }
    if (c.engineOperatingCounterInSeconds != null) {
      parts.push(`engine ${formatHours(c.engineOperatingCounterInSeconds)}h`);
    }
    return parts.length === 0 ? null : parts.join(' · ');
  });

  protected readonly form: AircraftForm = this.fb.group({
    aircraftTypeId: this.fb.nonNullable.control('', [Validators.required]),
    immatriculation: this.fb.nonNullable.control('', [
      Validators.required,
      Validators.minLength(2),
      Validators.maxLength(15),
      Validators.pattern(IMMAT_PATTERN),
    ]),
    ownerClubId: this.fb.nonNullable.control(''),
    manufacturerName: this.fb.nonNullable.control('', [Validators.maxLength(100)]),
    aircraftModel: this.fb.nonNullable.control('', [Validators.maxLength(50)]),
    competitionSign: this.fb.nonNullable.control('', [
      Validators.maxLength(5),
      patternOrEmpty(COMP_SIGN_PATTERN),
    ]),
    flarmId: this.fb.nonNullable.control('', [
      Validators.maxLength(50),
      patternOrEmpty(FLARM_PATTERN),
    ]),
    aircraftSerialNumber: this.fb.nonNullable.control('', [Validators.maxLength(20)]),
    yearOfManufacture: this.fb.nonNullable.control('', [isoDateOrEmpty()]),
    noiseClass: this.fb.nonNullable.control('', [Validators.maxLength(1)]),
    noiseLevel: this.fb.control<number | null>(null),
    mtom: this.fb.control<number | null>(null, [Validators.min(0)]),
    nrOfSeats: this.fb.control<number | null>(null, [Validators.min(0)]),
    daecIndex: this.fb.control<number | null>(null),
    homebaseId: this.fb.nonNullable.control(''),
    flightOperatingCounterUnitTypeId: this.fb.nonNullable.control(''),
    engineOperatingCounterUnitTypeId: this.fb.nonNullable.control(''),
    spotLink: this.fb.nonNullable.control('', [Validators.maxLength(250), httpsOrEmpty()]),
    isTowingOrWinchRequired: this.fb.nonNullable.control(false),
    isTowingStartAllowed: this.fb.nonNullable.control(false),
    isWinchStartAllowed: this.fb.nonNullable.control(false),
    isTowingAircraft: this.fb.nonNullable.control(false),
    comment: this.fb.nonNullable.control('', [Validators.maxLength(250)]),
  });

  protected readonly saveSubmitted = signal(false);

  private readonly aircraftTypeIdValue = toSignal(this.form.controls.aircraftTypeId.valueChanges, {
    initialValue: '',
  });
  private readonly spotLinkValue = toSignal(this.form.controls.spotLink.valueChanges, {
    initialValue: '',
  });

  protected readonly selectedTypeHasEngine = computed<boolean>(() => {
    const id = this.aircraftTypeIdValue();
    if (!id) return false;
    return this.referenceData.aircraftTypeById().get(id)?.hasEngine === true;
  });

  protected readonly canTestSpotLink = computed<boolean>(() => {
    const v = (this.spotLinkValue() ?? '').trim();
    return v.length > 0 && /^https:\/\//i.test(v);
  });

  protected openSpotLink(): void {
    const v = (this.form.controls.spotLink.value ?? '').trim();
    if (!v) return;
    window.open(v, '_blank', 'noopener');
  }

  constructor() {
    // Load reference data + clubs + locations once on entry. The stores are
    // TTL-gated so this is cheap on re-entry.
    this.referenceData.loadAll();

    effect(() => {
      const id = this.aircraftId();
      if (!id) {
        this.store.select(null);
        return;
      }
      this.store.select(id);
      this.store.loadOne(id);
    });

    effect(() => {
      const detail = this.store.selectedAircraft();
      if (!detail) return;
      // emitEvent stays true so toSignal-backed value mirrors (selectedTypeHasEngine,
      // canTestSpotLink) pick up the loaded values without needing a separate bridge.
      this.form.patchValue(detailToFormValue(detail));
    });

    // Engineless aircraft type → drop any engine-counter selection that survived
    // a type change (legacy parity: the engine-counter dropdown only renders when
    // selectedAircraftType.HasEngine in flsweb/.../aircraft-form-fields.html).
    effect(() => {
      if (this.selectedTypeHasEngine()) return;
      const ctrl = this.form.controls.engineOperatingCounterUnitTypeId;
      if (ctrl.value !== '') {
        ctrl.setValue('', { emitEvent: false });
      }
    });

    // Save-error effect: reset the submitted flag so the user can retry, and
    // hoist the typed save-error onto the offending field for inline visibility
    // (mirrors LocationsEditPage; replaces the old queueMicrotask race that
    // navigated unconditionally before the HTTP response landed).
    effect(() => {
      const err = this.store.saveError();
      if (!err) return;
      this.saveSubmitted.set(false);
      if (this.store.saveErrorKind() === 'immatriculation-duplicate') {
        // Merge into existing errors so a concurrent format / length error
        // isn't masked by the synthetic duplicate flag.
        this.form.controls.immatriculation.setErrors({
          ...(this.form.controls.immatriculation.errors ?? {}),
          duplicate: true,
        });
        this.form.controls.immatriculation.markAsTouched();
      }
    });

    const destroyRef = inject(DestroyRef);
    // Clear the synthetic `duplicate` flag the moment the user retypes the
    // immatriculation so Save re-enables without a blur dance.
    this.form.controls.immatriculation.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe(() => {
        if (this.form.controls.immatriculation.hasError('duplicate')) {
          const errs = { ...this.form.controls.immatriculation.errors };
          delete errs['duplicate'];
          this.form.controls.immatriculation.setErrors(Object.keys(errs).length ? errs : null);
        }
        if (this.store.saveErrorKind() === 'immatriculation-duplicate') {
          this.store.clearSaveError();
        }
      });

    // Navigate on the mutation-bus event — guaranteed-post-response, no race.
    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (evt.kind === 'aircraft.created' || evt.kind === 'aircraft.updated') {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/aircraft');
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
    const id = this.aircraftId();
    if (id === null) {
      this.store.create(formToCreateRequest(this.form));
    } else {
      this.store.update({ id, req: formToUpdateRequest(this.form) });
    }
  }
}

function formatHours(seconds: number): string {
  return (seconds / 3600).toFixed(1);
}

function patternOrEmpty(p: RegExp): ValidatorFn {
  return (c: AbstractControl) => {
    const v = (c.value ?? '') as string;
    if (v === '') return null;
    return p.test(v) ? null : { pattern: { requiredPattern: p.source, actualValue: v } };
  };
}

function httpsOrEmpty(): ValidatorFn {
  return (c: AbstractControl) => {
    const v = (c.value ?? '') as string;
    if (v === '') return null;
    return v.toLowerCase().startsWith('https://') ? null : { https: true };
  };
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function isoDateOrEmpty(): ValidatorFn {
  return (c: AbstractControl) => {
    const v = (c.value ?? '') as string;
    if (v === '') return null;
    return ISO_DATE.test(v) ? null : { isoDate: true };
  };
}

function detailToFormValue(d: AircraftDetail): Partial<{
  aircraftTypeId: string;
  immatriculation: string;
  ownerClubId: string;
  manufacturerName: string;
  aircraftModel: string;
  competitionSign: string;
  flarmId: string;
  aircraftSerialNumber: string;
  yearOfManufacture: string;
  noiseClass: string;
  noiseLevel: number | null;
  mtom: number | null;
  nrOfSeats: number | null;
  daecIndex: number | null;
  homebaseId: string;
  flightOperatingCounterUnitTypeId: string;
  engineOperatingCounterUnitTypeId: string;
  spotLink: string;
  isTowingOrWinchRequired: boolean;
  isTowingStartAllowed: boolean;
  isWinchStartAllowed: boolean;
  isTowingAircraft: boolean;
  comment: string;
}> {
  return {
    aircraftTypeId: d.aircraftTypeId,
    immatriculation: d.immatriculation,
    ownerClubId: d.ownerClubId ?? '',
    manufacturerName: d.manufacturerName ?? '',
    aircraftModel: d.aircraftModel ?? '',
    competitionSign: d.competitionSign ?? '',
    flarmId: d.flarmId ?? '',
    aircraftSerialNumber: d.aircraftSerialNumber ?? '',
    yearOfManufacture: d.yearOfManufacture ?? '',
    noiseClass: d.noiseClass ?? '',
    noiseLevel: d.noiseLevel ?? null,
    mtom: d.mtom ?? null,
    nrOfSeats: d.nrOfSeats ?? null,
    daecIndex: d.daecIndex ?? null,
    homebaseId: d.homebaseId ?? '',
    flightOperatingCounterUnitTypeId: d.flightOperatingCounterUnitTypeId ?? '',
    engineOperatingCounterUnitTypeId: d.engineOperatingCounterUnitTypeId ?? '',
    spotLink: d.spotLink ?? '',
    isTowingOrWinchRequired: d.isTowingOrWinchRequired,
    isTowingStartAllowed: d.isTowingStartAllowed,
    isWinchStartAllowed: d.isWinchStartAllowed,
    isTowingAircraft: d.isTowingAircraft,
    comment: d.comment ?? '',
  };
}

function formToCreateRequest(form: AircraftForm): AircraftCreateRequest {
  const v = form.getRawValue();
  const req: AircraftCreateRequest = {
    aircraftTypeId: v.aircraftTypeId,
    immatriculation: v.immatriculation.toUpperCase(),
    isTowingOrWinchRequired: v.isTowingOrWinchRequired,
    isTowingStartAllowed: v.isTowingStartAllowed,
    isWinchStartAllowed: v.isWinchStartAllowed,
    isTowingAircraft: v.isTowingAircraft,
  };
  if (v.ownerClubId !== '') req.ownerClubId = v.ownerClubId;
  if (v.manufacturerName !== '') req.manufacturerName = v.manufacturerName;
  if (v.aircraftModel !== '') req.aircraftModel = v.aircraftModel;
  if (v.competitionSign !== '') req.competitionSign = v.competitionSign;
  if (v.flarmId !== '') req.flarmId = v.flarmId;
  if (v.aircraftSerialNumber !== '') req.aircraftSerialNumber = v.aircraftSerialNumber;
  if (v.yearOfManufacture !== '') req.yearOfManufacture = v.yearOfManufacture;
  if (v.noiseClass !== '') req.noiseClass = v.noiseClass;
  if (v.noiseLevel !== null) req.noiseLevel = v.noiseLevel;
  if (v.mtom !== null) req.mtom = v.mtom;
  if (v.nrOfSeats !== null) req.nrOfSeats = v.nrOfSeats;
  if (v.daecIndex !== null) req.daecIndex = v.daecIndex;
  if (v.homebaseId !== '') req.homebaseId = v.homebaseId;
  if (v.engineOperatingCounterUnitTypeId !== '') {
    req.engineOperatingCounterUnitTypeId = v.engineOperatingCounterUnitTypeId;
  }
  if (v.spotLink !== '') req.spotLink = v.spotLink;
  if (v.comment !== '') req.comment = v.comment;
  return req;
}

function formToUpdateRequest(form: AircraftForm): AircraftUpdateRequest {
  const v = form.getRawValue();
  const req: AircraftUpdateRequest = {
    aircraftTypeId: v.aircraftTypeId,
    immatriculation: v.immatriculation.toUpperCase(),
    isTowingOrWinchRequired: v.isTowingOrWinchRequired,
    isTowingStartAllowed: v.isTowingStartAllowed,
    isWinchStartAllowed: v.isWinchStartAllowed,
    isTowingAircraft: v.isTowingAircraft,
  };
  if (v.manufacturerName !== '') req.manufacturerName = v.manufacturerName;
  if (v.aircraftModel !== '') req.aircraftModel = v.aircraftModel;
  if (v.competitionSign !== '') req.competitionSign = v.competitionSign;
  if (v.flarmId !== '') req.flarmId = v.flarmId;
  if (v.aircraftSerialNumber !== '') req.aircraftSerialNumber = v.aircraftSerialNumber;
  if (v.yearOfManufacture !== '') req.yearOfManufacture = v.yearOfManufacture;
  if (v.noiseClass !== '') req.noiseClass = v.noiseClass;
  if (v.noiseLevel !== null) req.noiseLevel = v.noiseLevel;
  if (v.mtom !== null) req.mtom = v.mtom;
  if (v.nrOfSeats !== null) req.nrOfSeats = v.nrOfSeats;
  if (v.daecIndex !== null) req.daecIndex = v.daecIndex;
  if (v.homebaseId !== '') req.homebaseId = v.homebaseId;
  // flightOperatingCounterUnitTypeId is preserved from the loaded detail and
  // echoed back unchanged — the master-data form has no UI for it (parity).
  if (v.flightOperatingCounterUnitTypeId !== '') {
    req.flightOperatingCounterUnitTypeId = v.flightOperatingCounterUnitTypeId;
  }
  if (v.engineOperatingCounterUnitTypeId !== '') {
    req.engineOperatingCounterUnitTypeId = v.engineOperatingCounterUnitTypeId;
  }
  if (v.spotLink !== '') req.spotLink = v.spotLink;
  if (v.comment !== '') req.comment = v.comment;
  return req;
}
