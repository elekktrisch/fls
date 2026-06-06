import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';

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
    // 0 and false are meaningful (not "empty"); only '' / null / undefined drop.
    expect(out).toEqual({ x: 1, c: 0, d: false });
  });
});
