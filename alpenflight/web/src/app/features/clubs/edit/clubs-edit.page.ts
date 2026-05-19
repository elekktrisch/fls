import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { ReferenceDataStore } from '../../../core/reference-data/reference-data.store';
import { ClubsStore } from '../clubs.store';
import { slugAvailable } from './clubs-edit.validators';

type ClubForm = FormGroup<{
  name: FormControl<string>;
  slug: FormControl<string>;
  clubKey: FormControl<string>;
  publicRegistrationEnabled: FormControl<boolean>;
  countryId: FormControl<string>;
  clubStateId: FormControl<string>;
}>;

@Component({
  selector: 'af-clubs-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    AfFormFieldComponent,
    AfInputComponent,
    AfSelectComponent,
    AfButtonComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
  ],
  host: { class: 'block' },
  template: `
    <af-page mode="narrow">
      <af-page-header [title]="isCreate() ? 'New club' : 'Edit club'" />

      <af-page-error
        [message]="store.saveError()"
        [retryLabel]="null"
        data-testid="clubs-save-error"
      />
      <af-page-error
        [message]="referenceData.loadError() ? 'Reference data unavailable.' : null"
        (retry)="referenceData.loadAll()"
        data-testid="clubs-ref-data-error"
      />

      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        data-testid="clubs-edit-form"
        class="flex flex-col gap-2"
        novalidate
      >
        <af-form-field
          label="Name"
          for="clubName"
          [required]="true"
          [errors]="form.controls.name.touched ? form.controls.name.errors : null"
        >
          <af-input inputId="clubName" formControlName="name" autocomplete="off" />
        </af-form-field>

        <af-form-field
          label="Slug"
          for="clubSlug"
          [required]="true"
          [errors]="form.controls.slug.touched ? form.controls.slug.errors : null"
        >
          <af-input
            inputId="clubSlug"
            formControlName="slug"
            autocomplete="off"
            placeholder="lowercase-with-hyphens"
          />
        </af-form-field>

        @if (isCreate()) {
          <af-form-field
            label="Club key"
            for="clubKey"
            [required]="true"
            [errors]="form.controls.clubKey.touched ? form.controls.clubKey.errors : null"
          >
            <af-input inputId="clubKey" formControlName="clubKey" autocomplete="off" />
          </af-form-field>
        }

        <af-form-field
          label="Country"
          for="countryId"
          [required]="true"
          [errors]="form.controls.countryId.touched ? form.controls.countryId.errors : null"
        >
          <af-select
            inputId="countryId"
            formControlName="countryId"
            placeholder="Select country"
            [showSearch]="true"
            [options]="countryOptions()"
            data-testid="clubs-country-select"
          />
        </af-form-field>

        <af-form-field
          label="Club state"
          for="clubStateId"
          [required]="true"
          [errors]="form.controls.clubStateId.touched ? form.controls.clubStateId.errors : null"
        >
          <af-select
            inputId="clubStateId"
            formControlName="clubStateId"
            placeholder="Select state"
            [options]="clubStateOptions()"
            data-testid="clubs-club-state-select"
          />
        </af-form-field>

        <label class="flex items-center gap-2 cursor-pointer select-none mt-2 mb-4">
          <input
            type="checkbox"
            formControlName="publicRegistrationEnabled"
            class="w-4 h-4 accent-brand-500 cursor-pointer"
          />
          <span>Enable public registration</span>
        </label>

        <div class="flex gap-2 justify-end mt-4 pt-4 border-t border-slate-200">
          <af-button htmlType="button" (clicked)="router.navigateByUrl('/clubs')">
            Cancel
          </af-button>
          <af-button
            type="primary"
            htmlType="submit"
            [disabled]="form.invalid || saveSubmitted()"
            data-testid="clubs-save-button"
          >
            Save
          </af-button>
        </div>
      </form>
    </af-page>
  `,
})
export class ClubsEditPage {
  protected readonly store = inject(ClubsStore);
  protected readonly referenceData = inject(ReferenceDataStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly clubId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.clubId() === null);

  protected readonly countryOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.referenceData.countries().map((c) => ({ value: c.id, label: c.name ?? c.id })),
  );
  protected readonly clubStateOptions = computed<readonly AfSelectOption<string>[]>(() =>
    this.referenceData.clubStates().map((s) => ({ value: s.id, label: s.name ?? s.id })),
  );

  // S-007 — slug validator stack (sync + in-memory async-style) declared
  // alongside the rest of the form definition. The duplicate-check closure
  // reads `this.store.entities()` and `this.clubId()` lazily so it stays
  // current as the store refreshes / the route changes.
  protected readonly form: ClubForm = this.fb.group({
    name: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(100)]),
    slug: this.fb.nonNullable.control('', [
      Validators.required,
      Validators.pattern(/^[a-z0-9-]{3,64}$/),
      slugAvailable({
        entities: () => this.store.entities(),
        currentId: () => this.clubId(),
      }),
    ]),
    clubKey: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(10)]),
    publicRegistrationEnabled: this.fb.nonNullable.control(false),
    countryId: this.fb.nonNullable.control('', [Validators.required]),
    clubStateId: this.fb.nonNullable.control('', [Validators.required]),
  });

  protected readonly saveSubmitted = signal(false);

  constructor() {
    effect(() => {
      // Re-run the slug validator whenever the entity list refreshes so the
      // duplicate flag updates the moment ClubsStore.loadAll() resolves.
      void this.store.entities();
      this.form.controls.slug.updateValueAndValidity({ emitEvent: false });
    });

    effect(() => {
      const id = this.clubId();
      if (!id) {
        this.store.select(null);
        // Re-enable on edit→new navigation; patchValue doesn't reset disabled.
        this.form.controls.clubKey.enable({ emitEvent: false });
        return;
      }
      this.store.select(id);
      const club = this.store.selectedClub();
      if (!club) return;
      // Race guard: countryId / clubStateId must match an option in the
      // <af-select> when patched, otherwise nz-select silently drops the
      // value and the form looks empty + invalid with no cue. Re-fire when
      // ref-data lands.
      const countriesReady = this.referenceData.countries().length > 0;
      const clubStatesReady = this.referenceData.clubStates().length > 0;
      this.form.patchValue({
        name: club.name ?? '',
        slug: club.slug ?? '',
        clubKey: club.clubKey ?? '',
        publicRegistrationEnabled: club.publicRegistrationEnabled ?? false,
        countryId: countriesReady ? club.countryId : '',
        clubStateId: clubStatesReady ? club.clubStateId : '',
      });
      this.form.controls.clubKey.disable({ emitEvent: false });
    });

    // Any save error (409, 500, network) disarms the bus-driven navigation
    // and (for 409 specifically) marks the slug field as duplicate so the
    // user sees the inline error.
    effect(() => {
      const err = this.store.saveError();
      if (!err) return;
      this.saveSubmitted.set(false);
      if (err.includes('already in use')) {
        this.form.controls.slug.setErrors({ duplicate: true });
        this.form.controls.slug.markAsTouched();
      }
    });

    // Navigate on confirmed server success — the store emits club.created /
    // club.updated only inside the rxMethod's tapResponse.next callback,
    // which fires after the HTTP response. Errors disarm saveSubmitted via
    // the effect above so we don't navigate past them.
    const destroyRef = inject(DestroyRef);
    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (evt.kind === 'club.created' || evt.kind === 'club.updated') {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/clubs');
      }
    });
  }

  protected onSubmit(): void {
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const id = this.clubId();
    this.saveSubmitted.set(true);
    if (id) {
      this.store.update({
        id,
        req: {
          name: value.name,
          slug: value.slug,
          publicRegistrationEnabled: value.publicRegistrationEnabled,
          countryId: value.countryId,
          clubStateId: value.clubStateId,
        },
      });
    } else {
      this.store.create(value);
    }
  }
}
