import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ValidationErrors } from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';

import { errorsToAbsoluteTranslationKeys } from './field-errors';

@Component({
  selector: 'af-field-errors',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  host: { class: 'block' },
  template: `
    <ng-container *transloco="let t">
      @for (key of keys(); track key) {
        <span class="block text-sm text-red-600" role="alert">{{ t(key) }}</span>
      }
    </ng-container>
  `,
})
export class AfFieldErrorsComponent {
  readonly errors = input<ValidationErrors | null>(null);
  protected readonly keys = computed(() => errorsToAbsoluteTranslationKeys(this.errors()));
}
