import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';

import { ClubsStore } from '../clubs.store';

type ClubForm = FormGroup<{
  name: FormControl<string>;
  slug: FormControl<string>;
  clubKey: FormControl<string>;
  publicRegistrationEnabled: FormControl<boolean>;
}>;

@Component({
  selector: 'af-clubs-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, AfFormFieldComponent, AfInputComponent, AfButtonComponent],
  template: `
    <header class="af-clubs-edit-header">
      <h1>{{ isCreate() ? 'New club' : 'Edit club' }}</h1>
    </header>

    @if (store.saveError(); as err) {
      <p class="af-clubs-error" data-testid="clubs-save-error">{{ err }}</p>
    }

    <form [formGroup]="form" (ngSubmit)="onSubmit()" data-testid="clubs-edit-form" novalidate>
      <af-form-field
        label="Name"
        for="clubName"
        [required]="true"
        [errors]="form.controls.name.touched ? form.controls.name.errors : null"
      >
        <af-input id="clubName" formControlName="name" autocomplete="off" />
      </af-form-field>

      <af-form-field
        label="Slug"
        for="clubSlug"
        [required]="true"
        [errors]="form.controls.slug.touched ? form.controls.slug.errors : null"
      >
        <af-input
          id="clubSlug"
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
          <af-input id="clubKey" formControlName="clubKey" autocomplete="off" />
        </af-form-field>
      }

      <label class="af-clubs-checkbox">
        <input type="checkbox" formControlName="publicRegistrationEnabled" />
        Enable public registration
      </label>

      <div class="af-clubs-actions">
        <af-button htmlType="button" (clicked)="router.navigateByUrl('/clubs')"> Cancel </af-button>
        <af-button
          type="primary"
          htmlType="submit"
          [disabled]="form.invalid"
          data-testid="clubs-save-button"
        >
          Save
        </af-button>
      </div>
    </form>
  `,
  styles: [
    `
      :host {
        display: block;
        padding: var(--space-row);
        max-width: 32rem;
      }
      .af-clubs-edit-header {
        margin-bottom: 1rem;
      }
      .af-clubs-error {
        color: var(--ant-error-color);
        margin-bottom: 0.75rem;
      }
      .af-clubs-checkbox {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin: 0.5rem 0 1rem;
      }
      .af-clubs-actions {
        display: flex;
        gap: 0.5rem;
        justify-content: flex-end;
      }
    `,
  ],
})
export class ClubsEditPage {
  protected readonly store = inject(ClubsStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly clubId = computed(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.clubId() === null);

  protected readonly form: ClubForm = this.fb.group({
    name: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(100)]),
    slug: this.fb.nonNullable.control('', [
      Validators.required,
      Validators.pattern(/^[a-z0-9-]{3,64}$/),
    ]),
    clubKey: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(10)]),
    publicRegistrationEnabled: this.fb.nonNullable.control(false),
  });

  constructor() {
    effect(() => {
      const id = this.clubId();
      if (!id) {
        this.store.select(null);
        return;
      }
      this.store.select(id);
      const club = this.store.selectedClub();
      if (club) {
        this.form.patchValue({
          name: club.name ?? '',
          slug: club.slug ?? '',
          clubKey: club.clubKey ?? '',
          publicRegistrationEnabled: club.publicRegistrationEnabled ?? false,
        });
        this.form.controls.clubKey.disable({ emitEvent: false });
      }
    });

    effect(() => {
      const err = this.store.saveError();
      if (err && err.includes('already in use')) {
        this.form.controls.slug.setErrors({ duplicate: true });
        this.form.controls.slug.markAsTouched();
      }
    });
  }

  protected onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const id = this.clubId();
    if (id) {
      this.store.update({
        id,
        req: {
          name: value.name,
          slug: value.slug,
          publicRegistrationEnabled: value.publicRegistrationEnabled,
        },
      });
    } else {
      this.store.create(value);
    }
    queueMicrotask(() => {
      if (!this.store.saveError()) {
        this.router.navigateByUrl('/clubs');
      }
    });
  }
}
