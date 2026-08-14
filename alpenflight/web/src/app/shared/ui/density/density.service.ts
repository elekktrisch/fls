import { Injectable, Signal, computed, inject, signal } from '@angular/core';

import { ViewportService } from '../viewport/viewport.service';

export type Density = 'comfortable' | 'dense';

@Injectable({ providedIn: 'root' })
export class DensityService {
  readonly #viewport = inject(ViewportService);
  readonly #override = signal<Density | null>(null);
  readonly #atLeastLg = this.#viewport.isAtLeast('lg');

  readonly density: Signal<Density> = computed(() => {
    const override = this.#override();
    if (override !== null) return override;
    return this.#atLeastLg() ? 'dense' : 'comfortable';
  });

  setOverride(value: Density): void {
    this.#override.set(value);
  }

  clearOverride(): void {
    this.#override.set(null);
  }
}
