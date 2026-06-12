import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ValidationErrors } from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';

import { errorsToKeys } from './field-errors';

@Component({
  selector: 'af-field-errors',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  host: { class: 'block' },
  template: `
    <!--
      Unscoped \`*transloco\` (no \`read:\`) — the mapped keys are absolute
      (\`common.errors.*\`, see field-errors.ts), and rendering through the
      directive keeps reRenderOnLangChange working (J-26 T-08: the raw i18n
      key used to render verbatim).
    -->
    <ng-container *transloco="let t">
      @for (key of keys(); track key) {
        <span class="block text-sm text-red-600" role="alert">{{ t(key) }}</span>
      }
    </ng-container>
  `,
})
export class AfFieldErrorsComponent {
  readonly errors = input<ValidationErrors | null>(null);
  protected readonly keys = computed(() => errorsToKeys(this.errors()));
}
