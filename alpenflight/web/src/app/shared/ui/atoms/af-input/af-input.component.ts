import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  forwardRef,
  inject,
  input,
  model,
  viewChild,
} from '@angular/core';
import { NG_VALUE_ACCESSOR, type ControlValueAccessor } from '@angular/forms';

/**
 * Native `<input>` wrapper. AC-DIR-9 keeps native types — `type="time"`,
 * `type="date"`, `inputmode="numeric"` — over ng-zorro custom widgets so the
 * mobile system pickers fire and Reactive-Forms integration stays simple.
 *
 * For richer date/time UIs use `<af-date-picker>` (the only ng-zorro picker
 * the kit pulls in).
 */
@Component({
  selector: 'af-input',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => AfInputComponent),
      multi: true,
    },
  ],
  host: { class: 'block' },
  template: `
    <input
      #inputEl
      class="w-full h-11 px-3 bg-white border border-slate-300 text-slate-900 placeholder:text-slate-400 focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-[1px] aria-invalid:border-red-600 disabled:bg-slate-50 disabled:text-slate-400"
      [attr.id]="inputId() || null"
      [type]="type()"
      [attr.inputmode]="inputmode()"
      [attr.autocomplete]="autocomplete()"
      [placeholder]="placeholder()"
      [readonly]="readonly()"
      [disabled]="disabled()"
      [value]="value()"
      [attr.aria-invalid]="ariaInvalid() ? 'true' : null"
      [attr.aria-describedby]="ariaDescribedby()"
      (input)="onInput($event)"
      (blur)="onTouched()"
    />
  `,
})
export class AfInputComponent implements ControlValueAccessor {
  private readonly inputEl = viewChild.required<ElementRef<HTMLInputElement>>('inputEl');
  protected readonly _elementRef = inject(ElementRef);

  /**
   * Stamped onto the inner native `<input>` so a sibling `<label for="X">`
   * can target the actual focusable element, not the `<af-input>` host.
   */
  readonly inputId = input<string>('');

  readonly type = input<'text' | 'number' | 'email' | 'tel' | 'time' | 'date' | 'password'>('text');
  readonly inputmode = input<'text' | 'numeric' | 'decimal' | 'tel' | 'email' | 'url' | null>(null);
  readonly autocomplete = input<string | null>(null);
  readonly placeholder = input<string>('');
  readonly readonly = input<boolean>(false);
  readonly disabled = input<boolean>(false);
  readonly ariaInvalid = input<boolean>(false);
  readonly ariaDescribedby = input<string | null>(null);

  readonly value = model<string>('');

  private onChange: (value: string) => void = () => undefined;
  protected onTouched: () => void = () => undefined;

  writeValue(value: string | null | undefined): void {
    this.value.set(value ?? '');
  }
  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
  setDisabledState(): void {
    // The disabled input wins; CVA-driven disable is a no-op here.
  }

  protected onInput(event: Event): void {
    const next = (event.target as HTMLInputElement).value;
    this.value.set(next);
    this.onChange(next);
  }

  focus(): void {
    this.inputEl().nativeElement.focus();
  }
}
