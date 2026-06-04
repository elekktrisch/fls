import type { DateValue } from './af-date-picker.component';

/**
 * Pure value-bridge between `af-date-picker`'s public `DateValue` model and the
 * array shape `nz-range-picker` consumes via `[ngModel]`.
 *
 * Why this exists — the zoneless deadlock (S-062e). Under zoneless Angular the
 * range picker reads its `[ngModel]` input on every change-detection pass. If
 * that input is a *fresh array reference each pass* (the naive
 * `Array.isArray(v) ? v : []` getter), ng-zorro's internal panel normalisation
 * sees a "new" value, writes back, and schedules another CD pass — a tight
 * self-perpetuating loop that never settles and freezes the main thread
 * (reproduced at /dev/primitives + the /flights range filter). The single
 * picker never hit this because a `Date | null` getter has stable identity.
 *
 * The fix is reference stability: only mint a new array when the *epoch values*
 * actually change. `rangeArray` takes the previously-returned array and returns
 * it unchanged when the new value is equivalent, so the picker's input identity
 * is stable across CD passes and the loop can never form.
 */

const EMPTY: readonly Date[] = Object.freeze([]);

/** Two `DateValue`s are range-equivalent iff both are 2-tuples with equal epochs. */
export function sameRange(a: DateValue, b: DateValue): boolean {
  const at = asTuple(a);
  const bt = asTuple(b);
  if (at === null && bt === null) return true;
  if (at === null || bt === null) return false;
  return at[0].getTime() === bt[0].getTime() && at[1].getTime() === bt[1].getTime();
}

/**
 * Stable array projection for `nz-range-picker`'s `[ngModel]`. Returns `prev`
 * unchanged whenever the incoming value is range-equivalent to it, so the
 * picker input keeps a constant reference across change-detection passes (the
 * S-062e deadlock fix). A null/single value collapses to a shared frozen `[]`.
 */
export function rangeArray(value: DateValue, prev: readonly Date[]): readonly Date[] {
  const tuple = asTuple(value);
  if (tuple === null) return EMPTY;
  if (
    prev.length === 2 &&
    prev[0]!.getTime() === tuple[0].getTime() &&
    prev[1]!.getTime() === tuple[1].getTime()
  ) {
    return prev;
  }
  return [tuple[0], tuple[1]];
}

/** Narrow a raw picker emission (length 0 or 2) back to the `DateValue` tuple. */
export function toRangeValue(next: readonly (Date | null)[]): DateValue {
  return next.length === 2 && next[0] instanceof Date && next[1] instanceof Date
    ? [next[0], next[1]]
    : null;
}

function asTuple(v: DateValue): [Date, Date] | null {
  return Array.isArray(v) && v.length === 2 && v[0] instanceof Date && v[1] instanceof Date
    ? [v[0], v[1]]
    : null;
}
