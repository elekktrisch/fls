import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonLicencesResponse, MePersonLicencesUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export interface PilotView {
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
  licenceNumber: string;
  medicalClass1ExpireDate: string;
  medicalClass2ExpireDate: string;
  medicalLaplExpireDate: string;
  gliderInstructorLicenceExpireDate: string;
  motorInstructorLicenceExpireDate: string;
  partMLicenceExpireDate: string;
  hasGliderTowingStartPermission: boolean;
  hasGliderSelfStartPermission: boolean;
  hasGliderWinchStartPermission: boolean;
  receiveOwnedAircraftStatisticReports: boolean;
}

interface PilotState {
  view: PilotView | null;
  isLoading: boolean;
  isSaving: boolean;
  hasError: boolean;
  savedOnce: boolean;
}

const initial: PilotState = {
  view: null,
  isLoading: false,
  isSaving: false,
  hasError: false,
  savedOnce: false,
};

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
