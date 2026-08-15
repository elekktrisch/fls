import type { HttpErrorResponse } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import type {
  PublicClubResponse,
  PublicRegistrantDetails,
  ScenicFlightRegistrationResponse,
} from '@api/generated/model';
import { PublicRegistrationService } from '@api/generated/public-registration/public-registration.service';

import type { PublicSubmitFailure } from './public-form-shell.component';
import {
  type ClubResolution,
  clubHeadingFor,
  clubRead,
  clubReadRejection,
  clubResolutionFor,
  publicFormState,
  rejectsTheClub,
  submitFailureFor,
} from './public-form-state';

interface ScenicFlightState {
  clubSlug: string;
  clubName: string | null;
  resolution: ClubResolution;
  submitting: boolean;
  registration: ScenicFlightRegistrationResponse | null;
  failure: PublicSubmitFailure | null;
  retryAfterSeconds: number;
}

const initial: ScenicFlightState = {
  clubSlug: '',
  clubName: null,
  resolution: 'loading',
  submitting: false,
  registration: null,
  failure: null,
  retryAfterSeconds: 0,
};

interface SubmitArgs {
  clubSlug: string;
  registrant: PublicRegistrantDetails;
}

function scenicRejection(error: HttpErrorResponse): Partial<ScenicFlightState> {
  return rejectsTheClub(error.status)
    ? { resolution: clubResolutionFor(error.status) }
    : submitFailureFor(error);
}

export const ScenicFlightStore = signalStore(
  withState<ScenicFlightState>(initial),
  withComputed((store) => ({
    formState: computed(() => publicFormState(store.resolution(), store.registration() !== null)),
    clubHeading: computed(() => clubHeadingFor(store.clubName(), store.clubSlug())),
  })),
  withMethods((store, api = inject(PublicRegistrationService)) => {
    const submit = rxMethod<SubmitArgs>(
      pipe(
        tap(() => patchState(store, { submitting: true, failure: null, retryAfterSeconds: 0 })),
        switchMap(({ clubSlug, registrant }) =>
          api.submitScenicFlightRegistration(clubSlug, { registrant }).pipe(
            tapResponse({
              next: (registration: ScenicFlightRegistrationResponse) =>
                patchState(store, { submitting: false, registration }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { submitting: false, ...scenicRejection(e) }),
            }),
          ),
        ),
      ),
    );

    return {
      loadClub: rxMethod<string>(
        pipe(
          tap((clubSlug: string) => patchState(store, { ...initial, clubSlug })),
          switchMap((clubSlug: string) =>
            api.getPublicClub(clubSlug).pipe(
              tapResponse({
                next: (club: PublicClubResponse) => patchState(store, clubRead(club)),
                error: (e: HttpErrorResponse) => patchState(store, clubReadRejection(e)),
              }),
            ),
          ),
        ),
      ),
      submit(registrant: PublicRegistrantDetails): void {
        submit({ clubSlug: store.clubSlug(), registrant });
      },
    };
  }),
);
