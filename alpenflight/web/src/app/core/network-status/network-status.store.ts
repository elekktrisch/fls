import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { patchState, signalStore, withHooks, withState } from '@ngrx/signals';
import { fromEvent, map, merge } from 'rxjs';

interface NetworkStatusState {
  networkOnline: boolean;
}

/**
 * Interim online/offline signal sourced from `window.online`/`window.offline`
 * events. S-117 (PWA service worker) replaces with a more reliable signal
 * driven by fetch outcomes — at that point this store stays as the public
 * surface and the underlying source swaps.
 */
export const NetworkStatusStore = signalStore(
  { providedIn: 'root' },
  withState<NetworkStatusState>({
    networkOnline: typeof navigator === 'undefined' ? true : navigator.onLine,
  }),
  withHooks({
    onInit(store) {
      const destroyRef = inject(DestroyRef);
      merge(
        fromEvent(window, 'online').pipe(map(() => true)),
        fromEvent(window, 'offline').pipe(map(() => false)),
      )
        .pipe(takeUntilDestroyed(destroyRef))
        .subscribe((online) => patchState(store, { networkOnline: online }));
    },
  }),
);
