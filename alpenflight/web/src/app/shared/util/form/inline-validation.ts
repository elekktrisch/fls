import { type Signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import type { AbstractControl, ValidationErrors } from '@angular/forms';
import {
  type Observable,
  combineLatest,
  debounceTime,
  distinctUntilChanged,
  map,
  of,
  startWith,
} from 'rxjs';

/**
 * Shared inline-validation infra (J-6b T-03).
 *
 * Drives per-field error display off `valueChanges` **debounced ~200ms** so a
 * field shows its client-side validation message WHILE the user types and
 * clears it (debounced) once the value becomes valid — replacing the
 * touched-only wiring the edit pages grew (`[errors]="ctl.touched ? ctl.errors
 * : null"` shows nothing until blur/submit; `planning-edit.page.ts` /
 * `reservation-edit.page.ts`).
 *
 * Two layers, so the time-dependent stream and the pure merge logic unit-test
 * without a `TestBed` (alpenflight/web CLAUDE.md §8):
 *
 *   - {@link mergeFieldErrors} — pure: combine the control's client-side
 *     validator errors with an optional async/server error into ONE
 *     `ValidationErrors` slot. This is the extension point T-06/T-07 plug their
 *     `…/validate` endpoint result into (the async error merges into the same
 *     inline slot the client rules render in).
 *   - {@link liveFieldErrors$} — builds the debounced
 *     `Observable<ValidationErrors | null>` from the control. Pure stream wiring
 *     (no signals, no injection context) → deterministically testable with
 *     RxJS `TestScheduler`.
 *
 * The component boundary uses {@link liveFieldErrors}, which bridges the stream
 * to a signal via `toSignal` — zoneless-safe, no `setTimeout`-driven view
 * updates (CLAUDE.md §4). The result feeds `<af-field-errors [errors]>` /
 * `<af-form-field [errors]>` unchanged — those molecules already render an
 * arbitrary `ValidationErrors` slot, so no DOM change is needed here.
 */

const DEFAULT_DEBOUNCE_MS = 200;

/** Options for the live-error stream / signal. */
export interface LiveErrorsOptions {
  /** Debounce applied to value changes before re-deriving errors. Default 200ms. */
  readonly debounceMs?: number;
  /**
   * An optional source of async/server-side errors to merge into the same
   * inline slot (the T-06/T-07 `…/validate` extension point). When it emits a
   * non-null `ValidationErrors`, those keys are merged onto the field's
   * client-side errors. Emit `null` to clear the server error.
   */
  readonly asyncErrors$?: Observable<ValidationErrors | null>;
}

/**
 * Merge a control's client-side validator errors with an optional async/server
 * error into a single `ValidationErrors` slot (or `null` when both are empty).
 *
 * Client keys come first (registration order — the first failing trivial rule
 * is the one a user is mid-fixing), then the async/server keys; a server key
 * never masks a client key of the same name. Pure + side-effect free.
 */
export function mergeFieldErrors(
  clientErrors: ValidationErrors | null | undefined,
  asyncErrors: ValidationErrors | null | undefined,
): ValidationErrors | null {
  const merged: ValidationErrors = { ...(clientErrors ?? {}) };
  if (asyncErrors) {
    for (const [k, v] of Object.entries(asyncErrors)) {
      if (!(k in merged)) merged[k] = v;
    }
  }
  return Object.keys(merged).length > 0 ? merged : null;
}

/**
 * Build the debounced live-errors stream for a control.
 *
 * Emits the control's current `errors` (merged with any `asyncErrors$`) on each
 * value change, debounced by `debounceMs` (~200ms) so the message tracks typing
 * without thrashing. Starts with the control's initial errors so a field that
 * is already invalid (e.g. a freshly opened empty required field that submit
 * touched) renders immediately, and de-dupes identical emissions.
 *
 * Pure stream wiring — no signals, no injection context — so it is testable
 * with RxJS `TestScheduler`.
 */
export function liveFieldErrors$(
  control: AbstractControl,
  options: LiveErrorsOptions = {},
): Observable<ValidationErrors | null> {
  const debounceMs = options.debounceMs ?? DEFAULT_DEBOUNCE_MS;
  const client$ = control.valueChanges.pipe(
    debounceTime(debounceMs),
    startWith(null),
    map(() => control.errors),
  );
  const async$ = (options.asyncErrors$ ?? of<ValidationErrors | null>(null)).pipe(startWith(null));
  return combineLatest([client$, async$]).pipe(
    map(([clientErrors, asyncErrors]) => mergeFieldErrors(clientErrors, asyncErrors)),
    distinctUntilChanged(sameErrors),
  );
}

/**
 * Component-boundary factory: a `Signal<ValidationErrors | null>` that tracks a
 * control's errors WHILE TYPING, debounced ~200ms, merged with any async/server
 * error. Bind it straight into `<af-form-field [errors]>` / `<af-field-errors
 * [errors]>`.
 *
 * Must be called in an injection context (a component field initializer or
 * `constructor`) — `toSignal` needs the `DestroyRef` to tear the subscription
 * down. The initial value is the control's current errors (read untracked) so
 * the signal is correct synchronously before the first debounced emission.
 */
export function liveFieldErrors(
  control: AbstractControl,
  options: LiveErrorsOptions = {},
): Signal<ValidationErrors | null> {
  const initial = untracked(() => control.errors);
  return toSignal(liveFieldErrors$(control, options), { initialValue: initial });
}

/** Shallow structural equality for two error objects (keys + top-level values). */
function sameErrors(a: ValidationErrors | null, b: ValidationErrors | null): boolean {
  if (a === b) return true;
  if (a === null || b === null) return false;
  const ak = Object.keys(a);
  const bk = Object.keys(b);
  if (ak.length !== bk.length) return false;
  return ak.every((k) => k in b && a[k] === b[k]);
}
