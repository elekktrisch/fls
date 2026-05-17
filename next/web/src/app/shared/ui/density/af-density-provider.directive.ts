import { Directive, effect, inject, input } from '@angular/core';

import { DensityService, type Density } from './density.service';

/**
 * Apply density to a subtree. Sets `data-density` on the host element (drives
 * Tailwind density-scoped tokens) and pushes the value to `DensityService`
 * so injected wrappers (`[nzSize]` etc.) flip in sync.
 *
 * Usage:
 *   <main afDensityProvider>...</main>         viewport-derived
 *   <main [afDensityProvider]="'dense'">...    pinned override
 */
@Directive({
  selector: '[afDensityProvider]',
  standalone: true,
  host: {
    '[attr.data-density]': 'effectiveDensity()',
  },
})
export class AfDensityProviderDirective {
  readonly afDensityProvider = input<Density | '' | null>(null);
  readonly #density = inject(DensityService);

  protected readonly effectiveDensity = this.#density.density;

  constructor() {
    effect(() => {
      const override = this.afDensityProvider();
      if (override === 'comfortable' || override === 'dense') {
        this.#density.setOverride(override);
      } else {
        this.#density.clearOverride();
      }
    });
  }
}
