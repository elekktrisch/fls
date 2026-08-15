import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { AfButtonComponent } from '@ui/atoms/af-button';

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
        <div
          class="w-full rounded-md bg-white p-6 shadow-lg"
          [class.max-w-md]="!wide()"
          [class.max-w-2xl]="wide()"
        >
          <h2 class="mb-3 text-lg font-medium text-slate-900" data-testid="af-dialog-title">
            {{ title() }}
          </h2>
          @if (message()) {
            <p class="mb-4 text-sm text-slate-600" data-testid="af-dialog-message">
              {{ message() }}
            </p>
          }
          <div class="empty:hidden mb-4">
            <ng-content />
          </div>
          <div class="flex justify-end gap-2">
            <af-button (clicked)="dismiss.emit()" data-testid="af-dialog-dismiss">
              {{ dismissLabel() }}
            </af-button>
            <af-button
              type="primary"
              [disabled]="confirmDisabled()"
              (clicked)="confirm.emit()"
              [attr.data-testid]="confirmTestid()"
            >
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
  readonly wide = input<boolean>(false);
  readonly title = input<string>('Confirm');
  readonly message = input<string | null>(null);
  readonly confirmLabel = input<string>('Confirm');
  readonly dismissLabel = input<string>('Cancel');
  readonly confirmTestid = input<string>('af-dialog-confirm');
  readonly confirmDisabled = input<boolean>(false);

  readonly confirm = output<void>();
  readonly dismiss = output<void>();
}
