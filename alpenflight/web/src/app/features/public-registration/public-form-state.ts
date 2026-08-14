import type { HttpErrorResponse } from '@angular/common/http';

import type { PublicClubResponse } from '@api/generated/model';

import type { PublicFormState, PublicSubmitFailure } from './public-form-shell.component';

export type ClubResolution = 'loading' | 'ready' | 'not-found' | 'unavailable';

export function clubResolutionFor(status: number): ClubResolution {
  return status === 404 ? 'not-found' : 'unavailable';
}

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

export function clubHeadingFor(clubName: string | null, clubSlug: string): string {
  return clubName ?? clubSlug;
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

export function rejectsTheClub(status: number): boolean {
  return status === 404 || status === 403;
}

export function publicFormState(resolution: ClubResolution, registered: boolean): PublicFormState {
  return registered ? 'success' : resolution;
}
