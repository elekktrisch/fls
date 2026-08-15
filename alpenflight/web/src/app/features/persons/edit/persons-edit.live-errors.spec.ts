import { FormControl, Validators } from '@angular/forms';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$ } from '@shared/util/form';

describe('person edit live errors (J-26 T-11)', () => {
  let scheduler: TestScheduler;

  beforeEach(() => {
    scheduler = new TestScheduler((actual, expected) => {
      expect(actual).toEqual(expected);
    });
  });

  function lastnameControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    });
  }

  function memberNumberControl(initial = ''): FormControl<string> {
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
    const control = lastnameControl('Pilot');
    const seen: (Record<string, unknown> | null)[] = [];

    scheduler.run(({ cold }) => {
      liveFieldErrors$(control, { debounceMs: 200 }).subscribe((e) => seen.push(e));
      cold('--a 300ms b|').subscribe((v) => control.setValue(v === 'a' ? '' : 'Pilot'));
    });

    expect(seen[0]).toBeNull();
    expect(seen[1]).toEqual({ required: true });
    expect(seen[seen.length - 1]).toBeNull();
  });
});
