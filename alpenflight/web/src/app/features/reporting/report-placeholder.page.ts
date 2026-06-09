import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NzSpinModule } from 'ng-zorro-antd/spin';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

/**
 * Scaffold placeholder for the not-yet-built reporting screens. The results page
 * (T-10) and the custom builder (T-11) replace this with their real components;
 * the route shells already exist so navigation from the picker resolves rather
 * than 404s. Renders the page chrome + a loading affordance so the route is
 * visibly mounted (and the e2e flow stubs flip from `fixme` once T-10/T-11 land).
 */
@Component({
  selector: 'af-report-placeholder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [AfPageComponent, AfPageHeaderComponent, NzSpinModule],
  template: `
    <af-page>
      <af-page-header [title]="title()" />
      <div
        class="flex flex-col items-center justify-center gap-3 py-16 text-sm text-slate-500 border border-slate-200 bg-white"
        data-testid="report-placeholder"
      >
        <nz-spin />
        <p class="m-0">Report screen coming soon.</p>
      </div>
    </af-page>
  `,
})
export class ReportPlaceholderPage {
  readonly title = input<string>('Flight report');
}
