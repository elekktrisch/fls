import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ValidationErrors } from '@angular/forms';

import { errorsToKeys } from './field-errors';

@Component({
  selector: 'af-field-errors',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @for (key of keys(); track key) {
      <span class="block text-sm text-red-600" role="alert">{{ key }}</span>
    }
  `,
})
export class AfFieldErrorsComponent {
  readonly errors = input<ValidationErrors | null>(null);
  protected readonly keys = computed(() => errorsToKeys(this.errors()));
}
