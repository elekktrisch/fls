import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Page container with responsive side gutters (16/24/32/48 at sm/md/lg/xl).
 * Default mode is `wide` — full width with gutters only. `narrow` caps at
 * 40rem (640px) centered, for login / simple settings / single-action forms.
 *
 *   <af-page>...wide content (tables, dashboards, complex forms)...</af-page>
 *   <af-page mode="narrow">...login, settings...</af-page>
 *
 * Vertical rhythm between top-level page sections is the consumer's job —
 * use Tailwind `gap-*` on a flex/grid container or `space-y-*` between blocks.
 */
@Component({
  selector: 'af-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `<div [class]="containerClasses()"><ng-content /></div>`,
})
export class AfPageComponent {
  readonly mode = input<'wide' | 'narrow'>('wide');

  protected readonly containerClasses = computed(
    () =>
      `w-full mx-auto px-4 py-6 md:px-6 lg:px-8 xl:px-12 ${this.mode() === 'narrow' ? 'max-w-[40rem]' : ''}`,
  );
}
