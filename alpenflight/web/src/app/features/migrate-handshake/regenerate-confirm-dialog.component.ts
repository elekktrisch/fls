import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { DialogRef } from '@angular/cdk/dialog';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Two-button confirm shown before {@code POST /handshake} when the user
 * clicks Regenerate. The body warns that the prior PEM (if already
 * pasted into the export tool) will be invalidated — per the security
 * plan + acceptance criteria.
 *
 * <p>Wired via CDK {@link Dialog} (a11y + focus-trap; project pattern
 * per alpenflight/web/CLAUDE.md §5). Returns {@code true} on confirm,
 * {@code false} (default) on cancel / escape / backdrop click.
 */
@Component({
  selector: 'af-regenerate-confirm-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  host: { class: 'block' },
  template: `
    <div
      class="bg-white border border-slate-200 max-w-md w-[min(28rem,calc(100vw-2rem))] p-6"
      role="dialog"
      aria-labelledby="regenerate-confirm-title"
      aria-describedby="regenerate-confirm-message"
    >
      <ng-container *transloco="let t; read: 'migrateHandshake.regenerateConfirm'">
        <h2
          id="regenerate-confirm-title"
          class="text-lg font-medium text-slate-900 mb-2"
          data-testid="regenerate-confirm-title"
        >
          {{ t('title') }}
        </h2>
        <p
          id="regenerate-confirm-message"
          class="text-sm text-slate-600 mb-6"
          data-testid="regenerate-confirm-message"
        >
          {{ t('message') }}
        </p>
        <div class="flex flex-wrap gap-3 justify-end">
          <button
            type="button"
            class="min-h-[44px] min-w-[44px] px-4 py-2 text-sm border border-slate-300 text-slate-700"
            data-testid="regenerate-confirm-cancel"
            (click)="dismiss(false)"
          >
            {{ t('cancel') }}
          </button>
          <button
            type="button"
            class="min-h-[44px] min-w-[44px] px-4 py-2 text-sm bg-brand-500 text-white"
            data-testid="regenerate-confirm-confirm"
            (click)="dismiss(true)"
          >
            {{ t('confirm') }}
          </button>
        </div>
      </ng-container>
    </div>
  `,
})
export class RegenerateConfirmDialogComponent {
  readonly #dialogRef = inject<DialogRef<boolean>>(DialogRef);

  dismiss(result: boolean): void {
    this.#dialogRef.close(result);
  }
}
