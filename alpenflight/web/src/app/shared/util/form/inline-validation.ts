import { type Signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import type { AbstractControl, ValidationErrors } from '@angular/forms';
import {
  type Observable,
  combineLatest,
  debounceTime,
  distinctUntilChanged,
  map,
  merge,
  of,
  startWith,
} from 'rxjs';


const DEFAULT_DEBOUNCE_MS = 200;

export interface LiveErrorsOptions {
  readonly debounceMs?: number;
  readonly asyncErrors$?: Observable<ValidationErrors | null>;
}

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

export function liveFieldErrors$(
  control: AbstractControl,
  options: LiveErrorsOptions = {},
): Observable<ValidationErrors | null> {
  const debounceMs = options.debounceMs ?? DEFAULT_DEBOUNCE_MS;
  const client$ = merge(control.valueChanges, control.root.statusChanges).pipe(
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

export function liveFieldErrors(
  control: AbstractControl,
  options: LiveErrorsOptions = {},
): Signal<ValidationErrors | null> {
  const initial = untracked(() => control.errors);
  return toSignal(liveFieldErrors$(control, options), { initialValue: initial });
}

function sameErrors(a: ValidationErrors | null, b: ValidationErrors | null): boolean {
  if (a === b) return true;
  if (a === null || b === null) return false;
  const ak = Object.keys(a);
  const bk = Object.keys(b);
  if (ak.length !== bk.length) return false;
  return ak.every((k) => k in b && a[k] === b[k]);
}
