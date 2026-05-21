import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
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
import { TranslocoDirective } from '@jsverse/transloco';

import { LocationsAdminService } from '@core/../api/generated/locations-admin/locations-admin.service';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { ReferenceDataStore } from '../../../core/reference-data/reference-data.store';
import {
  detailToFormValue,
  emptyInOutboundPointForm,
  formToCreateRequest,
  formToUpdateRequest,
  type InOutboundPointFormShape,
} from '../../locations/edit/locations-edit.mapper';

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

/**
 * Sysadmin cross-tenant edit/create form for a single Location under any
 * club. Mirrors `LocationsEditPage` but calls `LocationsAdminService`
 * (`/api/v1/admin/locations/{clubId}/*`) instead of the regular store —
 * the server wraps each call in `Tenants.runAs(clubId)` so Hibernate's
 * `@TenantId` filter targets the picked club. Reuses the shared mapper +
 * validators so the wire shape stays identical.
 *
 * State is kept page-local (no signal store) because the admin flow is
 * single-shot: pick club → edit one Location → return to the list. The
 * regular store is per-tenant and would have to be re-keyed per club to
 * carry sysadmin state — not worth the surface for a fix-up workflow.
 */
@Component({
  selector: 'af-admin-locations-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    TranslocoDirective,
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
    <ng-container *transloco="let t; read: 'locations.admin'">
      <af-page>
        <af-page-header [title]="isCreate() ? t('new') : t('edit')" />

        <div
          class="mb-4 px-3 py-2 text-sm text-slate-600 border-y border-r border-slate-200 border-l-2 border-l-amber-500 bg-slate-50"
        >
          {{ t('banner') }}
        </div>

        <af-page-error
          [message]="saveError()"
          [retryLabel]="null"
          data-testid="admin-locations-save-error"
        />
        <af-page-error
          [message]="referenceData.loadError() ? 'Reference data unavailable.' : null"
          (retry)="referenceData.loadAll()"
          data-testid="admin-locations-ref-data-error"
        />

        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          data-testid="admin-locations-edit-form"
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
              form.controls.locationShortName.touched
                ? form.controls.locationShortName.errors
                : null
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
              data-testid="admin-locations-country-select"
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
              data-testid="admin-locations-type-select"
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

          @if (!isCreate()) {
            <section
              class="mt-2 pt-3 border-t border-slate-200"
              data-testid="admin-locations-iop-section"
            >
              <header class="flex items-center justify-between mb-2">
                <h2 class="text-base font-medium text-slate-900 m-0">
                  Approach and departure points
                </h2>
                <af-button
                  htmlType="button"
                  (clicked)="addIop()"
                  data-testid="admin-locations-iop-add"
                >
                  Add point
                </af-button>
              </header>

              @if (iopArray.controls.length === 0) {
                <p class="text-sm text-slate-500">No in/outbound points.</p>
              }

              <ul class="list-none m-0 p-0 flex flex-col gap-2" formArrayName="inOutboundPoints">
                @for (iop of iopArray.controls; let i = $index; track i) {
                  <li
                    class="grid grid-cols-1 sm:grid-cols-[1fr_1fr_1fr_1fr_auto] gap-2 items-end p-3 border border-slate-200 bg-white"
                    [formGroupName]="i"
                    [attr.data-testid]="'admin-locations-iop-row-' + i"
                  >
                    <af-form-field label="Name" [required]="true">
                      <af-input formControlName="pointName" autocomplete="off" />
                    </af-form-field>
                    <af-form-field label="Type">
                      <af-input formControlName="pointType" autocomplete="off" />
                    </af-form-field>
                    <af-form-field label="Direction">
                      <af-input formControlName="direction" autocomplete="off" />
                    </af-form-field>
                    <af-form-field label="Description">
                      <af-input formControlName="description" autocomplete="off" />
                    </af-form-field>
                    <button
                      type="button"
                      class="w-9 h-9 inline-flex items-center justify-center bg-transparent border border-slate-200 text-slate-500 cursor-pointer hover:text-red-600 hover:border-red-300"
                      (click)="removeIop(i)"
                      [attr.aria-label]="'Remove in/outbound point ' + (i + 1)"
                      [attr.data-testid]="'admin-locations-iop-remove-' + i"
                    >
                      <af-icon name="trash-2" [size]="16" />
                    </button>
                  </li>
                }
              </ul>
            </section>
          }

          <div class="flex gap-2 justify-end mt-4 pt-4 border-t border-slate-200">
            <af-button htmlType="button" (clicked)="onCancel()">Cancel</af-button>
            <af-button
              type="primary"
              htmlType="submit"
              [disabled]="form.invalid || saveSubmitted()"
              data-testid="admin-locations-save-button"
            >
              Save
            </af-button>
          </div>
        </form>
      </af-page>
    </ng-container>
  `,
})
export class AdminLocationsEditPage {
  protected readonly referenceData = inject(ReferenceDataStore);
  private readonly adminApi = inject(LocationsAdminService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  private readonly params = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly clubId = computed(() => this.params().get('clubId'));
  protected readonly locationId = computed(() => this.params().get('id'));
  protected readonly isCreate = computed(() => this.locationId() === null);

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
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const clubId = this.clubId();
      const id = this.locationId();
      if (!clubId || !id) return;
      this.adminApi.adminGetLocation(clubId, id).subscribe({
        next: (detail) => {
          const value = detailToFormValue(detail);
          this.iopArray.clear({ emitEvent: false });
          for (const p of value.inOutboundPoints) {
            this.iopArray.push(this.makeIopGroup(p), { emitEvent: false });
          }
          const countriesReady = this.referenceData.countries().length > 0;
          const typesReady = this.referenceData.locationTypes().length > 0;
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
        },
        error: () => this.saveError.set('Failed to load the Location.'),
      });
    });
  }

  protected addIop(): void {
    this.iopArray.push(this.makeIopGroup(emptyInOutboundPointForm()));
  }

  protected removeIop(index: number): void {
    this.iopArray.removeAt(index);
  }

  protected onCancel(): void {
    this.navigateBackToList();
  }

  protected onSubmit(): void {
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    const clubId = this.clubId();
    if (!clubId) return;
    const value = this.form.getRawValue();
    this.saveSubmitted.set(true);
    this.saveError.set(null);
    const id = this.locationId();
    const op$ = id
      ? this.adminApi.adminUpdateLocation(clubId, id, formToUpdateRequest(value))
      : this.adminApi.adminCreateLocation(clubId, formToCreateRequest(value));
    op$.subscribe({
      next: () => this.navigateBackToList(),
      error: (err) => {
        this.saveSubmitted.set(false);
        // 409 surfaces ICAO duplicate; everything else falls through as a
        // generic save failure. Body shape mirrors the regular endpoint, so
        // the same kind-detection logic from the user flow could be reused
        // once a sysadmin actually hits one — kept simple here.
        if (err?.status === 409) {
          this.form.controls.icaoCode.setErrors({ duplicate: true });
          this.form.controls.icaoCode.markAsTouched();
          this.saveError.set('ICAO code is already in use.');
        } else {
          this.saveError.set('Failed to save the Location.');
        }
      },
    });
  }

  private navigateBackToList(): void {
    const clubId = this.clubId();
    this.router.navigate(['/admin/locations'], {
      queryParams: clubId ? { clubId } : {},
    });
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
