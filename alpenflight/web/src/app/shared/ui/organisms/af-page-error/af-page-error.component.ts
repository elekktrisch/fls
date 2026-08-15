import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { AfButtonComponent } from '../../atoms/af-button';
import { AfIconComponent } from '../../atoms/af-icon';

@Component({
  selector: 'af-page-error',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent, AfIconComponent],
  host: {
    '[class]': 'hostClasses()',
  },
  template: `
    @if (message(); as m) {
      <div
        role="alert"
        data-testid="af-page-error"
        class="flex items-center gap-3 py-3 px-4 bg-red-50 border border-red-600 text-red-700"
      >
        <af-icon name="alert-triangle" [size]="20" class="flex-none text-red-600" />
        <span class="flex-1 text-sm leading-snug">{{ m }}</span>
        @if (retryLabel(); as label) {
          <af-button htmlType="button" (clicked)="retry.emit()">{{ label }}</af-button>
        }
      </div>
    }
  `,
})
export class AfPageErrorComponent {
  readonly message = input<string | null>(null);
  readonly retryLabel = input<string | null>('Retry');
  readonly retry = output<void>();

  protected readonly hostClasses = computed(() => (this.message() ? 'block mb-4' : 'hidden'));
}
