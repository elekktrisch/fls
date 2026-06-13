import { FormControl, Validators } from '@angular/forms';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$ } from '@shared/util/form';

/**
 * J-26 T-11 — person as-you-type wiring (representative for batch B).
 *
 * §8 posture: NO `TestBed.createComponent` / DOM assertions. The page binds its
 * `af-form-field [errors]` to `liveFieldErrors(control)` for every field. We
 * prove the page-specific seam — the person field validator stacks feeding the
 * shared debounced stream — renders inline WHILE TYPING an invalid value and
 * CLEARS (debounced) once valid. The generic stream behavior is covered by
 * `inline-validation.spec.ts`; this nails the person field rules the e2e case
 * drives (the required lastname + the maxLength-only optional fields the T-11
 * sweep newly bound).
 *
 * The validators mirror persons-edit.page.ts `form` exactly. They are simple
 * enough that re-declaring them here (vs exporting) keeps the page surface lean
 * — the e2e + this spec together pin the behavior.
 */
describe('person edit live errors (J-26 T-11)', () => {
  let scheduler: TestScheduler;

  beforeEach(() => {
    scheduler = new TestScheduler((actual, expected) => {
      expect(actual).toEqual(expected);
    });
  });

  function lastnameControl(initial = ''): FormControl<string> {
    // persons-edit.page.ts: lastname = required + maxLength(100).
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    });
  }

  function memberNumberControl(initial = ''): FormControl<string> {
    // persons-edit.page.ts: memberNumber = maxLength(20) only — previously
    // SILENT (no `[errors]` binding at all); the T-11 sweep bound it.
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.maxLength(20)],
    });
  }

  it('a blank required lastname is invalid the instant it is empty', () => {
    expect(lastnameControl('').errors).toEqual({ required: true });
    expect(lastnameControl('Pilot').errors).toBeNull();
  });

  it('the newly-bound memberNumber surfaces maxLength while typing past 20 chars', () => {
    expect(memberNumberControl('1234567890').errors).toBeNull();
    expect(memberNumberControl('a'.repeat(21)).errors).not.toBeNull();
  });

  it('surfaces the live error ~200ms after the required lastname is cleared, then clears on a valid one', () => {
    const control = lastnameControl('Pilot'); // starts valid
    const seen: (Record<string, unknown> | null)[] = [];

    scheduler.run(({ cold }) => {
      liveFieldErrors$(control, { debounceMs: 200 }).subscribe((e) => seen.push(e));
      // Clear it (invalid: required), then (after the debounce settles) re-type a
      // valid value — exactly the e2e flow (fill('') → assert visible → fill('Pilot')).
      cold('--a 300ms b|').subscribe((v) => control.setValue(v === 'a' ? '' : 'Pilot'));
    });

    // valid (null) initially → required error after the cleared value debounces →
    // valid (null) again once a good value is typed.
    expect(seen[0]).toBeNull();
    expect(seen[1]).toEqual({ required: true });
    expect(seen[seen.length - 1]).toBeNull();
  });
});
