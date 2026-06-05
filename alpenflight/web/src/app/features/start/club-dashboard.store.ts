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
import type { ClubDashboardResponse } from '@api/generated/model';

import { MeEventsService } from '../../core/events';
import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

interface ClubDashboardState {
  todaysFlights: number;
  pendingValidation: number;
  isLoading: boolean;
  hasError: boolean;
  // True once a fetch has resolved successfully at least once — lets the tile
  // distinguish "first paint, still loading" from "loaded, count happens to be 0".
  hasLoaded: boolean;
}

const initial: ClubDashboardState = {
  todaysFlights: 0,
  pendingValidation: 0,
  isLoading: false,
  hasError: false,
  hasLoaded: false,
};

/**
 * Feeds the club-admin dashboard variant's tiles (J-3 T-09): today's club
 * flight count + flights-pending-validation count, both from
 * {@code GET /api/v1/me/club-dashboard} (T-08, tenant-scoped server-side to the
 * caller's club via {@code @TenantId}).
 *
 * <p><b>Live update.</b> The store subscribes to the per-principal SSE channel
 * ({@link MeEventsService} `flight.created`, T-06) and, on each event,
 * <em>re-fetches</em> the counts. Per the journey carve, "SSE is the nudge, the
 * GET is the source of truth" — so the today-count moves in place without a
 * page reload, and the displayed numbers always reflect a real server read
 * (no optimistic drift). The re-fetch keeps the previously-shown counts visible
 * (no flicker to zero), only flipping to the error branch if the GET itself
 * fails.
 *
 * <p>Provided in root (a single dashboard surface) and cleared on
 * `session.logout` / `session.tenantSwitch` like every domain store (CLAUDE.md
 * §4b) so one club's counts never bleed into another's session.
 */
export const ClubDashboardStore = signalStore(
  { providedIn: 'root' },
  withState<ClubDashboardState>(initial),
  withComputed(({ isLoading, hasError, hasLoaded }) => ({
    // Show the numbers once a load has resolved; keep them visible across a
    // background re-fetch (isLoading true but hasLoaded already true).
    showCounts: computed(() => hasLoaded() && !hasError()),
    // First-paint spinner: loading with nothing resolved yet.
    showLoading: computed(() => isLoading() && !hasLoaded()),
  })),
  withMethods((store, meApi = inject(MeService)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, hasError: false })),
        switchMap(() =>
          meApi.get3().pipe(
            tapResponse({
              next: (counts: ClubDashboardResponse) =>
                patchState(store, {
                  todaysFlights: counts.todaysFlights ?? 0,
                  pendingValidation: counts.pendingValidation ?? 0,
                  isLoading: false,
                  hasError: false,
                  hasLoaded: true,
                }),
              // Keep the last good counts on screen; only flag the error.
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
      const events = inject(MeEventsService);
      const destroyRef = inject(DestroyRef);

      // Initial paint — load the counts.
      store.load();

      // Live update: every flight.created push re-fetches (SSE is the nudge,
      // the GET is the source of truth — journey carve). Zoneless: patchState
      // writes signals, which schedule change detection on their own.
      events
        .on('flight.created')
        .pipe(takeUntilDestroyed(destroyRef))
        .subscribe(() => store.load());

      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          store.clear();
        }
      });
    },
  }),
);
