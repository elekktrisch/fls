import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'af-sticky-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="sticky bottom-0 left-0 right-0 z-10 border-t border-slate-200 bg-white p-3 lg:static lg:border-0 lg:p-0"
    >
      <ng-content />
    </div>
  `,
})
export class AfStickyBarComponent {}
