import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfDialogComponent } from '@ui/organisms/af-dialog';

import type { ConflictFieldName, FlightConflict } from './conflict-resolver';

/** The user's per-field adjudication of a 412 conflict. */
export type ConflictChoice = 'mine' | 'theirs';
export type ConflictResolution = Readonly<Record<ConflictFieldName, ConflictChoice>>;

/**
 * S-062h — 412 inline per-field conflict-diff prompt.
 *
 * Opens (via the host's `<af-dialog>` overlay) when a stale `If-Match` PUT
 * returns 412. For each conflicting field it shows the value the user
 * submitted ("keep mine") beside the stored value ("keep theirs"); the user
 * picks per field, then explicitly resubmits — there is NO auto-retry (legacy
 * was last-write-wins; this is the net-new affordance).
 *
 * - The FIRST conflicting field's "keep mine" control is focused on open.
 * - Enter activates the focused choice (native button activation — each
 *   choice is a real `<button>`, so a focused one fires on Enter).
 * - Default per field is "keep mine" (the user just typed those values).
 */
@Component({
  selector: 'af-flight-conflict-prompt',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfDialogComponent, AfButtonComponent, TranslocoDirective],
  template: `
    <ng-container *transloco="let t; read: 'flight.conflict'">
      <af-dialog
        [visible]="conflict() !== null"
        [wide]="true"
        [title]="t('title')"
        [message]="t('intro')"
        [confirmLabel]="t('resubmit')"
        [dismissLabel]="t('cancel')"
        (confirm)="onResubmit()"
        (dismiss)="dismissed.emit()"
      >
        @if (conflict(); as c) {
          <ul
            class="divide-y divide-slate-200 border-y border-slate-200"
            data-testid="flight-conflict-dialog"
          >
            @for (f of c.fields; track f.name) {
              <li class="py-3" [attr.data-testid]="'flight-conflict-field-' + f.name">
                <p class="mb-2 text-xs font-medium uppercase tracking-wide text-slate-500">
                  {{ t(fieldLabelKey(f.name)) }}
                </p>
                <div class="grid grid-cols-2 gap-2">
                  <button
                    #firstChoice
                    type="button"
                    class="border px-3 py-2 text-left text-sm focus:outline-hidden focus:ring-2 focus:ring-brand-400"
                    [class.border-brand-500]="choiceOf(f.name) === 'mine'"
                    [class.bg-brand-50]="choiceOf(f.name) === 'mine'"
                    [class.border-slate-300]="choiceOf(f.name) !== 'mine'"
                    [attr.aria-pressed]="choiceOf(f.name) === 'mine'"
                    [attr.data-testid]="'flight-conflict-keep-mine-' + f.name"
                    [attr.data-active]="choiceOf(f.name) === 'mine'"
                    (click)="choose(f.name, 'mine')"
                  >
                    <span class="block text-xs text-slate-500">{{ t('keepMine') }}</span>
                    <span class="block text-slate-900">{{ f.mine ?? t('empty') }}</span>
                  </button>
                  <button
                    type="button"
                    class="border px-3 py-2 text-left text-sm focus:outline-hidden focus:ring-2 focus:ring-brand-400"
                    [class.border-brand-500]="choiceOf(f.name) === 'theirs'"
                    [class.bg-brand-50]="choiceOf(f.name) === 'theirs'"
                    [class.border-slate-300]="choiceOf(f.name) !== 'theirs'"
                    [attr.aria-pressed]="choiceOf(f.name) === 'theirs'"
                    [attr.data-testid]="'flight-conflict-keep-theirs-' + f.name"
                    [attr.data-active]="choiceOf(f.name) === 'theirs'"
                    (click)="choose(f.name, 'theirs')"
                  >
                    <span class="block text-xs text-slate-500">{{ t('keepTheirs') }}</span>
                    <span class="block text-slate-900">{{ f.theirs ?? t('empty') }}</span>
                  </button>
                </div>
              </li>
            }
          </ul>
        }
      </af-dialog>
    </ng-container>
  `,
})
export class FlightConflictPromptComponent {
  /** The resolved 412 conflict; `null` keeps the dialog closed. */
  readonly conflict = input<FlightConflict | null>(null);

  /** Emits the user's per-field choices when they explicitly resubmit. */
  readonly resolved = output<ConflictResolution>();
  /** Emits when the user cancels (keeps editing, no resubmit). */
  readonly dismissed = output<void>();

  private readonly firstChoice = viewChild<ElementRef<HTMLButtonElement>>('firstChoice');

  /** Per-field choice; absent = the default ("keep mine"). */
  private readonly choices = signal<Partial<Record<ConflictFieldName, ConflictChoice>>>({});

  /** Transloco key for a field's human label (read under the `flight.conflict` scope). */
  protected fieldLabelKey(name: ConflictFieldName): string {
    return `field.${name}`;
  }

  protected choiceOf(name: ConflictFieldName): ConflictChoice {
    return this.choices()[name] ?? 'mine';
  }

  protected choose(name: ConflictFieldName, choice: ConflictChoice): void {
    this.choices.update((c) => ({ ...c, [name]: choice }));
  }

  /** The full resolution, defaulting every conflicting field to "keep mine". */
  private readonly resolution = computed<ConflictResolution>(() => {
    const c = this.conflict();
    const picked = this.choices();
    const out: Partial<Record<ConflictFieldName, ConflictChoice>> = {};
    for (const f of c?.fields ?? []) {
      out[f.name] = picked[f.name] ?? 'mine';
    }
    return out as ConflictResolution;
  });

  constructor() {
    // Reset choices + focus the first field's "keep mine" control each time a
    // fresh conflict opens (NO auto-retry — the user drives the resubmit).
    effect(() => {
      const c = this.conflict();
      if (!c) {
        return;
      }
      this.choices.set({});
      // The view child resolves after the dialog renders; focus once present.
      const el = this.firstChoice()?.nativeElement;
      el?.focus();
    });
  }

  protected onResubmit(): void {
    if (this.conflict()) {
      this.resolved.emit(this.resolution());
    }
  }
}
