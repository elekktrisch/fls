import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { liveFieldErrors } from '@shared/util/form';

import type { FlightReportSearchFilter } from '@api/generated/model';

import { decodeCustomFilter, encodeCustomFilter, formToFilter } from '../custom-filter';
import { ReportStore } from '../report.store';

type CustomBuilderForm = FormGroup<{
  from: FormControl<string>;
  to: FormControl<string>;
  glider: FormControl<boolean>;
  motor: FormControl<boolean>;
  tow: FormControl<boolean>;
  scopeId: FormControl<string>;
}>;

/**
 * Custom report builder form (legacy `flightreport-custom-configuration.html`),
 * mounted on `/flightreports/custom/:category/:filter/edit`.
 *
 * Mirrors the legacy field set: a FlightDate From/To range, three flight-type
 * toggles (Glider / Motor / Tow), and a conditional selector — a person picker
 * (`:category === 'person'`) or a location picker (`:category === 'location'`).
 * Apply builds a {@link FlightReportSearchFilter}, encodes it into the route's
 * `:filter` segment (`encodeCustomFilter`) and navigates to
 * `custom/:category/:filter/apply`, where the results page decodes it and renders
 * the filtered set — the filter round-trips through the route param (the AC).
 *
 * Built to the as-you-type bar (J-6b `liveFieldErrors`, not the touched-only
 * wiring): the From/To required messages render inline + debounced while typing.
 * Kept LOW-CRAP — this is a filter form, not an entity CRUD, so the
 * `*-edit.page.ts` `formToUpdateRequest`/`errorPatch` cascade is deliberately
 * NOT replicated; one small pure `formToFilter` builder is all the mapping there
 * is. Person/location option data is fetched by {@link ReportStore} (it owns the
 * HTTP — web CLAUDE.md §4).
 */
@Component({
  selector: 'af-report-custom-builder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfButtonComponent,
    AfInputComponent,
    AfSelectComponent,
    AfFormFieldComponent,
    AfPageComponent,
    AfPageHeaderComponent,
  ],
  template: `
    <af-page>
      <af-page-header [title]="pageTitle()" />

      <form
        [formGroup]="form"
        (ngSubmit)="onApply()"
        data-testid="report-custom-form"
        class="flex flex-col gap-6 border border-slate-200 bg-white p-4"
        novalidate
      >
        <!-- FlightDate range -->
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <af-form-field
            label="Date from"
            for="CustomFrom"
            [required]="true"
            [errors]="fromErrors()"
          >
            <af-input
              inputId="CustomFrom"
              type="date"
              formControlName="from"
              data-testid="report-custom-from"
            />
          </af-form-field>
          <af-form-field label="Date to" for="CustomTo" [required]="true" [errors]="toErrors()">
            <af-input
              inputId="CustomTo"
              type="date"
              formControlName="to"
              data-testid="report-custom-to"
            />
          </af-form-field>
        </div>

        <!-- Flight-type toggles -->
        <fieldset class="flex flex-wrap gap-6 m-0 p-0 border-0">
          <legend class="text-sm font-medium text-slate-700 mb-2 p-0">Flight types</legend>
          <label class="inline-flex items-center gap-2 text-sm text-slate-700">
            <input type="checkbox" formControlName="glider" data-testid="report-custom-glider" />
            Glider
          </label>
          <label class="inline-flex items-center gap-2 text-sm text-slate-700">
            <input type="checkbox" formControlName="motor" data-testid="report-custom-motor" />
            Motor
          </label>
          <label class="inline-flex items-center gap-2 text-sm text-slate-700">
            <input type="checkbox" formControlName="tow" data-testid="report-custom-tow" />
            Tow
          </label>
        </fieldset>

        <!-- Conditional selector: location (category=location) or person (category=person) -->
        @if (category() === 'location') {
          <af-form-field label="Location" for="CustomLocation">
            <af-select
              inputId="CustomLocation"
              formControlName="scopeId"
              placeholder="All locations"
              [allowClear]="true"
              [options]="locationOptions()"
              data-testid="report-custom-location"
            />
          </af-form-field>
        } @else {
          <af-form-field label="Person" for="CustomPerson">
            <af-select
              inputId="CustomPerson"
              formControlName="scopeId"
              placeholder="All persons"
              [allowClear]="true"
              [options]="personOptions()"
              data-testid="report-custom-person"
            />
          </af-form-field>
        }

        <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
          <af-button htmlType="button" (clicked)="router.navigateByUrl('/flightreports')">
            Cancel
          </af-button>
          <af-button
            type="primary"
            htmlType="submit"
            [disabled]="form.invalid"
            data-testid="report-custom-apply"
          >
            Apply
          </af-button>
        </div>
      </form>
    </af-page>
  `,
})
export class ReportCustomBuilderPage {
  protected readonly store = inject(ReportStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  private readonly routeParams = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly category = computed(() => this.routeParams().get('category') ?? 'person');

  protected readonly pageTitle = computed(() =>
    this.category() === 'location' ? 'Custom location report' : 'Custom person report',
  );

  protected readonly form: CustomBuilderForm = this.fb.group({
    from: this.fb.nonNullable.control('', [Validators.required]),
    to: this.fb.nonNullable.control('', [Validators.required]),
    // Legacy defaults (corrected): Glider + Motor on, Tow off (J-7 § Parity decisions).
    glider: this.fb.nonNullable.control(true),
    motor: this.fb.nonNullable.control(true),
    tow: this.fb.nonNullable.control(false),
    scopeId: this.fb.nonNullable.control(''),
  });

  // As-you-type inline validation (J-6b `liveFieldErrors`, debounced ~200ms),
  // not the touched-only `ctl.touched ? ctl.errors : null` wiring.
  protected readonly fromErrors = liveFieldErrors(this.form.controls.from);
  protected readonly toErrors = liveFieldErrors(this.form.controls.to);

  protected readonly locationOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.locationOptions().map((l) => ({ value: l.id, label: locationLabel(l) })),
  );
  protected readonly personOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.store.personOptions().map((p) => ({ value: p.id, label: personLabel(p) })),
  );

  constructor() {
    // Fetch the selector option data the category needs (store owns the HTTP).
    effect(() => {
      if (this.category() === 'location') {
        this.store.loadLocationOptions();
      } else {
        this.store.loadPersonOptions();
      }
    });

    // Seed the form from an existing `:filter` (re-editing an applied filter).
    const seeded = decodeCustomFilter(this.routeParams().get('filter'));
    if (seeded) this.form.patchValue(filterToForm(seeded), { emitEvent: false });
  }

  protected onApply(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const filter = formToFilter(this.form.getRawValue(), this.category());
    const encoded = encodeCustomFilter(filter);
    // Navigate by URL string (not a segments array) so the already
    // percent-encoded `:filter` segment is not re-encoded by the router — the
    // route then decodes it once and `decodeCustomFilter` reads it back.
    void this.router.navigateByUrl(`/flightreports/custom/${this.category()}/${encoded}/apply`);
  }
}

/** Person picker option shape (the subset the label needs). */
interface PersonOption {
  readonly id: string;
  readonly firstname: string;
  readonly lastname: string;
}
/** Location picker option shape (the subset the label needs). */
interface LocationOption {
  readonly id: string;
  readonly locationName: string;
  readonly icaoCode?: string;
}

function personLabel(p: PersonOption): string {
  return `${p.firstname} ${p.lastname}`.trim() || p.id;
}
function locationLabel(l: LocationOption): string {
  const name = l.locationName || l.id;
  return l.icaoCode ? `${name} (${l.icaoCode})` : name;
}

/** Inverse of `formToFilter`: seed the form from a decoded filter. */
function filterToForm(filter: FlightReportSearchFilter): Partial<{
  from: string;
  to: string;
  glider: boolean;
  motor: boolean;
  tow: boolean;
  scopeId: string;
}> {
  return {
    from: filter.flightDateFrom ?? '',
    to: filter.flightDateTo ?? '',
    glider: filter.gliderFlights ?? true,
    motor: filter.motorFlights ?? true,
    tow: filter.towFlights ?? false,
    scopeId: filter.locationId ?? filter.flightCrewPersonId ?? '',
  };
}
