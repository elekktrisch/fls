import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { ValidationErrors } from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';

import { AfFieldErrorsComponent } from '../af-field-errors';

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
