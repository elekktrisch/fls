import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Validators, type FormControl, type FormGroup } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import { AccountStore } from './account.store';
import { ProfileAccountTab } from './profile-account.tab';

/**
 * Logic test for the Account-tab form definition (J-26 T-08): `languageId`
 * carries `Validators.required` again — the legacy profile form
 * (flsweb `profile.html:61`) marked the language selectize `required`, and the
 * rewrite dropped it (only the server-side `@NotNull` enforced it). No
 * template rendering per the web testing posture (CLAUDE.md §8) — the class is
 * instantiated for its form definition only; the inline error rendering is the
 * Playwright case in `e2e/tests/forms/validation-hardening.spec.ts`.
 */

type AccountFormShape = FormGroup<{
  friendlyName: FormControl<string>;
  notificationEmail: FormControl<string>;
  phoneNumber: FormControl<string>;
  languageId: FormControl<string>;
}>;

function createTab(): ProfileAccountTab {
  // Minimal store stand-in: the constructor reads `view()` (hydration effect)
  // and calls `load()`; nothing else runs without a rendered template.
  const storeStub = {
    view: () => null,
    isLoading: () => false,
    isSaving: () => false,
    hasError: () => false,
    savedOnce: () => false,
    canSave: () => false,
    load: () => undefined,
    save: () => undefined,
    clearError: () => undefined,
  };

  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), { provide: AccountStore, useValue: storeStub }],
  });

  return TestBed.runInInjectionContext(() => new ProfileAccountTab());
}

describe('ProfileAccountTab form definition', () => {
  it('languageId carries the required validator (legacy parity, J-26 T-08)', () => {
    const tab = createTab();
    const form = (tab as unknown as { form: AccountFormShape }).form;

    expect(form.controls.languageId.hasValidator(Validators.required)).toBe(true);
  });

  it('an empty languageId makes the form invalid even when all other fields are valid', () => {
    const tab = createTab();
    const form = (tab as unknown as { form: AccountFormShape }).form;

    form.setValue({
      friendlyName: 'Pia L.',
      notificationEmail: 'pia@club.example',
      phoneNumber: '',
      languageId: '',
    });

    expect(form.controls.languageId.hasError('required')).toBe(true);
    expect(form.invalid).toBe(true);
  });
});
