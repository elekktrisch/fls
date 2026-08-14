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

import { AfDialogComponent } from '@ui/organisms/af-dialog';

import type { ConflictFieldName, FlightConflict } from './conflict-resolver';

export type ConflictChoice = 'mine' | 'theirs';
export type ConflictResolution = Readonly<Record<ConflictFieldName, ConflictChoice>>;

@Component({
  selector: 'af-flight-conflict-prompt',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfDialogComponent, TranslocoDirective],
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
  readonly conflict = input<FlightConflict | null>(null);

  readonly resolved = output<ConflictResolution>();
  readonly dismissed = output<void>();

  private readonly firstChoice = viewChild<ElementRef<HTMLButtonElement>>('firstChoice');

  private readonly choices = signal<Partial<Record<ConflictFieldName, ConflictChoice>>>({});

  protected fieldLabelKey(name: ConflictFieldName): string {
    return `field.${name}`;
  }

  protected choiceOf(name: ConflictFieldName): ConflictChoice {
    return this.choices()[name] ?? 'mine';
  }

  protected choose(name: ConflictFieldName, choice: ConflictChoice): void {
    this.choices.update((c) => ({ ...c, [name]: choice }));
  }

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
    effect(() => {
      const c = this.conflict();
      if (!c) {
        return;
      }
      this.choices.set({});
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
