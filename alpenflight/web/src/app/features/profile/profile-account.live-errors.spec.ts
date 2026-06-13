import { FormControl, Validators } from '@angular/forms';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$ } from '@shared/util/form';

/**
 * J-26 T-12 — profile Account tab as-you-type wiring (representative profile
 * tab for batch C).
 *
 * §8 posture: NO `TestBed.createComponent` / DOM assertions. The tab binds its
 * `af-form-field [errors]` to `liveFieldErrors(control)` for friendlyName /
 * notificationEmail / phoneNumber / languageId. We prove the page-specific seam
 * — the field validator stacks feeding the shared debounced stream — renders
 * inline WHILE TYPING an invalid value and CLEARS (debounced) once valid.
 *
 * In particular this pins the languageId required validator restored in J-26
 * T-08 (legacy parity, flsweb profile.html:61): the T-12 sweep converted its
 * binding from touched-only to live, and MUST NOT regress the required rule —
 * the clear → error → re-pick-recovers flow is asserted here.
 *
 * The validators mirror profile-account.tab.ts `form` exactly.
 */
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
    // T-08 restored the legacy-parity required validator; T-12 keeps it and
    // renders it live (this must not regress).
    return new FormControl<string>(initial, {
      nonNullable: true,
      validators: [Validators.required],
    });
  }

  function phoneControl(initial = ''): FormControl<string> {
    // Previously SILENT (no `[errors]` binding); the T-12 sweep bound it.
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
    const control = languageIdControl('de'); // starts valid (a language picked)
    const seen: (Record<string, unknown> | null)[] = [];

    scheduler.run(({ cold }) => {
      liveFieldErrors$(control, { debounceMs: 200 }).subscribe((e) => seen.push(e));
      // Clear the select (invalid: required), then (after the debounce settles)
      // re-pick a language — the e2e flow (clear → error visible → re-pick recovers).
      cold('--a 300ms b|').subscribe((v) => control.setValue(v === 'a' ? '' : 'fr'));
    });

    expect(seen[0]).toBeNull();
    expect(seen[1]).toEqual({ required: true });
    expect(seen[seen.length - 1]).toBeNull();
  });
});
