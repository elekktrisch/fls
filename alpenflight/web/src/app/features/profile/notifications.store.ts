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
  savedOnce: boolean;
}

const initial: NotificationsState = {
  view: null,
  isLoading: false,
  isSaving: false,
  hasError: false,
  savedOnce: false,
};

function toView(res: MeNotificationPrefsResponse): NotificationsView {
  return {
    receiveFlightReports: res.receiveFlightReports ?? false,
    receiveAircraftReservationNotifications: res.receiveAircraftReservationNotifications ?? false,
    receivePlanningDayRoleReminder: res.receivePlanningDayRoleReminder ?? false,
  };
}

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
