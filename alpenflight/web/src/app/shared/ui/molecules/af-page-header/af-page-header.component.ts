import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'af-page-header',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block mb-12' },
  template: `
    <header
      class="flex flex-col gap-3 pb-4 border-b border-slate-200 md:flex-row md:items-start md:justify-between md:gap-6"
    >
      <div class="flex flex-col gap-1 min-w-0">
        <h1 class="text-3xl font-medium text-slate-900 m-0 leading-tight">{{ title() }}</h1>
        @if (description(); as d) {
          <p class="text-sm text-slate-500 m-0">{{ d }}</p>
        }
      </div>
      <div class="flex items-center gap-2 md:flex-none">
        <ng-content />
      </div>
    </header>
  `,
})
export class AfPageHeaderComponent {
  readonly title = input.required<string>();
  readonly description = input<string | null>(null);
}
