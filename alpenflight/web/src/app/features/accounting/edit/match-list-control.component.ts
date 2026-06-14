import {
  ChangeDetectionStrategy,
  Component,
  computed,
  forwardRef,
  input,
  signal,
} from '@angular/core';
import { NG_VALUE_ACCESSOR, type ControlValueAccessor } from '@angular/forms';

import type { MatchList } from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';

/**
 * Reusable predicate match-list control — the legacy accounting "selectize +
 * include/exclude" panel (`accountingRuleFilters-edit.html`), one of ten.
 *
 * Round-trips a `MatchList` (`{useAllExcept, matched[]}`) via `ControlValueAccessor`
 * so the parent form owns each list as a single control:
 *  - the **invert toggle** (`useAllExcept`) — legacy "nur folgende" (false) vs
 *    "alle ausser" (true): include the listed tokens, or use the rule for ALL
 *    flights *except* the listed ones. This is the load-bearing AC.
 *  - the **matched tokens** as removable chips, added via a typed text input.
 *    The persisted value is always a string (legacy `valueField` ints/guids are
 *    serialised as strings), so typed entry is sufficient for every list; an
 *    optional `options` array drives a `<datalist>` so dropdown-backed lists
 *    (immatriculations, ICAO codes, member numbers, crew-type ids, …) get
 *    suggestions without the control coupling to any reference store.
 *
 * Parameterised by `(label, listKey, options?)` and instantiated N times — one
 * component, no copy-pasted blocks (the CPD/maintainability bar). Per-instance
 * `data-testid`s derive from `listKey`:
 *   `accounting-rules-<listKey>-use-all-except` (the invert toggle)
 *   `accounting-rules-<listKey>-add` / `-add-button`
 *   `accounting-rules-<listKey>-chip-<value>` / `-chip-<value>-remove`
 */
@Component({
  selector: 'af-match-list-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [AfButtonComponent, AfIconComponent],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => MatchListControlComponent),
      multi: true,
    },
  ],
  template: `
    <fieldset class="flex flex-col gap-1.5 border border-slate-200 px-3 py-2">
      <legend class="text-xs font-medium text-slate-600 px-1">{{ label() }}</legend>

      <label class="flex items-center gap-2 cursor-pointer select-none text-sm">
        <input
          type="checkbox"
          class="w-4 h-4 accent-brand-500 cursor-pointer"
          [checked]="useAllExcept()"
          [disabled]="disabled()"
          (change)="onToggle($event)"
          [attr.data-testid]="'accounting-rules-' + listKey() + '-use-all-except'"
        />
        <span>{{ useAllExceptLabel() }}</span>
      </label>

      @if (!disabled()) {
        <div class="flex gap-2 items-stretch">
          <input
            type="text"
            class="flex-1 border border-slate-300 px-2 py-1.5 text-sm bg-white focus:border-brand-500 focus:outline-hidden"
            [value]="draft()"
            (input)="onDraftInput($event)"
            (keydown.enter)="onAddFromEnter($event)"
            [attr.list]="options().length ? listKey() + '-options' : null"
            [attr.placeholder]="addPlaceholder()"
            [attr.data-testid]="'accounting-rules-' + listKey() + '-add'"
            autocomplete="off"
          />
          @if (options().length) {
            <datalist [id]="listKey() + '-options'">
              @for (opt of options(); track opt) {
                <option [value]="opt"></option>
              }
            </datalist>
          }
          <af-button
            htmlType="button"
            (clicked)="addDraft()"
            [disabled]="draft().trim() === ''"
            [attr.data-testid]="'accounting-rules-' + listKey() + '-add-button'"
          >
            <af-icon name="plus" [size]="16" />
          </af-button>
        </div>
      }

      @if (matched().length) {
        <ul class="flex flex-wrap gap-1.5 mt-0.5">
          @for (token of matched(); track token) {
            <li
              class="inline-flex items-center gap-1 border border-slate-300 bg-slate-50 pl-2 pr-1 py-0.5 text-xs"
              [attr.data-testid]="'accounting-rules-' + listKey() + '-chip-' + token"
            >
              <span>{{ token }}</span>
              @if (!disabled()) {
                <button
                  type="button"
                  class="inline-flex items-center text-slate-500 hover:text-slate-800 cursor-pointer"
                  (click)="remove(token)"
                  [attr.aria-label]="removeLabel()"
                  [attr.data-testid]="
                    'accounting-rules-' + listKey() + '-chip-' + token + '-remove'
                  "
                >
                  <af-icon name="x" [size]="14" />
                </button>
              }
            </li>
          }
        </ul>
      } @else {
        <p class="text-xs text-slate-400 mt-0.5">{{ emptyLabel() }}</p>
      }
    </fieldset>
  `,
})
export class MatchListControlComponent implements ControlValueAccessor {
  /** Stable key for this list — drives every `data-testid` + the `<datalist>` id. */
  readonly listKey = input.required<string>();
  /** Display label (legacy panel heading). */
  readonly label = input.required<string>();
  /** Optional suggestion tokens for the typed-entry `<datalist>` (dropdown-backed lists). */
  readonly options = input<readonly string[]>([]);
  /** Caption for the invert toggle ("Use for all except listed"). */
  readonly useAllExceptLabel = input<string>('Use for all flights except those listed');
  readonly addPlaceholder = input<string>('Add…');
  readonly emptyLabel = input<string>('None');
  readonly removeLabel = input<string>('Remove');

  protected readonly useAllExcept = signal<boolean>(true);
  protected readonly matched = signal<readonly string[]>([]);
  protected readonly draft = signal<string>('');
  protected readonly disabled = signal<boolean>(false);

  protected readonly value = computed<MatchList>(() => ({
    useAllExcept: this.useAllExcept(),
    matched: [...this.matched()],
  }));

  protected onToggle(event: Event): void {
    this.useAllExcept.set((event.target as HTMLInputElement).checked);
    this.emit();
  }

  protected onDraftInput(event: Event): void {
    this.draft.set((event.target as HTMLInputElement).value);
  }

  protected onAddFromEnter(event: Event): void {
    event.preventDefault();
    this.addDraft();
  }

  protected addDraft(): void {
    const token = this.draft().trim();
    if (token === '' || this.matched().includes(token)) {
      this.draft.set('');
      return;
    }
    this.matched.update((list) => [...list, token]);
    this.draft.set('');
    this.emit();
  }

  protected remove(token: string): void {
    this.matched.update((list) => list.filter((t) => t !== token));
    this.emit();
  }

  private emit(): void {
    this.onChange(this.value());
    this.onTouched();
  }

  private onChange: (value: MatchList) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: MatchList | null | undefined): void {
    this.useAllExcept.set(value?.useAllExcept ?? true);
    this.matched.set([...(value?.matched ?? [])]);
  }
  registerOnChange(fn: (value: MatchList) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }
}
