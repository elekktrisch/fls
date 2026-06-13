import type { HttpErrorResponse } from '@angular/common/http';

/**
 * Shared store `errorPatch` classifier (J-26 T-22).
 *
 * The per-feature signal stores grew a near-identical `errorPatch(e, …)`:
 * an HTTP error is classified — by status + problem-detail body — into the
 * `{ saveError, saveErrorKind }` slot the edit page binds inline. Each one was
 * a tall `if (status === 403) { … } if (status === 409) { … } if (400) { … }`
 * cascade (fallow flagged `users.store.ts errorPatch` CRAP 160 / cyc 25 and
 * `aircraft.store.ts errorPatch` as the worst FE hotspots — see `_BOYSCOUT.md`).
 *
 * This replaces the cascade with a declarative ORDERED rule table: each store
 * lists `(status, when?) → (kind, message)` rules; the first whose `status`
 * and optional body-predicate `when` match wins, else the generic `fallback`.
 * The per-status branching, body extraction, and fallback collapse into ONE
 * pure pass — the store keeps only its domain-specific rule list, no control
 * flow. Pure + side-effect free so it unit-tests without a TestBed.
 *
 * Sibling to {@link mapApiSaveError} (which maps to a bare string for the
 * reservation/planning stores); this one carries the discriminant `kind` the
 * aircraft/users edit pages route on (`saveErrorKind`).
 */

/** A backend problem-detail / domain-error body (RFC-7807-ish + legacy `message`). */
export interface ProblemDetailBody {
  readonly type?: string;
  readonly detail?: string;
  readonly message?: string;
  readonly field?: string;
}

/** The classified outcome a store patches: an inline message + its discriminant. */
export interface SaveErrorOutcome<K extends string> {
  readonly saveError: string;
  readonly saveErrorKind: K;
}

/**
 * One classification rule. `status` is the HTTP status it matches; the optional
 * `when` predicate further discriminates on the parsed body (e.g. a 409
 * `detail` substring, or a `type` URN fragment). `outcome` produces the
 * `{ saveError, saveErrorKind }` from the body (and the raw response, so the
 * message can fall back to `e.message`) — a function so the message can fall
 * back to `body.detail` / interpolate. The first matching rule wins, so order
 * narrowest-first.
 */
export interface SaveErrorRule<K extends string> {
  readonly status: number;
  readonly when?: (body: ProblemDetailBody) => boolean;
  readonly outcome: (body: ProblemDetailBody, e: HttpErrorResponse) => SaveErrorOutcome<K>;
}

/** Read the JSON error body off an `HttpErrorResponse` (or `null` when absent). */
export function problemDetailBody(e: HttpErrorResponse): ProblemDetailBody | null {
  return (e.error ?? null) as ProblemDetailBody | null;
}

/**
 * Classify an `HttpErrorResponse` against an ORDERED rule table.
 *
 * Walks `rules` top-to-bottom; the first rule whose `status` equals the
 * response status AND whose `when` (if any) returns true produces the outcome.
 * If nothing matches, `fallback` runs with the parsed body (the generic
 * `body.detail ?? body.message ?? e.message` case each store ended with).
 */
export function classifyApiError<K extends string>(
  e: HttpErrorResponse,
  rules: readonly SaveErrorRule<K>[],
  fallback: (body: ProblemDetailBody | null, e: HttpErrorResponse) => SaveErrorOutcome<K>,
): SaveErrorOutcome<K> {
  const body = problemDetailBody(e);
  for (const rule of rules) {
    if (rule.status !== e.status) continue;
    if (rule.when && !rule.when(body ?? {})) continue;
    return rule.outcome(body ?? {}, e);
  }
  return fallback(body, e);
}

/**
 * The generic last-resort message every store fell back to:
 * `body.detail ?? body.message ?? e.message`. Exposed so a store's `fallback`
 * can reuse it without re-spelling the chain.
 */
export function genericSaveErrorMessage(
  body: ProblemDetailBody | null,
  e: HttpErrorResponse,
): string {
  return body?.detail ?? body?.message ?? e.message;
}
