import {
  DestroyRef,
  Injectable,
  Signal,
  computed,
  inject,
  signal,
  type WritableSignal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { fromEvent } from 'rxjs';

export type Breakpoint = 'sm' | 'md' | 'lg' | 'xl';

const BREAKPOINT_MIN_PX: Record<Breakpoint, number> = {
  sm: 360,
  md: 768,
  lg: 1024,
  xl: 1440,
};

/**
 * Signal-derived viewport tracking.
 *
 * Subscribes one `MediaQueryList` per AC-DIR-1 breakpoint and exposes per-bp
 * signals. `isBelow` / `isAtLeast` are computed-derived from `current`.
 */
@Injectable({ providedIn: 'root' })
export class ViewportService {
  readonly #destroyRef = inject(DestroyRef);
  readonly #matches: Record<Breakpoint, WritableSignal<boolean>> = {
    sm: signal(this.#matchesNow('sm')),
    md: signal(this.#matchesNow('md')),
    lg: signal(this.#matchesNow('lg')),
    xl: signal(this.#matchesNow('xl')),
  };

  constructor() {
    for (const bp of ['sm', 'md', 'lg', 'xl'] as const) {
      const mql = this.#mql(bp);
      if (!mql) continue;
      fromEvent<MediaQueryListEvent>(mql, 'change')
        .pipe(takeUntilDestroyed(this.#destroyRef))
        .subscribe((evt) => this.#matches[bp].set(evt.matches));
    }
  }

  /** True when the viewport width is >= the breakpoint's lower bound. */
  isAtLeast(bp: Breakpoint): Signal<boolean> {
    return this.#matches[bp].asReadonly();
  }

  /** True when the viewport width is strictly below the breakpoint. */
  isBelow(bp: Breakpoint): Signal<boolean> {
    return computed(() => !this.#matches[bp]());
  }

  #matchesNow(bp: Breakpoint): boolean {
    const mql = this.#mql(bp);
    return mql ? mql.matches : true;
  }

  #mql(bp: Breakpoint): MediaQueryList | null {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return null;
    }
    return window.matchMedia(`(min-width: ${BREAKPOINT_MIN_PX[bp]}px)`);
  }
}
