import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { ValidationErrors } from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';

import { AfFieldErrorsComponent } from '../af-field-errors';

/**
 * Label + content + errors wrapper.
 *
 * **Label association convention:** the consumer is responsible for matching
 * the `[for]` input with the projected input's `id` attribute. Example:
 *
 *   <af-form-field label="Email" for="emailField" [required]="true" [errors]="ctl.errors">
 *     <input id="emailField" [formControl]="ctl" type="email" />
 *   </af-form-field>
 *
 * Auto-wiring (querying the projected content + stamping an id) is not done
 * today; the first feature consumer (S-049 Locations CRUD with mocked auth,
 * per operator suggestion) can drive an auto-wire directive if the manual
 * convention turns out to bite.
 */

@Component({
  selector: 'af-form-field',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzFormModule, AfFieldErrorsComponent],
  template: `
    <nz-form-item>
      @if (label()) {
        <nz-form-label [nzRequired]="required()" [nzFor]="for()">
          {{ label() }}
        </nz-form-label>
      }
      <nz-form-control [nzErrorTip]="errorTip">
        <ng-content />
      </nz-form-control>
      <ng-template #errorTip>
        <af-field-errors [errors]="errors()" />
      </ng-template>
    </nz-form-item>
  `,
})
export class AfFormFieldComponent {
  readonly label = input<string>('');
  readonly for = input<string>('');
  readonly required = input<boolean>(false);
  readonly errors = input<ValidationErrors | null>(null);
}
