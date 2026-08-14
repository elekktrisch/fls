import { describe, expect, it } from 'vitest';

import { rangeArray, sameRange, toRangeValue } from './date-value-bridge';

const d = (iso: string): Date => new Date(iso);

describe('date-value-bridge — zoneless reference stability (S-062e)', () => {
  describe('rangeArray', () => {
    it('returns the SAME reference when the value is range-equivalent to prev', () => {
      const prev = [d('2026-01-01'), d('2026-01-31')];
      const next = rangeArray([d('2026-01-01'), d('2026-01-31')], prev);
      expect(next).toBe(prev);
    });

    it('mints a new array only when an epoch actually changes', () => {
      const prev = [d('2026-01-01'), d('2026-01-31')];
      const next = rangeArray([d('2026-01-01'), d('2026-02-15')], prev);
      expect(next).not.toBe(prev);
      expect((next[1] as Date).getTime()).toBe(d('2026-02-15').getTime());
    });

    it('collapses null to a shared frozen empty array (stable across calls)', () => {
      const a = rangeArray(null, []);
      const b = rangeArray(null, a);
      expect(a).toBe(b);
      expect(a.length).toBe(0);
    });

    it('collapses a single Date (wrong mode) to the empty array', () => {
      expect(rangeArray(d('2026-01-01'), []).length).toBe(0);
    });
  });

  describe('sameRange', () => {
    it('is true for equal-epoch tuples', () => {
      expect(
        sameRange([d('2026-01-01'), d('2026-01-31')], [d('2026-01-01'), d('2026-01-31')]),
      ).toBe(true);
    });
    it('is false when one epoch differs', () => {
      expect(
        sameRange([d('2026-01-01'), d('2026-01-31')], [d('2026-01-01'), d('2026-02-01')]),
      ).toBe(false);
    });
    it('is true for null/null and false for null/tuple', () => {
      expect(sameRange(null, null)).toBe(true);
      expect(sameRange(null, [d('2026-01-01'), d('2026-01-31')])).toBe(false);
    });
  });

  describe('toRangeValue', () => {
    it('narrows a length-2 emission to a [from,to] tuple', () => {
      const v = toRangeValue([d('2026-01-01'), d('2026-01-31')]);
      expect(Array.isArray(v)).toBe(true);
      expect((v as [Date, Date])[0].getTime()).toBe(d('2026-01-01').getTime());
    });
    it('narrows a cleared (length-0) emission to null', () => {
      expect(toRangeValue([])).toBeNull();
    });
  });
});
