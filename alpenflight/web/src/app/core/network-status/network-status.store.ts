import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { patchState, signalStore, withHooks, withState } from '@ngrx/signals';
import { fromEvent, map, merge } from 'rxjs';

interface NetworkStatusState {
  networkOnline: boolean;
}

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
