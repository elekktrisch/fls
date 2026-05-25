import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { AfButtonComponent } from '@ui/atoms/af-button';

/**
 * Minimal confirmation dialog. Consumed by the wizard's Esc-dirty-confirm
 * prompt and reused by S-062h's draft-restore + 412 conflict-diff prompts.
 *
 * The visible-flag is owned by the host so this stays a passive renderer
 * the host can re-locate (overlay vs inline) later without touching the API.
 */
@Component({
  selector: 'af-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent],
  template: `
    @if (visible()) {
      <div
        class="fixed inset-0 z-20 flex items-center justify-center bg-slate-900/40"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="title()"
      >
        <div class="w-full max-w-md rounded-md bg-white p-6 shadow-lg">
          <h2 class="mb-3 text-lg font-medium text-slate-900" data-testid="af-dialog-title">
            {{ title() }}
          </h2>
          @if (message()) {
            <p class="mb-4 text-sm text-slate-600" data-testid="af-dialog-message">
              {{ message() }}
            </p>
          }
          <div class="flex justify-end gap-2">
            <af-button (clicked)="cancel.emit()" data-testid="af-dialog-cancel">
              {{ cancelLabel() }}
            </af-button>
            <af-button type="primary" (clicked)="confirm.emit()" data-testid="af-dialog-confirm">
              {{ confirmLabel() }}
            </af-button>
          </div>
        </div>
      </div>
    }
  `,
})
export class AfDialogComponent {
  readonly visible = input<boolean>(false);
  readonly title = input<string>('Confirm');
  readonly message = input<string | null>(null);
  readonly confirmLabel = input<string>('Confirm');
  readonly cancelLabel = input<string>('Cancel');

  readonly confirm = output<void>();
  readonly cancel = output<void>();
}
