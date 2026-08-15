import { Directive, effect, inject, input } from '@angular/core';

import { DensityService, type Density } from './density.service';

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
