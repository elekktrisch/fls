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

/**
 * Project-wide display format for every date picker (J-6b T-12 — legacy
 * hardcodes `DD.MM.YYYY` everywhere; the new app matches). date-fns tokens,
 * fed to ng-zorro's `[nzFormat]`. The format is *display-only* — the model
 * stays a `Date` round-tripped through `date-value-bridge`, untouched. A
 * consumer that genuinely needs another format can override the `format` input,
 * but the default means no per-call config is required for the common case.
 */
export const DEFAULT_DATE_FORMAT = 'dd.MM.yyyy';

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
  /**
   * Stamped onto the inner ng-zorro control (`nzId`) so a sibling
   * `<label for="X">` targets the actual focusable element, matching the
   * `af-input` convention.
   */
  readonly inputId = input<string>('');
  // Display format (date-fns tokens, → ng-zorro `[nzFormat]`). Defaults to the
  // project-wide DD.MM.YYYY so every consumer renders dates the same without
  // per-call config; overridable for the rare consumer that needs another shape.
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

  // The nz-range-picker's own working model. A `linkedSignal` (not a `computed`)
  // so `onRangeChange` can write the picker's just-emitted value back into it;
  // a `computed` re-projecting the external `value()` every CD pass overwrote a
  // half-typed field with the stale store value mid-keyboard-entry, which reset
  // the typed range and left the controls unusable. The source resyncs the
  // picker whenever `value()` changes externally (e.g. the store's clear-filters
  // reset to today).
  //
  // Reference stability is preserved (S-062e): under zoneless Angular a
  // fresh-array-each-pass input made the picker re-normalise and re-schedule CD
  // forever, freezing the main thread. `rangeArray` returns the SAME array
  // whenever the epochs are unchanged; `equal` by identity stops a same-epoch
  // recompute from notifying, so the picker's input is stable and the busy-loop
  // can never form.
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
    // disabled input wins
  }

  protected onSingleChange(next: Date | null): void {
    this.value.set(next);
    this.onChange(next);
    this.onTouched();
  }
  protected onRangeChange(next: readonly (Date | null)[]): void {
    const tuple = toRangeValue(next);
    // Keep the picker's working model in lockstep with what it just emitted so a
    // subsequent CD pass re-reads this value (not the stale external one) and the
    // half-typed field survives. Reference-stabilised against the prior array to
    // preserve the S-062e deadlock guard.
    this.rangeValue.set(rangeArray(tuple, this.rangeValue()));
    this.value.set(tuple);
    this.onChange(tuple);
    this.onTouched();
  }
}
