import {
  ChangeDetectionStrategy,
  Component,
  computed,
  forwardRef,
  inject,
  input,
  model,
  signal,
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
      class="w-full focus:border-brand-500 focus:outline-hidden"
      [nzId]="inputId() || null"
      [nzSize]="nzSize()"
      [nzPlaceHolder]="placeholder()"
      [nzShowSearch]="showSearch()"
      [nzAllowClear]="allowClear()"
      [nzDisabled]="isDisabled()"
      [ngModel]="value()"
      (ngModelChange)="onModelChange($event)"
    >
      @for (option of options(); track option.value) {
        <nz-option
          [nzValue]="option.value"
          [nzLabel]="option.label"
          [nzDisabled]="option.disabled ?? false"
          [nzCustomContent]="true"
        >
          <!--
            Custom content renders into the CDK-overlay option-item, so the
            data-testid lives on a node Playwright can target directly with
            getByTestId() — no ant-select-dropdown:not(.hidden) gymnastics.
            Value is coerced to string for the testid; specs pass the same
            string they bind on the model.
          -->
          <span [attr.data-testid]="'af-select-option-' + option.value">{{ option.label }}</span>
        </nz-option>
      }
    </nz-select>
  `,
})
export class AfSelectComponent<T> implements ControlValueAccessor {
  readonly #density = inject(DensityService);

  readonly options = input.required<readonly AfSelectOption<T>[]>();

  /**
   * Stamped onto the inner ng-zorro control (`nzId`) so a sibling
   * `<label for="X">` targets the actual focusable element, matching the
   * `af-input` convention.
   */
  readonly inputId = input<string>('');
  readonly placeholder = input<string>('');
  readonly showSearch = input<boolean>(true);
  readonly allowClear = input<boolean>(false);
  readonly disabled = input<boolean>(false);
  readonly value = model<T | null>(null);

  /**
   * CVA-driven disabled state (set by `form.disable()`/`enable()`), OR'd with the
   * explicit `[disabled]` input. A read-only form (`form.disable()`) MUST render
   * its selects non-editable, not just mark the control DISABLED (J-6b T-09: the
   * planning view-mode read-only fix).
   */
  private readonly cvaDisabled = signal(false);
  protected readonly isDisabled = computed(() => this.disabled() || this.cvaDisabled());

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
  setDisabledState(isDisabled: boolean): void {
    this.cvaDisabled.set(isDisabled);
  }

  protected onModelChange(next: T | null): void {
    this.value.set(next);
    this.onChange(next);
    this.onTouched();
  }
}
