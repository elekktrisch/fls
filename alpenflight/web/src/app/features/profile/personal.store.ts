import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonUpdateRequest, MeResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

/**
 * The Personal (Person-contact) self-edit values the form binds to.
 *
 * `firstName` / `lastName` are sourced from the `/api/v1/me` projection and
 * render READ-ONLY (rename stays admin-only). The contact / address fields are
 * editable and round-trip through {@code PATCH /api/v1/me/person}
 * (`updateMyPerson`).
 *
 * <p><strong>/me projection gap (note for T-13 round-trip).</strong> The
 * `/api/v1/me` projection (`MeResponse`) carries only `firstName` / `lastName`
 * of the Person — NOT the contact / address fields (`addressLine1`, `city`,
 * `zip`, phones, `birthday`, …). So the editable controls hydrate empty on first
 * load and the populated-render + edit→persist→reflect-on-reload round-trip
 * (T-13 / S-182 AC "Personal round-trip") needs the `/me` projection (or a
 * caller-scoped `GET /me/person`) extended with those Person contact fields —
 * the same shape T-05 added for the Account self-fields. T-07 wires the form +
 * the PATCH; the projection touch + round-trip assertions are T-13's.
 */
export interface PersonalView {
  // Read-only identity (admin-owned rename) — display only.
  firstName: string;
  lastName: string;
  // Editable contact / address fields. Sourced from /me where present; today
  // /me carries none of these, so they start empty until the projection touch
  // (see the class doc) — the form is still wired to the PATCH.
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

/**
 * Map the `/me` projection to the Personal view. Only `firstName` / `lastName`
 * are present in `MeResponse` today; the contact fields fall back to empty (see
 * the {@link PersonalView} class doc — the projection touch is T-13's).
 */
function toView(res: MeResponse): PersonalView {
  return {
    firstName: res.firstName ?? '',
    lastName: res.lastName ?? '',
    addressLine1: '',
    addressLine2: '',
    zip: '',
    city: '',
    region: '',
    countryId: '',
    privatePhone: '',
    mobilePhone: '',
    businessPhone: '',
    faxNumber: '',
    emailPrivate: '',
    emailBusiness: '',
    preferMailToBusinessMail: false,
    birthday: '',
  };
}

/**
 * Personal-tab store (J-4 T-07). Loads the caller's own Person fields from
 * `/api/v1/me` (name fields for the read-only display) and persists contact /
 * address edits through `PATCH /api/v1/me/person` (orval `updateMyPerson`).
 * Name fields (first/last/mid/company) are admin-only — read-only here.
 *
 * On a saved edit it emits a `profile.updated` MUTATION_BUS event so the session
 * re-reads `/me` (the same event the Account tab emits) — coordinated via the
 * bus rather than a direct SessionStore injection (no-sibling-store rule,
 * CLAUDE.md §10).
 *
 * Feature-scoped (not `providedIn: 'root'`): the store's lifetime is the
 * `/profile` route, and a fresh load on every visit is the desired behavior.
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
          me.get1().pipe(
            tapResponse({
              next: (res: MeResponse) => patchState(store, { view: toView(res), isLoading: false }),
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
            tapResponse({
              next: (res: MeResponse) => {
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
