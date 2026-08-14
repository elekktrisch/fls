import type { DateValue } from './af-date-picker.component';

const STABLE_EMPTY_RANGE: readonly Date[] = Object.freeze([]);

export function sameRange(a: DateValue, b: DateValue): boolean {
  const at = asTuple(a);
  const bt = asTuple(b);
  if (at === null && bt === null) return true;
  if (at === null || bt === null) return false;
  return at[0].getTime() === bt[0].getTime() && at[1].getTime() === bt[1].getTime();
}

// RENAME: rangeArray -> stableRangeArray
export function rangeArray(value: DateValue, prev: readonly Date[]): readonly Date[] {
  const tuple = asTuple(value);
  if (tuple === null) return STABLE_EMPTY_RANGE;
  if (
    prev.length === 2 &&
    prev[0]!.getTime() === tuple[0].getTime() &&
    prev[1]!.getTime() === tuple[1].getTime()
  ) {
    return prev;
  }
  return [tuple[0], tuple[1]];
}

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
