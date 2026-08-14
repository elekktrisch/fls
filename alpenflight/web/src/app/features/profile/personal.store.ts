import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonResponse, MePersonUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export interface PersonalView {
  firstName: string;
  lastName: string;
  addressLine1: string;
  addressLine2: string;
  zip: string;
  city: string;
  region: string;
  countryId: string;
  privatePhone: string;
  mobilePhone: string;
  businessPhone: string;
  faxNumber: string;
  emailPrivate: string;
  emailBusiness: string;
  preferMailToBusinessMail: boolean;
  birthday: string;
}

interface PersonalState {
  view: PersonalView | null;
  isLoading: boolean;
  isSaving: boolean;
  hasError: boolean;
  savedOnce: boolean;
}

const initial: PersonalState = {
  view: null,
  isLoading: false,
  isSaving: false,
  hasError: false,
  savedOnce: false,
};

function toView(res: MePersonResponse): PersonalView {
  return {
    firstName: res.firstName ?? '',
    lastName: res.lastName ?? '',
    addressLine1: res.addressLine1 ?? '',
    addressLine2: res.addressLine2 ?? '',
    zip: res.zip ?? '',
    city: res.city ?? '',
    region: res.region ?? '',
    countryId: res.countryId ?? '',
    privatePhone: res.privatePhone ?? '',
    mobilePhone: res.mobilePhone ?? '',
    businessPhone: res.businessPhone ?? '',
    faxNumber: res.faxNumber ?? '',
    emailPrivate: res.emailPrivate ?? '',
    emailBusiness: res.emailBusiness ?? '',
    preferMailToBusinessMail: res.preferMailToBusinessMail ?? false,
    birthday: res.birthday ?? '',
  };
}

export const PersonalStore = signalStore(
  withState<PersonalState>(initial),
  withComputed(({ view, isSaving }) => ({
    canSave: computed(() => view() !== null && !isSaving()),
  })),
  withMethods((store, me = inject(MeService), bus = inject(MUTATION_BUS)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, hasError: false })),
        switchMap(() =>
          me.getMyPerson().pipe(
            tapResponse({
              next: (res: MePersonResponse) =>
                patchState(store, { view: toView(res), isLoading: false }),
              error: () => patchState(store, { isLoading: false, hasError: true }),
            }),
          ),
        ),
      ),
    );

    const save = rxMethod<MePersonUpdateRequest>(
      pipe(
        tap(() => patchState(store, { isSaving: true, hasError: false })),
        switchMap((req) =>
          me.updateMyPerson(req).pipe(
            switchMap(() => me.getMyPerson()),
            tapResponse({
              next: (res: MePersonResponse) => {
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
