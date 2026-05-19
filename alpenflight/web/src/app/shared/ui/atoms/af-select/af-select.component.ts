import {
  ChangeDetectionStrategy,
  Component,
  computed,
  forwardRef,
  inject,
  input,
  model,
} from '@angular/core';
import { FormsModule, NG_VALUE_ACCESSOR, type ControlValueAccessor } from '@angular/forms';
import { NzSelectModule } from 'ng-zorro-antd/select';

import { DensityService } from '../../density';

export interface AfSelectOption<T> {
  readonly value: T;
  readonly label: string;
  readonly disabled?: boolean;
}

@Component({
  selector: 'af-select',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzSelectModule, FormsModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => AfSelectComponent),
      multi: true,
    },
  ],
  host: { class: 'block w-full' },
  template: `
    <nz-select
      class="w-full"
      [nzSize]="nzSize()"
      [nzPlaceHolder]="placeholder()"
      [nzShowSearch]="showSearch()"
      [nzAllowClear]="allowClear()"
      [nzDisabled]="disabled()"
      [ngModel]="value()"
      (ngModelChange)="onModelChange($event)"
    >
      @for (option of options(); track option.value) {
        <nz-option
          [nzValue]="option.value"
          [nzLabel]="option.label"
          [nzDisabled]="option.disabled ?? false"
        />
      }
    </nz-select>
  `,
})
export class AfSelectComponent<T> implements ControlValueAccessor {
  readonly #density = inject(DensityService);

  readonly options = input.required<readonly AfSelectOption<T>[]>();
  readonly placeholder = input<string>('');
  readonly showSearch = input<boolean>(true);
  readonly allowClear = input<boolean>(false);
  readonly disabled = input<boolean>(false);
  readonly value = model<T | null>(null);

  protected readonly nzSize = computed(() =>
    this.#density.density() === 'dense' ? ('small' as const) : ('default' as const),
  );

  private onChange: (value: T | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: T | null | undefined): void {
    this.value.set(value ?? null);
  }
  registerOnChange(fn: (value: T | null) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
  setDisabledState(): void {
    // The disabled input wins.
  }

  protected onModelChange(next: T | null): void {
    this.value.set(next);
    this.onChange(next);
    this.onTouched();
  }
}
