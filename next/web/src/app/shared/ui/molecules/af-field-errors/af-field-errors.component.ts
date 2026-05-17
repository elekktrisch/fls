import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ValidationErrors } from '@angular/forms';

import { errorsToKeys } from './field-errors';

@Component({
  selector: 'af-field-errors',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (key of keys(); track key) {
      <span class="af-field-error" role="alert">{{ key }}</span>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .af-field-error {
        color: var(--ant-error-color);
        font-size: 0.875rem;
        display: block;
      }
    `,
  ],
})
export class AfFieldErrorsComponent {
  readonly errors = input<ValidationErrors | null>(null);
  protected readonly keys = computed(() => errorsToKeys(this.errors()));
}
