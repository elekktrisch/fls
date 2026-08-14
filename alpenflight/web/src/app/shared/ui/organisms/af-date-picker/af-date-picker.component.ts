import {
  ChangeDetectionStrategy,
  Component,
  computed,
  forwardRef,
  inject,
  input,
  linkedSignal,
  model,
} from '@angular/core';
import { FormsModule, NG_VALUE_ACCESSOR, type ControlValueAccessor } from '@angular/forms';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';

import { DensityService } from '../../density';
import { rangeArray, toRangeValue } from './date-value-bridge';

export type DateValue = Date | [Date, Date] | null;

export const DEFAULT_DATE_FORMAT = 'dd.MM.yyyy';

@Component({
  selector: 'af-date-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzDatePickerModule, FormsModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => AfDatePickerComponent),
      multi: true,
    },
  ],
  host: { class: 'block w-full af-date-picker-host' },
  template: `
    @if (mode() === 'range') {
      <nz-range-picker
        class="w-full"
        [nzId]="inputId() || null"
        [nzFormat]="format()"
        [nzSize]="nzSize()"
        [nzDisabled]="disabled()"
        [nzAllowClear]="allowClear()"
        [nzPlaceHolder]="rangePlaceholders()"
        [ngModel]="rangeValue()"
        (ngModelChange)="onRangeChange($event)"
      />
    } @else {
      <nz-date-picker
        class="w-full"
        [nzId]="inputId() || null"
        [nzFormat]="format()"
        [nzSize]="nzSize()"
        [nzDisabled]="disabled()"
        [nzAllowClear]="allowClear()"
        [nzPlaceHolder]="placeholder()"
        [ngModel]="singleValue()"
        (ngModelChange)="onSingleChange($event)"
      />
    }
  `,
})
export class AfDatePickerComponent implements ControlValueAccessor {
  readonly #density = inject(DensityService);

  readonly mode = input<'single' | 'range'>('single');
  readonly inputId = input<string>('');
  readonly format = input<string>(DEFAULT_DATE_FORMAT);
  readonly placeholder = input<string>('');
  readonly rangePlaceholders = input<[string, string]>(['', '']);
  readonly allowClear = input<boolean>(true);
  readonly disabled = input<boolean>(false);
  readonly value = model<DateValue>(null);

  protected readonly nzSize = computed(() =>
    this.#density.density() === 'dense' ? ('small' as const) : ('default' as const),
  );

  protected readonly singleValue = computed<Date | null>(() => {
    const v = this.value();
    return v instanceof Date ? v : null;
  });

  protected readonly rangeValue = linkedSignal<DateValue, readonly Date[]>({
    source: () => this.value(),
    computation: (value, prev) => rangeArray(value, prev?.value ?? []),
    equal: (a, b) => a === b,
  });

  private onChange: (value: DateValue) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: DateValue): void {
    this.value.set(value ?? null);
  }
  registerOnChange(fn: (value: DateValue) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
  setDisabledState(): void {
    return;
  }

  protected onSingleChange(next: Date | null): void {
    this.value.set(next);
    this.onChange(next);
    this.onTouched();
  }
  protected onRangeChange(next: readonly (Date | null)[]): void {
    const tuple = toRangeValue(next);
    this.rangeValue.set(rangeArray(tuple, this.rangeValue()));
    this.value.set(tuple);
    this.onChange(tuple);
    this.onTouched();
  }
}
