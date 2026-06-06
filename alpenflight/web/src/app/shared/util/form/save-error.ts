import type { HttpErrorResponse } from '@angular/common/http';

/**
 * Shared form↔request + save-error helpers (S-068 / J-5 T-09).
 *
 * Extracted to KILL the duplicated, high-CRAP `errorPatch` /
 * `formToCreateRequest` / `formToUpdateRequest` pattern that the per-feature
 * `*-edit.page.ts` + `*.store.ts` files grew (fallow flagged
 * `aircraft-edit.page.ts`/`users.store.ts errorPatch` as CRITICAL/HIGH CRAP —
 * see `_BOYSCOUT.md`). A new edit page maps a backend error to an inline string
 * with ONE table lookup instead of an 8-branch `if (status===…)` cascade, and
 * builds a request body with ONE optional-pruning pass instead of ~20 hand
 * `if (v.x !== '') req.x = …` lines. As the aircraft / flights / users hotspots
 * are next touched they fold onto these helpers.
 */

/** A backend domain error body: `{ key, message? }` (e.g. `aircraft.reservation.overlap`). */
export interface ApiErrorBody {
  readonly key?: string;
  readonly message?: string;
  readonly field?: string;
}

/**
 * Map an `HttpErrorResponse` to an inline save-error message.
 *
 * Resolution order:
 *   1. the backend domain `key` (e.g. `aircraft.reservation.overlap`) against
 *      the caller's `keyMessages` table — the conflict/duration cases;
 *   2. a `field`-prefixed backend `message` (validation echo);
 *   3. a per-status fallback in `statusMessages` (e.g. 403);
 *   4. the generic `fallback`.
 *
 * Pure + side-effect free so it unit-tests without a TestBed.
 */
export function mapApiSaveError(
  e: HttpErrorResponse,
  keyMessages: Readonly<Record<string, string>>,
  options: {
    readonly statusMessages?: Readonly<Record<number, string>>;
    readonly fallback?: string;
  } = {},
): string {
  const body = (e.error ?? null) as ApiErrorBody | null;
  const keyed = body?.key ? keyMessages[body.key] : undefined;
  if (keyed) return keyed;
  if (body && typeof body.message === 'string' && body.message.length > 0) {
    return body.field ? `${body.field}: ${body.message}` : body.message;
  }
  const byStatus = options.statusMessages?.[e.status];
  if (byStatus) return byStatus;
  return options.fallback ?? e.message;
}
