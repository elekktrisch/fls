import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';

import {
  classifyApiError,
  genericSaveErrorMessage,
  problemDetailBody,
  type SaveErrorRule,
} from './error-patch';
import { mapApiSaveError } from './save-error';
import { withOptionals } from './optional-fields';

describe('mapApiSaveError', () => {
  const keyMessages = {
    'aircraft.reservation.overlap': 'This aircraft is already reserved for an overlapping period.',
    'aircraft.reservation.duration': 'End must be after start.',
  };

  it('maps a known domain key to its inline message (409 overlap)', () => {
    const e = new HttpErrorResponse({
      status: 409,
      error: { key: 'aircraft.reservation.overlap' },
    });
    expect(mapApiSaveError(e, keyMessages)).toBe(
      'This aircraft is already reserved for an overlapping period.',
    );
  });

  it('maps the 422 duration key', () => {
    const e = new HttpErrorResponse({
      status: 422,
      error: { key: 'aircraft.reservation.duration' },
    });
    expect(mapApiSaveError(e, keyMessages)).toBe('End must be after start.');
  });

  it('falls back to a field-prefixed backend message when no key matches', () => {
    const e = new HttpErrorResponse({
      status: 400,
      error: { field: 'start', message: 'must not be null' },
    });
    expect(mapApiSaveError(e, keyMessages)).toBe('start: must not be null');
  });

  it('uses a per-status fallback (403) when there is no key or message', () => {
    const e = new HttpErrorResponse({ status: 403, error: {} });
    expect(mapApiSaveError(e, keyMessages, { statusMessages: { 403: 'Not allowed.' } })).toBe(
      'Not allowed.',
    );
  });

  it('uses the generic fallback as a last resort', () => {
    const e = new HttpErrorResponse({ status: 500, error: null });
    expect(mapApiSaveError(e, keyMessages, { fallback: 'Save failed.' })).toBe('Save failed.');
  });
});

describe('classifyApiError', () => {
  type Kind = 'forbidden' | 'conflict-self' | 'validation' | 'other';

  const rules: readonly SaveErrorRule<Kind>[] = [
    {
      status: 403,
      outcome: (b) => ({ saveError: b.detail ?? 'Not allowed.', saveErrorKind: 'forbidden' }),
    },
    {
      status: 409,
      when: (b) => /self/i.test(b.detail ?? ''),
      outcome: (b) => ({ saveError: b.detail ?? '', saveErrorKind: 'conflict-self' }),
    },
    {
      status: 400,
      outcome: (b) => ({ saveError: b.detail ?? b.message ?? '', saveErrorKind: 'validation' }),
    },
  ];

  const fallback = (b: ReturnType<typeof problemDetailBody>, e: HttpErrorResponse) => ({
    saveError: genericSaveErrorMessage(b, e),
    saveErrorKind: 'other' as Kind,
  });

  it('matches the first rule whose status equals the response status', () => {
    const e = new HttpErrorResponse({ status: 403, error: { detail: 'No.' } });
    expect(classifyApiError(e, rules, fallback)).toEqual({
      saveError: 'No.',
      saveErrorKind: 'forbidden',
    });
  });

  it('skips a status-matching rule whose `when` predicate fails, then falls through', () => {
    const e = new HttpErrorResponse({ status: 409, error: { detail: 'totally different' } });
    expect(classifyApiError(e, rules, fallback)).toEqual({
      saveError: 'totally different',
      saveErrorKind: 'other',
    });
  });

  it('honours rule order: a passing `when` wins over the generic fallback', () => {
    const e = new HttpErrorResponse({
      status: 409,
      error: { detail: 'a user cannot self-delete' },
    });
    expect(classifyApiError(e, rules, fallback).saveErrorKind).toBe('conflict-self');
  });

  it('falls back to body.detail ?? body.message ?? e.message when no rule matches', () => {
    const e = new HttpErrorResponse({ status: 500, error: { message: 'boom' } });
    expect(classifyApiError(e, rules, fallback)).toEqual({
      saveError: 'boom',
      saveErrorKind: 'other',
    });
  });

  it('passes an empty body object to predicates/outcomes when error body is null', () => {
    const e = new HttpErrorResponse({ status: 400, error: null });
    expect(classifyApiError(e, rules, fallback)).toEqual({
      saveError: '',
      saveErrorKind: 'validation',
    });
  });
});

describe('withOptionals', () => {
  it('keeps the base fields and only the non-empty optionals', () => {
    const out = withOptionals(
      { aircraftId: 'a', start: 's', end: 'e' },
      { secondCrewPersonId: '', reservationTypeId: 'rt-1', remarks: undefined },
    );
    expect(out).toEqual({ aircraftId: 'a', start: 's', end: 'e', reservationTypeId: 'rt-1' });
    expect('secondCrewPersonId' in out).toBe(false);
    expect('remarks' in out).toBe(false);
  });

  it('drops null + empty-string optionals, keeps falsy-but-meaningful values', () => {
    const out = withOptionals({ x: 1 }, { a: null, b: '', c: 0, d: false });
    expect(out).toEqual({ x: 1, c: 0, d: false });
  });
});
