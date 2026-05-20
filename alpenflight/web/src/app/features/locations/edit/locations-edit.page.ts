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
  FormArray,
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  type ValidatorFn,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { ReferenceDataStore } from '../../../core/reference-data/reference-data.store';
import { SessionStore } from '../../../core/session/session.store';
import { LocationsStore } from '../locations.store';
import {
  detailToFormValue,
  emptyInOutboundPointForm,
  formToCreateRequest,
  formToUpdateRequest,
  type InOutboundPointFormShape,
} from './locations-edit.mapper';

const ICAO_PATTERN = /^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$/;

type IopForm = FormGroup<{
  pointName: FormControl<string>;
  pointType: FormControl<string>;
  direction: FormControl<string>;
  description: FormControl<string>;
}>;

type LocationForm = FormGroup<{
  locationName: FormControl<string>;
  locationShortName: FormControl<string>;
  countryId: FormControl<string>;
  locationTypeId: FormControl<string>;
  icaoCode: FormControl<string>;
  latitude: FormControl<string>;
  longitude: FormControl<string>;
  description: FormControl<string>;
  isInboundRouteRequired: FormControl<boolean>;
  isOutboundRouteRequired: FormControl<boolean>;
  isFastEntryRecord: FormControl<boolean>;
  inOutboundPoints: FormArray<IopForm>;
}>;

@Component({
  selector: 'af-locations-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    AfFormFieldComponent,
    AfInputComponent,
    AfSelectComponent,
    AfButtonComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
  ],
  host: { class: 'block' },
  template: `
    <af-page>
      <af-page-header [title]="isCreate() ? 'New location' : 'Edit location'" />

      @if (canMutate()) {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50"
          data-testid="locations-blast-radius-banner"
        >
          Reference data — changes apply to all clubs.
        </div>
      } @else {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-600 border border-slate-200 bg-slate-50"
          data-testid="locations-readonly-banner"
        >
          Reference data — changes apply to all clubs and are managed by the system administrator.
        </div>
      }

      <af-page-error
        [message]="store.saveError()"
        [retryLabel]="null"
        data-testid="locations-save-error"
      />
      <af-page-error
        [message]="referenceData.loadError() ? 'Reference data unavailable.' : null"
        (retry)="referenceData.loadAll()"
        data-testid="locations-ref-data-error"
      />

      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        data-testid="locations-edit-form"
        class="flex flex-col gap-2"
        novalidate
      >
        <af-form-field
          label="Name"
          for="LocationName"
          [required]="true"
          [errors]="form.controls.locationName.touched ? form.controls.locationName.errors : null"
        >
          <af-input inputId="LocationName" formControlName="locationName" autocomplete="off" />
        </af-form-field>

        <af-form-field
          label="Short name"
          for="LocationShortName"
          [errors]="
            form.controls.locationShortName.touched ? form.controls.locationShortName.errors : null
          "
        >
          <af-input
            inputId="LocationShortName"
            formControlName="locationShortName"
            autocomplete="off"
          />
        </af-form-field>

        <af-form-field
          label="ICAO code"
          for="IcaoCode"
          [errors]="form.controls.icaoCode.touched ? form.controls.icaoCode.errors : null"
        >
          <af-input
            inputId="IcaoCode"
            formControlName="icaoCode"
            autocomplete="off"
            placeholder="LSZH or LS22"
          />
        </af-form-field>

        <af-form-field
          label="Country"
          for="CountryId"
          [required]="true"
          [errors]="form.controls.countryId.touched ? form.controls.countryId.errors : null"
        >
          <af-select
            inputId="CountryId"
            formControlName="countryId"
            placeholder="Select country"
            [showSearch]="true"
            [options]="countryOptions()"
            data-testid="locations-country-select"
          />
        </af-form-field>

        <af-form-field
          label="Location type"
          for="LocationTypeId"
          [required]="true"
          [errors]="
            form.controls.locationTypeId.touched ? form.controls.locationTypeId.errors : null
          "
        >
          <af-select
            inputId="LocationTypeId"
            formControlName="locationTypeId"
            placeholder="Select type"
            [options]="locationTypeOptions()"
            data-testid="locations-type-select"
          />
        </af-form-field>

        <div class="grid grid-cols-2 gap-2">
          <af-form-field
            label="Latitude"
            for="Latitude"
            [errors]="form.controls.latitude.touched ? form.controls.latitude.errors : null"
          >
            <af-input inputId="Latitude" formControlName="latitude" autocomplete="off" />
          </af-form-field>
          <af-form-field
            label="Longitude"
            for="Longitude"
            [errors]="form.controls.longitude.touched ? form.controls.longitude.errors : null"
          >
            <af-input inputId="Longitude" formControlName="longitude" autocomplete="off" />
          </af-form-field>
        </div>

        <af-form-field label="Description" for="Description">
          <af-input inputId="Description" formControlName="description" autocomplete="off" />
        </af-form-field>

        <label class="flex items-center gap-2 cursor-pointer select-none mt-2">
          <input
            type="checkbox"
            formControlName="isInboundRouteRequired"
            class="w-4 h-4 accent-brand-500 cursor-pointer"
          />
          <span>Inbound route required</span>
        </label>
        <label class="flex items-center gap-2 cursor-pointer select-none">
          <input
            type="checkbox"
            formControlName="isOutboundRouteRequired"
            class="w-4 h-4 accent-brand-500 cursor-pointer"
          />
          <span>Outbound route required</span>
        </label>
        <label class="flex items-center gap-2 cursor-pointer select-none mb-4">
          <input
            type="checkbox"
            formControlName="isFastEntryRecord"
            class="w-4 h-4 accent-brand-500 cursor-pointer"
          />
          <span>Fast-entry record</span>
        </label>

        @if (showIopSection()) {
          <section class="mt-2 pt-3 border-t border-slate-200" data-testid="locations-iop-section">
            <header class="flex items-center justify-between mb-2">
              <h2 class="text-base font-medium text-slate-900 m-0">
                Approach and departure points
              </h2>
              @if (canMutate()) {
                <af-button htmlType="button" (clicked)="addIop()" data-testid="locations-iop-add">
                  Add point
                </af-button>
              }
            </header>

            @if (iopArray.controls.length === 0) {
              <p class="text-sm text-slate-500">No in/outbound points.</p>
            }

            <ul class="list-none m-0 p-0 flex flex-col gap-2" formArrayName="inOutboundPoints">
              @for (iop of iopArray.controls; let i = $index; track i) {
                <li
                  class="grid grid-cols-1 sm:grid-cols-[1fr_1fr_1fr_1fr_auto] gap-2 items-end p-3 border border-slate-200 bg-white"
                  [formGroupName]="i"
                  [attr.data-testid]="'locations-iop-row-' + i"
                >
                  <af-form-field label="Name" [required]="true">
                    <af-input
                      formControlName="pointName"
                      autocomplete="off"
                      [attr.data-testid]="'locations-iop-name-' + i"
                    />
                  </af-form-field>
                  <af-form-field label="Type">
                    <af-input
                      formControlName="pointType"
                      autocomplete="off"
                      [attr.data-testid]="'locations-iop-type-' + i"
                    />
                  </af-form-field>
                  <af-form-field label="Direction">
                    <af-input
                      formControlName="direction"
                      autocomplete="off"
                      [attr.data-testid]="'locations-iop-direction-' + i"
                    />
                  </af-form-field>
                  <af-form-field label="Description">
                    <af-input
                      formControlName="description"
                      autocomplete="off"
                      [attr.data-testid]="'locations-iop-description-' + i"
                    />
                  </af-form-field>
                  @if (canMutate()) {
                    <button
                      type="button"
                      class="w-9 h-9 inline-flex items-center justify-center bg-transparent border border-slate-200 text-slate-500 cursor-pointer hover:text-red-600 hover:border-red-300"
                      (click)="removeIop(i)"
                      [attr.aria-label]="'Remove in/outbound point ' + (i + 1)"
                      [attr.data-testid]="'locations-iop-remove-' + i"
                    >
                      <af-icon name="trash-2" [size]="16" />
                    </button>
                  }
                </li>
              }
            </ul>
          </section>
        }

        <div class="flex gap-2 justify-end mt-4 pt-4 border-t border-slate-200">
          <af-button htmlType="button" (clicked)="router.navigateByUrl('/locations')">
            Cancel
          </af-button>
          @if (canMutate()) {
            <af-button
              type="primary"
              htmlType="submit"
              [disabled]="form.invalid || saveSubmitted()"
              data-testid="locations-save-button"
            >
              Save
            </af-button>
          }
        </div>
      </form>
    </af-page>
  `,
})
export class LocationsEditPage {
  protected readonly store = inject(LocationsStore);
  protected readonly referenceData = inject(ReferenceDataStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly locationId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.locationId() === null);
  protected readonly canMutate = computed(() => this.session.isSystemAdmin());
  protected readonly showIopSection = computed(() => !this.isCreate());

  protected readonly countryOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.referenceData.countries().map((c) => ({ value: c.id, label: c.name ?? c.id })),
  );
  protected readonly locationTypeOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.referenceData.locationTypes().map((t) => ({
      value: t.id,
      label: t.description ?? t.code ?? t.id,
    })),
  );

  protected readonly form: LocationForm = this.fb.group({
    locationName: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(100)]),
    locationShortName: this.fb.nonNullable.control('', [Validators.maxLength(50)]),
    countryId: this.fb.nonNullable.control('', [Validators.required]),
    locationTypeId: this.fb.nonNullable.control('', [Validators.required]),
    icaoCode: this.fb.nonNullable.control('', [Validators.maxLength(10), icaoFormatValidator()]),
    latitude: this.fb.nonNullable.control('', [Validators.maxLength(10)]),
    longitude: this.fb.nonNullable.control('', [Validators.maxLength(10)]),
    description: this.fb.nonNullable.control(''),
    isInboundRouteRequired: this.fb.nonNullable.control(false),
    isOutboundRouteRequired: this.fb.nonNullable.control(false),
    isFastEntryRecord: this.fb.nonNullable.control(false),
    inOutboundPoints: this.fb.array<IopForm>([]),
  });

  protected get iopArray(): FormArray<IopForm> {
    return this.form.controls.inOutboundPoints;
  }

  protected readonly saveSubmitted = signal(false);

  constructor() {
    effect(() => {
      const id = this.locationId();
      if (!id) {
        this.store.select(null);
        return;
      }
      this.store.select(id);
      this.store.loadOne(id);
    });

    effect(() => {
      const detail = this.store.selectedLocation();
      if (!detail) return;
      const countriesReady = this.referenceData.countries().length > 0;
      const typesReady = this.referenceData.locationTypes().length > 0;
      const value = detailToFormValue(detail);
      this.iopArray.clear({ emitEvent: false });
      for (const p of value.inOutboundPoints) {
        this.iopArray.push(this.makeIopGroup(p), { emitEvent: false });
      }
      this.form.patchValue(
        {
          locationName: value.locationName,
          locationShortName: value.locationShortName,
          countryId: countriesReady ? value.countryId : '',
          locationTypeId: typesReady ? value.locationTypeId : '',
          icaoCode: value.icaoCode,
          latitude: value.latitude,
          longitude: value.longitude,
          description: value.description,
          isInboundRouteRequired: value.isInboundRouteRequired,
          isOutboundRouteRequired: value.isOutboundRouteRequired,
          isFastEntryRecord: value.isFastEntryRecord,
        },
        { emitEvent: false },
      );
      if (!this.canMutate()) {
        this.form.disable({ emitEvent: false });
      }
    });

    effect(() => {
      const err = this.store.saveError();
      if (!err) return;
      this.saveSubmitted.set(false);
      if (this.store.saveErrorKind() === 'icao-duplicate') {
        this.form.controls.icaoCode.setErrors({ duplicate: true });
        this.form.controls.icaoCode.markAsTouched();
      }
    });

    const destroyRef = inject(DestroyRef);
    // Clear the synthetic `duplicate` flag the moment the user retypes the
    // ICAO so Save re-enables without a tab-out/tab-back blur dance.
    this.form.controls.icaoCode.valueChanges.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => {
      if (this.form.controls.icaoCode.hasError('duplicate')) {
        const errs = { ...this.form.controls.icaoCode.errors };
        delete errs['duplicate'];
        this.form.controls.icaoCode.setErrors(Object.keys(errs).length ? errs : null);
      }
      // Drop the page-level save banner too so the user isn't reading a stale
      // "already in use" prompt while already retyping the value.
      if (this.store.saveErrorKind() === 'icao-duplicate') {
        this.store.clearSaveError();
      }
    });
    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (evt.kind === 'location.created' || evt.kind === 'location.updated') {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/locations');
      }
    });
  }

  protected addIop(): void {
    this.iopArray.push(this.makeIopGroup(emptyInOutboundPointForm()));
  }

  protected removeIop(index: number): void {
    this.iopArray.removeAt(index);
  }

  protected onSubmit(): void {
    if (!this.canMutate()) return;
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saveSubmitted.set(true);
    const id = this.locationId();
    if (id) {
      this.store.update({ id, req: formToUpdateRequest(value) });
    } else {
      this.store.create(formToCreateRequest(value));
    }
  }

  private makeIopGroup(value: InOutboundPointFormShape): IopForm {
    return this.fb.group({
      pointName: this.fb.nonNullable.control(value.pointName, [
        Validators.required,
        Validators.maxLength(100),
      ]),
      pointType: this.fb.nonNullable.control(value.pointType, [Validators.maxLength(50)]),
      direction: this.fb.nonNullable.control(value.direction, [Validators.maxLength(50)]),
      description: this.fb.nonNullable.control(value.description, [Validators.maxLength(500)]),
    });
  }
}

function icaoFormatValidator(): ValidatorFn {
  return (control: AbstractControl) => {
    const raw = control.value as string | null;
    if (!raw || raw.trim().length === 0) return null;
    return ICAO_PATTERN.test(raw.toUpperCase()) ? null : { pattern: true };
  };
}
