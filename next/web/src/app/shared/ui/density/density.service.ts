import { Injectable, Signal, computed, inject, signal } from '@angular/core';

import { ViewportService } from '../viewport/viewport.service';

export type Density = 'comfortable' | 'dense';

/**
 * Single source of truth for UI density. Viewport-derived by default
 * (`dense` at `≥lg`, `comfortable` otherwise) with an explicit override hook
 * used by `<af-density-provider>` directives that want to pin density inside
 * a subtree's host attribute.
 *
 * Note: the override today is global per app — overriding inside a subtree
 * flips the value everywhere that injects `DensityService`. The per-subtree
 * override (DI-scoped service) lands when the first consumer needs it.
 */
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

  /** Pin density to a specific value, ignoring viewport. */
  setOverride(value: Density): void {
    this.#override.set(value);
  }

  /** Drop the override; density derives from viewport again. */
  clearOverride(): void {
    this.#override.set(null);
  }
}
