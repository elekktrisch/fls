import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { NzButtonModule, type NzButtonShape, type NzButtonType } from 'ng-zorro-antd/button';

import { DensityService } from '../../density';

@Component({
  selector: 'af-button',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzButtonModule],
  template: `
    <button
      nz-button
      [nzType]="type()"
      [nzShape]="shape()"
      [nzSize]="nzSize()"
      [nzLoading]="loading()"
      [nzDanger]="danger()"
      [disabled]="disabled()"
      [attr.aria-label]="ariaLabel()"
      [type]="htmlType()"
      (click)="clicked.emit($event)"
    >
      <ng-content />
    </button>
  `,
})
export class AfButtonComponent {
  readonly #density = inject(DensityService);

  readonly type = input<NzButtonType>('default');
  readonly shape = input<NzButtonShape>(null);
  readonly loading = input<boolean>(false);
  readonly danger = input<boolean>(false);
  readonly disabled = input<boolean>(false);
  readonly htmlType = input<'button' | 'submit' | 'reset'>('button');
  readonly ariaLabel = input<string | null>(null);

  readonly clicked = output<MouseEvent>();

  protected readonly nzSize = computed(() =>
    this.#density.density() === 'dense' ? ('small' as const) : ('default' as const),
  );
}
