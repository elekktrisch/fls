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
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';

import { DensityService } from '../../density';

export type DateValue = Date | [Date, Date] | null;

/**
 * Wraps `nz-range-picker` (mode="range") or `nz-date-picker` (mode="single").
 * The range mode is the load-bearing case for the flight form (departure +
 * arrival times) per operator. The single mode is for one-off date inputs
 * that want a richer picker than `<af-input type="date">`.
 */
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
  template: `
    @if (mode() === 'range') {
      <nz-range-picker
        [nzSize]="nzSize()"
        [nzDisabled]="disabled()"
        [nzAllowClear]="allowClear()"
        [nzPlaceHolder]="rangePlaceholders()"
        [ngModel]="rangeValue()"
        (ngModelChange)="onRangeChange($event)"
      />
    } @else {
      <nz-date-picker
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
  readonly placeholder = input<string>('');
  readonly rangePlaceholders = input<[string, string]>(['', '']);
  readonly allowClear = input<boolean>(true);
  readonly disabled = input<boolean>(false);
  readonly value = model<DateValue>(null);

  protected readonly nzSize = computed(() =>
    this.#density.density() === 'dense' ? ('small' as const) : ('default' as const),
  );

  protected singleValue(): Date | null {
    const v = this.value();
    return v instanceof Date ? v : null;
  }
  protected rangeValue(): readonly Date[] {
    const v = this.value();
    return Array.isArray(v) ? v : [];
  }

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
    // disabled input wins
  }

  protected onSingleChange(next: Date | null): void {
    this.value.set(next);
    this.onChange(next);
    this.onTouched();
  }
  protected onRangeChange(next: Date[]): void {
    const tuple: DateValue = next.length === 2 ? [next[0]!, next[1]!] : null;
    this.value.set(tuple);
    this.onChange(tuple);
    this.onTouched();
  }
}
