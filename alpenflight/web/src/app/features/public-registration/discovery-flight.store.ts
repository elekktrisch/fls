import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import type {
  DiscoveryFlightRegistrationResponse,
  PublicRegistrantDetails,
} from '@api/generated/model';
import { PublicRegistrationService } from '@api/generated/public-registration/public-registration.service';

import type { PublicFormState, PublicSubmitFailure } from './public-form-shell.component';

/** What the club slug in the URL turned out to be. */
export type ClubResolution = 'loading' | 'ready' | 'not-found' | 'unavailable';

/**
 * Only a 404 says "no club is published at that slug". A 403 (registration
 * closed) and anything else the read can fail with leave the visitor unable to
 * register through this URL, which is what the unavailable panel states — and
 * it is the safe direction: an unreachable club must not present a form whose
 * submit the server would reject anyway.
 */
export function clubResolutionFor(status: number): ClubResolution {
  return status === 404 ? 'not-found' : 'unavailable';
}

export interface SubmitOutcome {
  readonly failure: PublicSubmitFailure;
  readonly retryAfterSeconds: number;
}

export function submitFailureFor(error: HttpErrorResponse): SubmitOutcome {
  if (error.status !== 429) return { failure: 'failed', retryAfterSeconds: 0 };
  return { failure: 'throttled', retryAfterSeconds: retryAfterSecondsOf(error) };
}

function retryAfterSecondsOf(error: HttpErrorResponse): number {
  const header = error.headers?.get('Retry-After') ?? '';
  const seconds = Number.parseInt(header, 10);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : 0;
}

/** A registration in hand outranks the club lookup: the form is done with. */
export function publicFormState(resolution: ClubResolution, registered: boolean): PublicFormState {
  return registered ? 'success' : resolution;
}

interface DiscoveryFlightState {
  clubSlug: string;
  days: readonly string[];
  resolution: ClubResolution;
  submitting: boolean;
  registration: DiscoveryFlightRegistrationResponse | null;
  failure: PublicSubmitFailure | null;
  retryAfterSeconds: number;
}

const initial: DiscoveryFlightState = {
  clubSlug: '',
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

/**
 * The anonymous discovery-flight page's state. Feature-scoped (provided by the
 * page, not `providedIn: 'root'`): its lifetime is one visit to one club's form.
 *
 * The published-days read doubles as the club lookup — it is the only anonymous
 * read of a club, and it carries the same 404 / 403 contract as the submit, so
 * a slug that cannot be registered against never renders a form.
 */
export const DiscoveryFlightStore = signalStore(
  withState<DiscoveryFlightState>(initial),
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
            api.listPublicDiscoveryFlightDays(clubSlug).pipe(
              tapResponse({
                // An empty list is a club that has published nothing yet, not a
                // failure: the form still renders and the picker says so.
                next: (days: string[]) => patchState(store, { days, resolution: 'ready' }),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { days: [], resolution: clubResolutionFor(e.status) }),
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
