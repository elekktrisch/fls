import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonLicencesResponse, MePersonLicencesUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

/**
 * The Pilot (licence / medical) self-edit values the form binds to. Sourced from
 * the caller-scoped `GET /api/v1/me/person/licences` projection (T-08) and
 * round-tripped through `PATCH /api/v1/me/person/licences` (`updateMyLicences`).
 *
 * All flags default to `false` and all dates / the licence number default to the
 * empty string so the reactive form's non-nullable controls always have a value.
 * The medical / instructor / Part-M expiry dates are FADP-sensitive; here they
 * are ordinary `<input type="date">`-shaped strings (`YYYY-MM-DD`).
 */
export interface PilotView {
  // 10 licence flags.
  hasMotorPilotLicence: boolean;
  hasTowPilotLicence: boolean;
  hasGliderInstructorLicence: boolean;
  hasGliderPilotLicence: boolean;
  hasGliderTraineeLicence: boolean;
  hasGliderPaxLicence: boolean;
  hasTmgLicence: boolean;
  hasWinchOperatorLicence: boolean;
  hasMotorInstructorLicence: boolean;
  hasPartMLicence: boolean;
  // Licence number.
  licenceNumber: string;
  // 6 expiry dates (YYYY-MM-DD).
  medicalClass1ExpireDate: string;
  medicalClass2ExpireDate: string;
  medicalLaplExpireDate: string;
  gliderInstructorLicenceExpireDate: string;
  motorInstructorLicenceExpireDate: string;
  partMLicenceExpireDate: string;
  // 3 glider start-permission flags.
  hasGliderTowingStartPermission: boolean;
  hasGliderSelfStartPermission: boolean;
  hasGliderWinchStartPermission: boolean;
  // Owned-aircraft statistic reports.
  receiveOwnedAircraftStatisticReports: boolean;
}

interface PilotState {
  view: PilotView | null;
  isLoading: boolean;
  isSaving: boolean;
  hasError: boolean;
  // True once a save has persisted — drives the inline "saved" confirmation.
  savedOnce: boolean;
}

const initial: PilotState = {
  view: null,
  isLoading: false,
  isSaving: false,
  hasError: false,
  savedOnce: false,
};

/** Map the licence/medical projection to the view, defaulting absent fields. */
function toView(res: MePersonLicencesResponse): PilotView {
  return {
    hasMotorPilotLicence: res.hasMotorPilotLicence ?? false,
    hasTowPilotLicence: res.hasTowPilotLicence ?? false,
    hasGliderInstructorLicence: res.hasGliderInstructorLicence ?? false,
    hasGliderPilotLicence: res.hasGliderPilotLicence ?? false,
    hasGliderTraineeLicence: res.hasGliderTraineeLicence ?? false,
    hasGliderPaxLicence: res.hasGliderPaxLicence ?? false,
    hasTmgLicence: res.hasTmgLicence ?? false,
    hasWinchOperatorLicence: res.hasWinchOperatorLicence ?? false,
    hasMotorInstructorLicence: res.hasMotorInstructorLicence ?? false,
    hasPartMLicence: res.hasPartMLicence ?? false,
    licenceNumber: res.licenceNumber ?? '',
    medicalClass1ExpireDate: res.medicalClass1ExpireDate ?? '',
    medicalClass2ExpireDate: res.medicalClass2ExpireDate ?? '',
    medicalLaplExpireDate: res.medicalLaplExpireDate ?? '',
    gliderInstructorLicenceExpireDate: res.gliderInstructorLicenceExpireDate ?? '',
    motorInstructorLicenceExpireDate: res.motorInstructorLicenceExpireDate ?? '',
    partMLicenceExpireDate: res.partMLicenceExpireDate ?? '',
    hasGliderTowingStartPermission: res.hasGliderTowingStartPermission ?? false,
    hasGliderSelfStartPermission: res.hasGliderSelfStartPermission ?? false,
    hasGliderWinchStartPermission: res.hasGliderWinchStartPermission ?? false,
    receiveOwnedAircraftStatisticReports: res.receiveOwnedAircraftStatisticReports ?? false,
  };
}

/**
 * Pilot-tab store (J-4 T-09). Loads the caller's own Person licence/medical
 * fields from `GET /api/v1/me/person/licences` (orval `getMyLicences`) and
 * persists edits through `PATCH /api/v1/me/person/licences` (orval
 * `updateMyLicences`). On a saved edit it emits a `profile.updated` MUTATION_BUS
 * event so the session re-reads `/me` (same event the Account / Personal tabs
 * emit) — coordinated via the bus, not a direct SessionStore injection
 * (no-sibling-store rule, CLAUDE.md §10).
 *
 * Feature-scoped (not `providedIn: 'root'`): the store's lifetime is the
 * `/profile` route, and a fresh load on every visit is the desired behavior.
 * The backend GET / PATCH 409 when the caller has no linked Person; the shell
 * gates this tab on `hasPerson()`, so the load never fires for a person-less
 * principal.
 */
export const PilotStore = signalStore(
  withState<PilotState>(initial),
  withComputed(({ view, isSaving }) => ({
    canSave: computed(() => view() !== null && !isSaving()),
  })),
  withMethods((store, me = inject(MeService), bus = inject(MUTATION_BUS)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, hasError: false })),
        switchMap(() =>
          me.getMyLicences().pipe(
            tapResponse({
              next: (res: MePersonLicencesResponse) =>
                patchState(store, { view: toView(res), isLoading: false }),
              error: () => patchState(store, { isLoading: false, hasError: true }),
            }),
          ),
        ),
      ),
    );

    const save = rxMethod<MePersonLicencesUpdateRequest>(
      pipe(
        tap(() => patchState(store, { isSaving: true, hasError: false })),
        switchMap((req) =>
          me.updateMyLicences(req).pipe(
            tapResponse({
              next: (res: MePersonLicencesResponse) => {
                patchState(store, {
                  view: toView(res),
                  isSaving: false,
                  savedOnce: true,
                });
                // Nudge the session to re-read /me so any /me consumer reflects
                // the change (the licence fields are not on /me today, but the
                // event keeps the self-edit tabs uniform).
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
