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
import { rangeArray, toRangeValue } from './date-value-bridge';

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
  // ng-zorro's nz-date-picker defaults to inline-block + intrinsic width;
  // when consumers place the host in a grid cell they expect the input to
  // fill the cell (parity with af-select). The host class + the wide
  // descendant width selector achieve that without leaking into other
  // consumers — `:host` ensures the override is scoped.
  host: { class: 'block w-full af-date-picker-host' },
  template: `
    @if (mode() === 'range') {
      <nz-range-picker
        class="w-full"
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

  protected readonly singleValue = computed<Date | null>(() => {
    const v = this.value();
    return v instanceof Date ? v : null;
  });

  // Reference-stable array projection for nz-range-picker's [ngModel]. Under
  // zoneless Angular a fresh-array-each-pass input made the picker re-normalise
  // and re-schedule CD forever, freezing the main thread (S-062e). `rangeArray`
  // returns the SAME array across passes whenever the epochs are unchanged, so
  // the picker input identity is stable and the loop can never form. The
  // `computed` memoises within a pass; the `prev` carry memoises across them.
  #prevRange: readonly Date[] = [];
  protected readonly rangeValue = computed<readonly Date[]>(() => {
    this.#prevRange = rangeArray(this.value(), this.#prevRange);
    return this.#prevRange;
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
    // disabled input wins
  }

  protected onSingleChange(next: Date | null): void {
    this.value.set(next);
    this.onChange(next);
    this.onTouched();
  }
  protected onRangeChange(next: readonly (Date | null)[]): void {
    const tuple = toRangeValue(next);
    this.value.set(tuple);
    this.onChange(tuple);
    this.onTouched();
  }
}
