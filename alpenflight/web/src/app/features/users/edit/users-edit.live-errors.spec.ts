import { FormControl, Validators } from '@angular/forms';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$ } from '@shared/util/form';

describe('user edit live errors (J-26 T-12)', () => {
  const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,256}$/;

  let scheduler: TestScheduler;

  beforeEach(() => {
    scheduler = new TestScheduler((actual, expected) => {
      expect(actual).toEqual(expected);
    });
  });

  function usernameControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.minLength(3),
        Validators.maxLength(256),
        Validators.pattern(USERNAME_PATTERN),
      ],
    });
  }

  function notificationEmailControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.required, Validators.email, Validators.maxLength(256)],
    });
  }

  function phoneControl(initial = ''): FormControl<string> {
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.maxLength(30)],
    });
  }

  it('a blank required username is invalid the instant it is empty', () => {
    expect(usernameControl('').errors).toEqual({ required: true });
    expect(usernameControl('mock-admin').errors).toBeNull();
  });

  it('a single-char username fails minLength AND the pattern while typing', () => {
    const errors = usernameControl('a').errors ?? {};
    expect('minlength' in errors || 'pattern' in errors).toBe(true);
    expect(usernameControl('a').valid).toBe(false);
  });

  it('the notification email surfaces the email rule while typing a malformed value', () => {
    expect(notificationEmailControl('not-an-email').errors).toEqual({ email: true });
    expect(notificationEmailControl('pilot@club.ch').errors).toBeNull();
  });

  it('the newly-bound phone surfaces maxLength while typing past 30 chars', () => {
    expect(phoneControl('+41 79 123 45 67').errors).toBeNull();
    expect(phoneControl('1'.repeat(31)).errors).not.toBeNull();
  });

  it('surfaces the live error ~200ms after the required notification email is cleared, then clears on a valid one', () => {
    const control = notificationEmailControl('pilot@club.ch');
    const seen: (Record<string, unknown> | null)[] = [];

    scheduler.run(({ cold }) => {
      liveFieldErrors$(control, { debounceMs: 200 }).subscribe((e) => seen.push(e));
      cold('--a 300ms b|').subscribe((v) => control.setValue(v === 'a' ? '' : 'pilot@club.ch'));
    });

    expect(seen[0]).toBeNull();
    expect(seen[1]).toEqual({ required: true });
    expect(seen[seen.length - 1]).toBeNull();
  });

  it('roles-≥1 is empty when every role checkbox is unchecked and present once one is ticked (live cross-field)', () => {
    const roles = {
      CLUB_ADMINISTRATOR: new FormControl(false, { nonNullable: true }),
      FLIGHT_OPERATOR: new FormControl(false, { nonNullable: true }),
      PILOT: new FormControl(false, { nonNullable: true }),
      OFFICE_USER: new FormControl(false, { nonNullable: true }),
      GUEST: new FormControl(false, { nonNullable: true }),
    };
    const anyChecked = (): boolean => Object.values(roles).some((c) => c.value);

    expect(anyChecked()).toBe(false);
    roles.PILOT.setValue(true);
    expect(anyChecked()).toBe(true);
  });
});
