import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/*
 * Brand wordmark (ADR 0024 §Wordmark). Renders the full lockup at ≥md and the
 * plane-glyph-only compact mark at <md — CSS-driven so both are in the DOM and
 * the swap costs no request. `label` names the mark for assistive tech and any
 * name-based locator; the two <img alt> carry it (an <img>-loaded SVG's own
 * internal aria-label does not surface to the host document).
 */
@Component({
  selector: 'af-wordmark',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <img
      class="hidden md:block h-6 w-auto"
      src="brand/wordmark-full.svg"
      [alt]="label()"
      data-testid="af-wordmark-full"
    />
    <img
      class="block md:hidden h-8 w-auto"
      src="brand/wordmark-compact.svg"
      [alt]="label()"
      data-testid="af-wordmark-compact"
    />
  `,
})
export class AfWordmarkComponent {
  readonly label = input<string>('AlpenFlight');
}
