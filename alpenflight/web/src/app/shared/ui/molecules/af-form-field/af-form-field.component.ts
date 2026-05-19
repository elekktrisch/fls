import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { ValidationErrors } from '@angular/forms';

import { AfFieldErrorsComponent } from '../af-field-errors';

/**
 * Label + content + errors wrapper. Vertical stack everywhere — label
 * above the input — so widths align cleanly regardless of field type and
 * the label sits where readers expect.
 *
 *   <af-form-field label="Email" for="emailField" [required]="true" [errors]="ctl.errors">
 *     <input id="emailField" [formControl]="ctl" type="email" />
 *   </af-form-field>
 *
 * Label-association convention: the consumer matches the `[for]` input
 * with the projected input's `id` attribute.
 */
@Component({
  selector: 'af-form-field',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfFieldErrorsComponent],
  host: { class: 'block' },
  template: `
    <div class="flex flex-col gap-1.5 mb-4">
      @if (label()) {
        <label [attr.for]="for() || null" class="text-sm font-medium text-slate-700">
          {{ label() }}
          @if (required()) {
            <span class="text-red-600 ml-0.5" aria-hidden="true">*</span>
          }
        </label>
      }
      <ng-content />
      <af-field-errors [errors]="errors()" />
    </div>
  `,
})
export class AfFormFieldComponent {
  readonly label = input<string>('');
  readonly for = input<string>('');
  readonly required = input<boolean>(false);
  readonly errors = input<ValidationErrors | null>(null);
}
