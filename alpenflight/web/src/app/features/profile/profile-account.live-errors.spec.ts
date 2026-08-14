import { FormControl, Validators } from '@angular/forms';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$ } from '@shared/util/form';

describe('profile account live errors (J-26 T-12)', () => {
  let scheduler: TestScheduler;

  beforeEach(() => {
    scheduler = new TestScheduler((actual, expected) => {
      expect(actual).toEqual(expected);
    });
  });

  function friendlyNameControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    });
  }

  function notificationEmailControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.required, Validators.email, Validators.maxLength(256)],
    });
  }

  function languageIdControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.required],
    });
  }

  function phoneControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.maxLength(30)],
    });
  }

  it('a blank required friendlyName is invalid the instant it is empty', () => {
    expect(friendlyNameControl('').errors).toEqual({ required: true });
    expect(friendlyNameControl('Anna Pilot').errors).toBeNull();
  });

  it('the notification email surfaces the email rule while typing a malformed value', () => {
    expect(notificationEmailControl('nope').errors).toEqual({ email: true });
    expect(notificationEmailControl('anna@club.ch').errors).toBeNull();
  });

  it('the newly-bound phone surfaces maxLength while typing past 30 chars', () => {
    expect(phoneControl('+41 79 000 00 00').errors).toBeNull();
    expect(phoneControl('9'.repeat(31)).errors).not.toBeNull();
  });

  it('languageId required (T-08 parity) stays enforced and is invalid when cleared', () => {
    expect(languageIdControl('').errors).toEqual({ required: true });
    expect(languageIdControl('de').errors).toBeNull();
  });

  it('surfaces the languageId required error ~200ms after clearing, then clears on a re-pick (T-08 not regressed)', () => {
    const control = languageIdControl('de');
    const seen: (Record<string, unknown> | null)[] = [];

    scheduler.run(({ cold }) => {
      liveFieldErrors$(control, { debounceMs: 200 }).subscribe((e) => seen.push(e));
      cold('--a 300ms b|').subscribe((v) => control.setValue(v === 'a' ? '' : 'fr'));
    });

    expect(seen[0]).toBeNull();
    expect(seen[1]).toEqual({ required: true });
    expect(seen[seen.length - 1]).toBeNull();
  });
});
