import { FormControl } from '@angular/forms';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$, mergeFieldErrors } from '@shared/util/form';

import { immatriculationValidators } from './aircraft-edit.page';

/**
 * J-26 T-10 — aircraft as-you-type wiring (representative for batch A).
 *
 * §8 posture: NO `TestBed.createComponent` / DOM assertions. The page binds its
 * `af-form-field [errors]` to `liveFieldErrors(control)`; we prove the
 * page-specific seam — the EXPORTED `immatriculationValidators` stack feeding the
 * shared debounced stream — renders inline WHILE TYPING an invalid value and
 * CLEARS (debounced) once valid, plus the server-duplicate async slot the page
 * routes the 409 through. The generic stream behavior is covered by
 * `inline-validation.spec.ts`; this nails the aircraft immatriculation rules.
 */
describe('aircraft immatriculation live errors (J-26 T-10)', () => {
  let scheduler: TestScheduler;

  beforeEach(() => {
    scheduler = new TestScheduler((actual, expected) => {
      expect(actual).toEqual(expected);
    });
  });

  function immatControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [...immatriculationValidators],
    });
  }

  it('the exported validator stack flags an empty required immatriculation', () => {
    expect(immatControl('').errors).toEqual({ required: true });
  });

  it('flags a single lowercase char — fails minLength(2) AND the upper-only pattern', () => {
    // The e2e representative types `a`; it must be invalid the instant it has
    // content (no blur), so the as-you-type binding has something to render.
    const errors = immatControl('a').errors ?? {};
    expect('minlength' in errors || 'pattern' in errors).toBe(true);
    expect(immatControl('a').valid).toBe(false);
  });

  it('accepts a valid immatriculation (HB-3000)', () => {
    expect(immatControl('HB-3000').errors).toBeNull();
  });

  it('surfaces the live error ~200ms after an invalid value is typed, then clears on a valid one', () => {
    const control = immatControl('HB-3000'); // starts valid
    const seen: (Record<string, unknown> | null)[] = [];

    scheduler.run(({ cold }) => {
      liveFieldErrors$(control, { debounceMs: 200 }).subscribe((e) => seen.push(e));
      // Type an invalid value, then (after the debounce settles) a valid one.
      cold('--a 300ms b|').subscribe((v) => control.setValue(v === 'a' ? 'a' : 'HB-3000'));
    });

    // valid (null) initially → invalid keys (minlength + pattern, both fire on a
    // single lowercase char) → valid (null) again once a good value is typed.
    expect(seen[0]).toBeNull();
    expect(Object.keys(seen[1] ?? {}).sort()).toEqual(['minlength', 'pattern']);
    expect(seen[seen.length - 1]).toBeNull();
  });

  it('merges the server immatriculation-duplicate 409 into the same inline slot (the page async route)', () => {
    // The page sets `immatriculationDuplicate.set({ duplicate: true })` on the
    // store 409 and binds it through `asyncErrors$`. A concurrent client error
    // must never be masked by the duplicate flag.
    expect(mergeFieldErrors({ pattern: true }, { duplicate: true })).toEqual({
      pattern: true,
      duplicate: true,
    });
    expect(mergeFieldErrors(null, { duplicate: true })).toEqual({ duplicate: true });
  });
});
