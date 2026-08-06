import type { HttpErrorResponse } from '@angular/common/http';

import type { PublicClubResponse } from '@api/generated/model';

import type { PublicFormState, PublicSubmitFailure } from './public-form-shell.component';

/**
 * How an anonymous registration URL is derived into what the shared shell shows.
 * Both public flows answer the same club-resolution and rejection contract, so
 * the derivation is one implementation rather than one per page.
 */

/** What the club slug in the URL turned out to be. */
export type ClubResolution = 'loading' | 'ready' | 'not-found' | 'unavailable';

/**
 * Only a 404 says "no club is published at that slug". A 403 (registration
 * closed) and anything else the call can fail with leave the visitor unable to
 * register through this URL, which is what the unavailable panel states — and
 * it is the safe direction: an unreachable club must not present a form whose
 * submit the server would reject anyway.
 */
export function clubResolutionFor(status: number): ClubResolution {
  return status === 404 ? 'not-found' : 'unavailable';
}

/**
 * What the anonymous club read turned into: the name to head the form with, or
 * the reason there is no form to head. Both flows read the club before rendering
 * anything, so a bad or disabled slug is answered before a visitor types.
 */
export interface ClubReadOutcome {
  readonly clubName: string | null;
  readonly resolution: ClubResolution;
}

export function clubRead(club: PublicClubResponse): ClubReadOutcome {
  return { clubName: club.clubName ?? null, resolution: 'ready' };
}

export function clubReadRejection(error: HttpErrorResponse): ClubReadOutcome {
  return { clubName: null, resolution: clubResolutionFor(error.status) };
}

/**
 * The URL slug stands in for the club only while its name is still being read —
 * a slug where a club name belongs is what this public screen must not settle on.
 */
export function clubHeadingFor(clubName: string | null, clubSlug: string): string {
  return clubName ?? clubSlug;
}

/** A rejection that leaves the form open for another attempt. */
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

/** True for the statuses that adjudicate the slug itself, not the submission. */
export function rejectsTheClub(status: number): boolean {
  return status === 404 || status === 403;
}

/** A registration in hand outranks the club lookup: the form is done with. */
export function publicFormState(resolution: ClubResolution, registered: boolean): PublicFormState {
  return registered ? 'success' : resolution;
}
