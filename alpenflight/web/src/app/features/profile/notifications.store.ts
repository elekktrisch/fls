import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type {
  MeNotificationPrefsResponse,
  MeNotificationPrefsUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

/**
 * The Notifications (per-club notification-prefs) self-edit values the form binds
 * to. Sourced from the caller-tenant-scoped
 * `GET /api/v1/me/club-membership/notification-prefs` projection (T-10) and
 * round-tripped through `PATCH /api/v1/me/club-membership/notification-prefs`
 * (`updateMyNotificationPrefs`).
 *
 * All three flags default to `false` so the reactive form's non-nullable controls
 * always have a value (absent in the response = unchecked).
 */
export interface NotificationsView {
  receiveFlightReports: boolean;
  receiveAircraftReservationNotifications: boolean;
  receivePlanningDayRoleReminder: boolean;
}

interface NotificationsState {
  view: NotificationsView | null;
  isLoading: boolean;
  isSaving: boolean;
  hasError: boolean;
  // True once a save has persisted — drives the inline "saved" confirmation.
  savedOnce: boolean;
}

const initial: NotificationsState = {
  view: null,
  isLoading: false,
  isSaving: false,
  hasError: false,
  savedOnce: false,
};

/** Map the notification-prefs projection to the view, defaulting absent flags. */
function toView(res: MeNotificationPrefsResponse): NotificationsView {
  return {
    receiveFlightReports: res.receiveFlightReports ?? false,
    receiveAircraftReservationNotifications: res.receiveAircraftReservationNotifications ?? false,
    receivePlanningDayRoleReminder: res.receivePlanningDayRoleReminder ?? false,
  };
}

/**
 * Notifications-tab store (J-4 T-11). Loads the caller's own per-club notification
 * preferences from `GET /api/v1/me/club-membership/notification-prefs` (orval
 * `getMyNotificationPrefs`) and persists edits through
 * `PATCH /api/v1/me/club-membership/notification-prefs` (orval
 * `updateMyNotificationPrefs`). On a saved edit it emits a `profile.updated`
 * MUTATION_BUS event so the session re-reads `/me` (same event the Account /
 * Personal / Pilot tabs emit) — coordinated via the bus, not a direct
 * SessionStore injection (no-sibling-store rule, CLAUDE.md §10).
 *
 * Feature-scoped (not `providedIn: 'root'`): the store's lifetime is the
 * `/profile` route, and a fresh load on every visit is the desired behavior.
 * The backend GET / PATCH 409 when the caller has no linked Person / membership;
 * the shell gates this tab on `hasPerson()`, so the load never fires for a
 * person-less principal.
 */
export const NotificationsStore = signalStore(
  withState<NotificationsState>(initial),
  withComputed(({ view, isSaving }) => ({
    canSave: computed(() => view() !== null && !isSaving()),
  })),
  withMethods((store, me = inject(MeService), bus = inject(MUTATION_BUS)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, hasError: false })),
        switchMap(() =>
          me.getMyNotificationPrefs().pipe(
            tapResponse({
              next: (res: MeNotificationPrefsResponse) =>
                patchState(store, { view: toView(res), isLoading: false }),
              error: () => patchState(store, { isLoading: false, hasError: true }),
            }),
          ),
        ),
      ),
    );

    const save = rxMethod<MeNotificationPrefsUpdateRequest>(
      pipe(
        tap(() => patchState(store, { isSaving: true, hasError: false })),
        switchMap((req) =>
          me.updateMyNotificationPrefs(req).pipe(
            tapResponse({
              next: (res: MeNotificationPrefsResponse) => {
                patchState(store, {
                  view: toView(res),
                  isSaving: false,
                  savedOnce: true,
                });
                // Nudge the session to re-read /me so any /me consumer reflects
                // the change — keeps the self-edit tabs uniform.
                bus.next({ kind: 'profile.updated' });
              },
              error: () => patchState(store, { isSaving: false, hasError: true }),
            }),
          ),
        ),
      ),
    );

    return {
      load,
      save,
      clearError(): void {
        patchState(store, { hasError: false });
      },
    };
  }),
);
