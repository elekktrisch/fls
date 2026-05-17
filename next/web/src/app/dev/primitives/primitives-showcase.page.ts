import { JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { FormsModule, ReactiveFormsModule, FormControl, Validators } from '@angular/forms';

import { AfButtonComponent } from '../../shared/ui/atoms/af-button';
import { AfInputComponent } from '../../shared/ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '../../shared/ui/atoms/af-select';
import { AfDensityProviderDirective } from '../../shared/ui/density';
import { AfFieldErrorsComponent } from '../../shared/ui/molecules/af-field-errors';
import { AfFormFieldComponent } from '../../shared/ui/molecules/af-form-field';
import { AfAutocompleteComponent } from '../../shared/ui/organisms/af-autocomplete';
import { AfDataTableComponent } from '../../shared/ui/organisms/af-data-table';
import { AfDatePickerComponent, type DateValue } from '../../shared/ui/organisms/af-date-picker';

interface Aircraft {
  readonly id: string;
  readonly immatriculation: string;
  readonly name: string;
}

const AIRCRAFT: readonly Aircraft[] = [
  { id: '1', immatriculation: 'HB-PCD', name: 'Piper PA-28' },
  { id: '2', immatriculation: 'HB-CHA', name: 'Cessna 152' },
  { id: '3', immatriculation: 'HB-GLD', name: 'ASK-21' },
  { id: '4', immatriculation: 'HB-MOT', name: 'Robin DR-400' },
];

const COUNTRIES: readonly AfSelectOption<string>[] = [
  { value: 'CH', label: 'Switzerland' },
  { value: 'AT', label: 'Austria' },
  { value: 'DE', label: 'Germany' },
  { value: 'FR', label: 'France' },
  { value: 'IT', label: 'Italy' },
];

@Component({
  selector: 'af-primitives-showcase',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    JsonPipe,
    FormsModule,
    ReactiveFormsModule,
    AfDensityProviderDirective,
    AfButtonComponent,
    AfInputComponent,
    AfSelectComponent,
    AfFormFieldComponent,
    AfFieldErrorsComponent,
    AfDataTableComponent,
    AfDatePickerComponent,
    AfAutocompleteComponent,
  ],
  template: `
    <main [afDensityProvider]="density()" class="af-showcase">
      <header>
        <h1>AlpenFlight primitives showcase</h1>
        <p>
          Visual probe for every <code>af-*</code> wrapper. Resize the viewport to verify the
          dense-desktop (≥1024) and comfortable-mobile (&lt;1024) behaviours. Toggle density below
          to force-pin either mode.
        </p>
        <af-button (clicked)="toggleDensity()">Density: {{ density() }}</af-button>
      </header>

      <section>
        <h2>Atoms</h2>
        <div class="af-row">
          <af-button type="primary">Primary</af-button>
          <af-button>Default</af-button>
          <af-button type="default" [danger]="true">Danger</af-button>
          <af-button [disabled]="true">Disabled</af-button>
          <af-button [loading]="true">Loading</af-button>
        </div>
        <div class="af-row">
          <af-input [(value)]="text" placeholder="Type something" />
          <af-input type="time" [(value)]="time" />
          <af-input type="date" [(value)]="date" />
        </div>
        <div class="af-row">
          <af-select [options]="countries" [(value)]="country" placeholder="Pick a country" />
        </div>
      </section>

      <section>
        <h2>Molecules</h2>
        <af-form-field
          label="Email"
          for="emailField"
          [errors]="emailControl.errors"
          [required]="true"
        >
          <input
            id="emailField"
            class="af-input"
            [formControl]="emailControl"
            type="email"
            autocomplete="email"
          />
        </af-form-field>
        <af-field-errors [errors]="emailControl.errors" />
      </section>

      <section>
        <h2>Organisms — af-data-table</h2>
        <af-data-table [items]="aircraft" [showPagination]="true">
          <ng-template #primary let-a>{{ a.immatriculation }} — {{ a.name }}</ng-template>
          <ng-template #secondary let-a>id: {{ a.id }}</ng-template>
          <ng-template #meta let-a>{{ a.name | json }}</ng-template>
        </af-data-table>
      </section>

      <section>
        <h2>Organisms — af-date-picker (range)</h2>
        <af-date-picker mode="range" [rangePlaceholders]="['From', 'To']" [(value)]="range" />
        <pre>{{ range() | json }}</pre>
      </section>

      <section>
        <h2>Organisms — af-autocomplete (with recency)</h2>
        <af-autocomplete
          primitiveKey="aircraft"
          [items]="aircraft"
          [searchFields]="['immatriculation', 'name']"
          [labelFn]="aircraftLabel"
          placeholder="Pick an aircraft"
          [(value)]="picked"
        />
        <pre>picked: {{ picked() | json }}</pre>
      </section>

      <section>
        <h2>af-nav-bar</h2>
        <p>The full nav bar is mounted at the app root; visit any route to see it.</p>
      </section>
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
        container-type: inline-size;
      }
      .af-showcase {
        padding: 1rem;
        display: flex;
        flex-direction: column;
        gap: 2rem;
      }
      section {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }
      .af-row {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        align-items: center;
      }
      .af-input {
        border: 1px solid var(--ant-border-color-base, #d9d9d9);
        padding: 0.375rem 0.75rem;
        border-radius: var(--radius-md);
      }
      h1,
      h2 {
        margin: 0;
      }
      pre {
        background: #f6f8fa;
        padding: 0.5rem;
        border-radius: var(--radius-md);
        font-size: 0.75rem;
      }
    `,
  ],
})
export default class PrimitivesShowcasePage {
  protected readonly density = signal<'comfortable' | 'dense' | ''>('');

  protected readonly aircraft = AIRCRAFT;
  protected readonly countries = COUNTRIES;

  protected text = signal('');
  protected time = signal('');
  protected date = signal('');
  protected country = signal<string | null>(null);
  protected range = signal<DateValue>(null);
  protected picked = signal<Aircraft | null>(null);

  protected readonly emailControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.email],
  });

  protected readonly aircraftLabel = (a: Aircraft): string => `${a.immatriculation} — ${a.name}`;

  protected toggleDensity(): void {
    const current = this.density();
    if (current === '') this.density.set('comfortable');
    else if (current === 'comfortable') this.density.set('dense');
    else this.density.set('');
  }
}
