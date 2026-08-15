import type { HttpErrorResponse } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { forkJoin, pipe, switchMap, tap } from 'rxjs';

import type {
  DiscoveryFlightRegistrationResponse,
  PublicClubResponse,
  PublicRegistrantDetails,
} from '@api/generated/model';
import { PublicRegistrationService } from '@api/generated/public-registration/public-registration.service';

import type { PublicSubmitFailure } from './public-form-shell.component';
import {
  type ClubResolution,
  clubHeadingFor,
  clubRead,
  clubReadRejection,
  publicFormState,
  submitFailureFor,
} from './public-form-state';

interface DiscoveryFlightState {
  clubSlug: string;
  clubName: string | null;
  days: readonly string[];
  resolution: ClubResolution;
  submitting: boolean;
  registration: DiscoveryFlightRegistrationResponse | null;
  failure: PublicSubmitFailure | null;
  retryAfterSeconds: number;
}

const initial: DiscoveryFlightState = {
  clubSlug: '',
  clubName: null,
  days: [],
  resolution: 'loading',
  submitting: false,
  registration: null,
  failure: null,
  retryAfterSeconds: 0,
};

interface SubmitArgs {
  clubSlug: string;
  registrant: PublicRegistrantDetails;
  selectedDay: string;
}

export const DiscoveryFlightStore = signalStore(
  withState<DiscoveryFlightState>(initial),
  withComputed((store) => ({
    formState: computed(() => publicFormState(store.resolution(), store.registration() !== null)),
    clubHeading: computed(() => clubHeadingFor(store.clubName(), store.clubSlug())),
  })),
  withMethods((store, api = inject(PublicRegistrationService)) => {
    const submit = rxMethod<SubmitArgs>(
      pipe(
        tap(() => patchState(store, { submitting: true, failure: null, retryAfterSeconds: 0 })),
        switchMap(({ clubSlug, registrant, selectedDay }) =>
          api.submitDiscoveryFlightRegistration(clubSlug, { registrant, selectedDay }).pipe(
            tapResponse({
              next: (registration: DiscoveryFlightRegistrationResponse) =>
                patchState(store, { submitting: false, registration }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { submitting: false, ...submitFailureFor(e) }),
            }),
          ),
        ),
      ),
    );

    return {
      loadClub: rxMethod<string>(
        pipe(
          tap((clubSlug: string) =>
            patchState(store, { ...initial, clubSlug, resolution: 'loading' }),
          ),
          switchMap((clubSlug: string) =>
            forkJoin({
              club: api.getPublicClub(clubSlug),
              days: api.listPublicDiscoveryFlightDays(clubSlug),
            }).pipe(
              tapResponse({
                next: ({ club, days }: { club: PublicClubResponse; days: string[] }) =>
                  patchState(store, { days, ...clubRead(club) }),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { days: [], ...clubReadRejection(e) }),
              }),
            ),
          ),
        ),
      ),
      submit(registrant: PublicRegistrantDetails, selectedDay: string): void {
        submit({ clubSlug: store.clubSlug(), registrant, selectedDay });
      },
    };
  }),
);
