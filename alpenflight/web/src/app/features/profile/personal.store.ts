import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonResponse, MePersonUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

/**
 * The Personal (Person-contact) self-edit values the form binds to. Sourced from
 * the caller-scoped `GET /api/v1/me/person` projection (T-18) and round-tripped
 * through `PATCH /api/v1/me/person` (`updateMyPerson`).
 *
 * `firstName` / `lastName` render READ-ONLY (rename stays admin-only). The
 * contact / address fields are editable. All fields default to the empty string
 * (or `false` for the business-mail pref) so the reactive form's non-nullable
 * controls always have a value.
 *
 * <p>The dedicated GET (mirroring the Pilot / Notifications tabs, T-08 / T-10) is
 * what makes the populated-render + edit→persist→reflect-on-reload round-trip
 * work: `/me` carries only the Person's name, not the contact / address fields,
 * so the tab would otherwise hydrate empty (the T-06/T-07 read gap).
 */
export interface PersonalView {
  // Read-only identity (admin-owned rename) — display only.
  firstName: string;
  lastName: string;
  // Editable contact / address fields.
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
  // True once a save has persisted — drives the inline "saved" confirmation.
  savedOnce: boolean;
}

const initial: PersonalState = {
  view: null,
  isLoading: false,
  isSaving: false,
  hasError: false,
  savedOnce: false,
};

/** Map the contact/address projection to the view, defaulting absent fields. */
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

/**
 * Personal-tab store (J-4 T-07 form, T-18 hydrate). Loads the caller's own
 * Person contact / address fields from `GET /api/v1/me/person` (orval
 * `getMyPerson`) and persists edits through `PATCH /api/v1/me/person` (orval
 * `updateMyPerson`). Name fields (first/last/mid/company) are admin-only —
 * read-only here.
 *
 * On a saved edit it re-reads via `getMyPerson` so the form reflects the
 * persisted contact values (the PATCH response is the `/me` projection, which
 * carries only the name) and emits a `profile.updated` MUTATION_BUS event so the
 * session re-reads `/me` (the same event the Account / Pilot tabs emit) —
 * coordinated via the bus rather than a direct SessionStore injection
 * (no-sibling-store rule, CLAUDE.md §10).
 *
 * Feature-scoped (not `providedIn: 'root'`): the store's lifetime is the
 * `/profile` route, and a fresh load on every visit is the desired behavior.
 * The backend GET / PATCH 409 when the caller has no linked Person; the shell
 * gates this tab on `hasPerson()`, so the load never fires for a person-less
 * principal.
 */
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
            // The PATCH response is the /me projection (name only); re-read the
            // contact shape so the form reflects the persisted values.
            switchMap(() => me.getMyPerson()),
            tapResponse({
              next: (res: MePersonResponse) => {
                patchState(store, {
                  view: toView(res),
                  isSaving: false,
                  savedOnce: true,
                });
                // Nudge the session to re-read /me so the nav avatar +
                // session-backed consumers reflect the new values.
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
