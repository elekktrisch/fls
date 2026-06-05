import { DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { tapResponse } from '@ngrx/operators';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { SystemDashboardResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

interface SystemDashboardState {
  totalClubs: number;
  totalUsers: number;
  totalFlights: number;
  isLoading: boolean;
  hasError: boolean;
  // True once a fetch has resolved successfully at least once — lets the tile
  // distinguish "first paint, still loading" from "loaded, total happens to be 0".
  hasLoaded: boolean;
}

const initial: SystemDashboardState = {
  totalClubs: 0,
  totalUsers: 0,
  totalFlights: 0,
  isLoading: false,
  hasError: false,
  hasLoaded: false,
};

/**
 * Feeds the sysadmin dashboard variant's tiles (J-3 T-11): cross-tenant totals —
 * total clubs / users / flights — from {@code GET /api/v1/me/system-dashboard}
 * (T-10, SYSTEM_ADMINISTRATOR-gated, deliberately tenant-<em>un</em>scoped: it
 * aggregates across every club).
 *
 * <p><b>No SSE re-fetch.</b> Unlike the club-admin store (which re-fetches on the
 * per-principal `flight.created` push), cross-tenant aggregates don't track a
 * single principal's flight events — they load once on init via a normal GET
 * (the journey carve: SSE is a per-tenant change overlay, not a deployment-wide
 * counter). Load-on-init is the contract here.
 *
 * <p>Provided in root (a single dashboard surface) and cleared on
 * `session.logout` / `session.tenantSwitch` like every domain store (CLAUDE.md
 * §4b) — so a stale aggregate never bleeds across a session boundary.
 */
export const SystemDashboardStore = signalStore(
  { providedIn: 'root' },
  withState<SystemDashboardState>(initial),
  withComputed(({ isLoading, hasError, hasLoaded }) => ({
    // Show the numbers once a load has resolved; keep them visible across a
    // background re-fetch (isLoading true but hasLoaded already true).
    showTotals: computed(() => hasLoaded() && !hasError()),
    // First-paint spinner: loading with nothing resolved yet.
    showLoading: computed(() => isLoading() && !hasLoaded()),
  })),
  withMethods((store, meApi = inject(MeService)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, hasError: false })),
        switchMap(() =>
          meApi.get2().pipe(
            tapResponse({
              next: (totals: SystemDashboardResponse) =>
                patchState(store, {
                  totalClubs: totals.totalClubs ?? 0,
                  totalUsers: totals.totalUsers ?? 0,
                  totalFlights: totals.totalFlights ?? 0,
                  isLoading: false,
                  hasError: false,
                  hasLoaded: true,
                }),
              // Keep the last good totals on screen; only flag the error.
              error: () => patchState(store, { isLoading: false, hasError: true }),
            }),
          ),
        ),
      ),
    );
    return {
      load,
      clear(): void {
        patchState(store, { ...initial });
      },
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);

      // Initial (and only) paint — load the cross-tenant totals.
      store.load();

      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          store.clear();
        }
      });
    },
  }),
);
