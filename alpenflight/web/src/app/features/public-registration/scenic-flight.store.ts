import type { HttpErrorResponse } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import type {
  PublicRegistrantDetails,
  ScenicFlightRegistrationResponse,
} from '@api/generated/model';
import { PublicRegistrationService } from '@api/generated/public-registration/public-registration.service';

import type { PublicSubmitFailure } from './public-form-shell.component';
import {
  type ClubResolution,
  clubResolutionFor,
  publicFormState,
  rejectsTheClub,
  submitFailureFor,
} from './public-form-state';

interface ScenicFlightState {
  clubSlug: string;
  resolution: ClubResolution;
  submitting: boolean;
  registration: ScenicFlightRegistrationResponse | null;
  failure: PublicSubmitFailure | null;
  retryAfterSeconds: number;
}

/** Scenic publishes no days, so there is nothing to wait for before the form. */
const initial: ScenicFlightState = {
  clubSlug: '',
  resolution: 'ready',
  submitting: false,
  registration: null,
  failure: null,
  retryAfterSeconds: 0,
};

interface SubmitArgs {
  clubSlug: string;
  registrant: PublicRegistrantDetails;
}

/**
 * Scenic has no anonymous read to adjudicate the slug with, so the submit
 * response is the first and only place it is judged: a 404 / 403 closes the form
 * with the club-resolution panel instead of offering a retry the server would
 * refuse again, while everything else leaves the form open.
 */
function scenicRejection(error: HttpErrorResponse): Partial<ScenicFlightState> {
  return rejectsTheClub(error.status)
    ? { resolution: clubResolutionFor(error.status) }
    : submitFailureFor(error);
}

/**
 * The anonymous scenic-flight page's state. Feature-scoped (provided by the
 * page, not `providedIn: 'root'`): its lifetime is one visit to one club's form.
 *
 * The wire body carries the registrant and nothing else — the endpoint refuses
 * an unknown property, deliberately, so a day this flow never books cannot be
 * silently dropped and read back as a booked slot.
 */
export const ScenicFlightStore = signalStore(
  withState<ScenicFlightState>(initial),
  withComputed((store) => ({
    formState: computed(() => publicFormState(store.resolution(), store.registration() !== null)),
    /**
     * There is no anonymous read of a club's name — the slug is the only thing
     * the visitor's URL identifies it by until the accepted registration comes
     * back carrying the real name.
     */
    clubHeading: computed(() => store.registration()?.clubName ?? store.clubSlug()),
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
      openClub: rxMethod<string>(
        pipe(tap((clubSlug: string) => patchState(store, { ...initial, clubSlug }))),
      ),
      submit(registrant: PublicRegistrantDetails): void {
        submit({ clubSlug: store.clubSlug(), registrant });
      },
    };
  }),
);
