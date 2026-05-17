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
import { RecentlyUsedService } from '../../recency';
import { fuzzyFilter } from './fuzzy-filter';

/**
 * Searchable single-select with a "Recently used" group at the top.
 *
 * Built on `nz-select [nzShowSearch]` so chip-style selection + multi-select
 * fallback are available later. The recency group is rendered by ordering
 * the option array: recent ids first, the rest after.
 *
 * Recency is keyed by `primitiveKey` (e.g. `'aircraft'`, `'pilot'`); the
 * `RecentlyUsedService` allowlists localStorage in shared/ui/recency/.
 */
@Component({
  selector: 'af-autocomplete',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzSelectModule, FormsModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => AfAutocompleteComponent),
      multi: true,
    },
  ],
  template: `
    <nz-select
      [nzSize]="nzSize()"
      [nzPlaceHolder]="placeholder()"
      [nzShowSearch]="true"
      [nzServerSearch]="false"
      [nzAllowClear]="true"
      [nzDisabled]="disabled()"
      [nzFilterOption]="filterOption"
      (nzOpenChange)="onOpenChange($event)"
      [ngModel]="selectedId()"
      (ngModelChange)="onSelect($event)"
    >
      @if (recentGroup().length > 0) {
        <nz-option-group nzLabel="Recently used">
          @for (item of recentGroup(); track itemId(item)) {
            <nz-option [nzValue]="itemId(item)" [nzLabel]="labelFn()(item)" />
          }
        </nz-option-group>
        <nz-option-group nzLabel="All">
          @for (item of otherGroup(); track itemId(item)) {
            <nz-option [nzValue]="itemId(item)" [nzLabel]="labelFn()(item)" />
          }
        </nz-option-group>
      } @else {
        @for (item of items(); track itemId(item)) {
          <nz-option [nzValue]="itemId(item)" [nzLabel]="labelFn()(item)" />
        }
      }
    </nz-select>
  `,
  styles: [
    `
      :host {
        display: block;
      }
    `,
  ],
})
export class AfAutocompleteComponent<
  T extends { id: string | number },
> implements ControlValueAccessor {
  readonly #density = inject(DensityService);
  readonly #recency = inject(RecentlyUsedService);

  readonly primitiveKey = input.required<string>();
  readonly items = input.required<readonly T[]>();
  readonly searchFields = input.required<readonly (keyof T)[]>();
  readonly labelFn = input<(item: T) => string>((item) =>
    String((item as unknown as { name?: string }).name ?? item.id),
  );
  readonly recent = input<boolean>(true);
  readonly recentWindowDays = input<number>(7);
  readonly placeholder = input<string>('');
  readonly disabled = input<boolean>(false);
  readonly value = model<T | null>(null);

  protected readonly nzSize = computed(() =>
    this.#density.density() === 'dense' ? ('small' as const) : ('default' as const),
  );

  readonly #recentIds = signal<readonly string[]>([]);

  protected readonly recentGroup = computed(() => {
    if (!this.recent()) return [];
    const recentSet = new Set(this.#recentIds());
    if (recentSet.size === 0) return [];
    const order = new Map(this.#recentIds().map((id, i) => [id, i] as const));
    return this.items()
      .filter((item) => recentSet.has(String(item.id)))
      .sort((a, b) => (order.get(String(a.id)) ?? 0) - (order.get(String(b.id)) ?? 0));
  });

  protected readonly otherGroup = computed(() => {
    if (!this.recent()) return this.items();
    const recentSet = new Set(this.#recentIds());
    return this.items().filter((item) => !recentSet.has(String(item.id)));
  });

  protected readonly selectedId = computed(() => {
    const v = this.value();
    return v ? String(v.id) : null;
  });

  protected itemId(item: T): string {
    return String(item.id);
  }

  protected filterOption = (
    input: string,
    option: { nzLabel: string | number | null },
  ): boolean => {
    const q = input.trim().toLowerCase();
    if (!q) return true;
    if (typeof option.nzLabel === 'string' && option.nzLabel.toLowerCase().includes(q)) {
      return true;
    }
    return false;
  };

  protected onOpenChange(open: boolean): void {
    if (!open || !this.recent()) return;
    this.#recentIds.set(this.#recency.recent(this.primitiveKey(), this.recentWindowDays()));
  }

  protected onSelect(id: string | null): void {
    if (id === null) {
      this.value.set(null);
      this.onChange(null);
      return;
    }
    const picked = this.items().find((item) => String(item.id) === id) ?? null;
    this.value.set(picked);
    this.onChange(picked);
    if (picked && this.recent()) {
      this.#recency.record(this.primitiveKey(), String(picked.id));
    }
  }

  /**
   * Pure-function search helper exposed for testability and future server-
   * side wiring; the in-template `[nzFilterOption]` uses nz-select's own
   * filter today.
   */
  filter(query: string): readonly T[] {
    return fuzzyFilter(this.items(), this.searchFields(), query);
  }

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
    // disabled input wins
  }
}
