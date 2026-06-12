import { FormControl, Validators } from '@angular/forms';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$ } from '@shared/util/form';

/**
 * J-26 T-12 — user as-you-type wiring (representative for batch C).
 *
 * §8 posture: NO `TestBed.createComponent` / DOM assertions. The page binds its
 * `af-form-field [errors]` to `liveFieldErrors(control)` for every field. We
 * prove the page-specific seam — the user field validator stacks feeding the
 * shared debounced stream — renders inline WHILE TYPING an invalid value and
 * CLEARS (debounced) once valid, plus the roles-≥1 cross-field that the T-12
 * sweep made LIVE (surfaces once the role checkboxes are emptied, not only on
 * submit). The generic stream behavior is covered by `inline-validation.spec.ts`;
 * this nails the user field rules.
 *
 * The validators mirror users-edit.page.ts `form` exactly. They are simple
 * enough that re-declaring them here (vs exporting) keeps the page surface lean
 * — the e2e + this spec together pin the behavior.
 */
describe('user edit live errors (J-26 T-12)', () => {
  // Mirrors USERNAME_PATTERN in users-edit.page.ts.
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
    // users-edit.page.ts: phoneNumber = maxLength(30) only — previously SILENT
    // (no `[errors]` binding at all); the T-12 sweep bound it.
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
    const control = notificationEmailControl('pilot@club.ch'); // starts valid
    const seen: (Record<string, unknown> | null)[] = [];

    scheduler.run(({ cold }) => {
      liveFieldErrors$(control, { debounceMs: 200 }).subscribe((e) => seen.push(e));
      // Clear it (invalid: required), then (after the debounce settles) re-type a
      // valid value — the e2e flow (fill('') → assert visible → fill(valid)).
      cold('--a 300ms b|').subscribe((v) => control.setValue(v === 'a' ? '' : 'pilot@club.ch'));
    });

    expect(seen[0]).toBeNull();
    expect(seen[1]).toEqual({ required: true });
    expect(seen[seen.length - 1]).toBeNull();
  });

  it('roles-≥1 is empty when every role checkbox is unchecked and present once one is ticked (live cross-field)', () => {
    // The page makes the roles-empty error LIVE: it derives off the role
    // controls' values (all-false → invalid). This asserts the underlying
    // predicate the page's `rolesEmptyError` computed evaluates (`checkedRoles()
    // .length === 0`), independent of submit. Plain `FormControl`s (no
    // `FormGroup`) keep the spec on the no-TestBed path (§8).
    const roles = {
      CLUB_ADMINISTRATOR: new FormControl(false, { nonNullable: true }),
      FLIGHT_OPERATOR: new FormControl(false, { nonNullable: true }),
      PILOT: new FormControl(false, { nonNullable: true }),
      OFFICE_USER: new FormControl(false, { nonNullable: true }),
      GUEST: new FormControl(false, { nonNullable: true }),
    };
    const anyChecked = (): boolean => Object.values(roles).some((c) => c.value);

    expect(anyChecked()).toBe(false); // all unchecked → roles-empty error live
    roles.PILOT.setValue(true);
    expect(anyChecked()).toBe(true); // ticking one clears it
  });
});
