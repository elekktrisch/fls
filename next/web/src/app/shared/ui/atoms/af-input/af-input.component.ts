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
  template: `
    <input
      #inputEl
      class="af-input"
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
  styles: [
    `
      :host {
        display: block;
      }
      .af-input {
        width: 100%;
        min-height: var(--row-height);
        padding: 0 0.75rem;
        border: 1px solid var(--ant-border-color-base, #d9d9d9);
        border-radius: var(--radius-md);
        font: inherit;
        background: #fff;
      }
      .af-input:focus-visible {
        outline: 2px solid var(--color-brand-500);
        outline-offset: 1px;
      }
      .af-input[aria-invalid='true'] {
        border-color: var(--ant-error-color);
      }
    `,
  ],
})
export class AfInputComponent implements ControlValueAccessor {
  private readonly inputEl = viewChild.required<ElementRef<HTMLInputElement>>('inputEl');
  protected readonly _elementRef = inject(ElementRef);

  /**
   * Stamped onto the inner native `<input>` so a sibling `<label for="X">`
   * can target the actual focusable element, not the `<af-input>` host. Use
   * this instead of binding `id=` on the `<af-input>` tag itself (which
   * would attach the id to the host but leave the inner input unreachable).
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
