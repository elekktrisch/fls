import { describe, expect, it } from 'vitest';

import type { SubmitError } from './join.store';

import {
  JOIN_CODE_UNAMBIGUOUS_ALPHABET,
  JOIN_CODE_LENGTH,
  JOIN_NOTE_MAX,
  countdownRemaining,
  errorView,
  noteRemaining,
  sanitizeJoinCode,
} from './join-page.logic';

describe('sanitizeJoinCode', () => {
  it('uppercases and keeps alphabet characters', () => {
    expect(sanitizeJoinCode('abcd2345')).toBe('ABCD2345');
  });

  it('strips characters outside the unambiguous alphabet (I, O, 0, 1)', () => {
    expect(sanitizeJoinCode('AIO10BCD')).toBe('ABCD');
  });

  it('drops whitespace and punctuation pasted from a chat message', () => {
    expect(sanitizeJoinCode(' ab-cd 23 ')).toBe('ABCD23');
  });

  it('truncates to the 8-char code length', () => {
    expect(sanitizeJoinCode('ABCD2345EXTRA')).toBe('ABCD2345');
    expect(sanitizeJoinCode('ABCD2345EXTRA').length).toBe(JOIN_CODE_LENGTH);
  });

  it('every alphabet character survives sanitization', () => {
    for (const ch of JOIN_CODE_UNAMBIGUOUS_ALPHABET) {
      expect(sanitizeJoinCode(ch)).toBe(ch);
    }
  });
});

describe('noteRemaining', () => {
  it('reports the full budget for an empty note', () => {
    expect(noteRemaining('')).toBe(JOIN_NOTE_MAX);
  });

  it('counts down as the note grows', () => {
    expect(noteRemaining('hello')).toBe(JOIN_NOTE_MAX - 5);
  });

  it('never goes negative when over the cap', () => {
    expect(noteRemaining('x'.repeat(JOIN_NOTE_MAX + 50))).toBe(0);
  });
});

describe('countdownRemaining', () => {
  const now = (s: number): number => s * 1000;

  it('returns the full Retry-After window at the moment the error arrives', () => {
    expect(countdownRemaining({ startedAtMs: now(100), retryAfterSeconds: 120 }, now(100))).toBe(
      120,
    );
  });

  it('ticks down as wall-clock advances', () => {
    expect(countdownRemaining({ startedAtMs: now(100), retryAfterSeconds: 120 }, now(130))).toBe(
      90,
    );
  });

  it('floors at zero once the window has elapsed (submit re-enabled)', () => {
    expect(countdownRemaining({ startedAtMs: now(100), retryAfterSeconds: 120 }, now(300))).toBe(0);
  });
});

describe('errorView', () => {
  const err = (kind: SubmitError['kind'], retryAfterSeconds?: number): SubmitError => ({
    kind,
    message: 'msg',
    ...(retryAfterSeconds !== undefined ? { retryAfterSeconds } : {}),
  });

  it('renders nothing when there is no error', () => {
    const v = errorView(null);
    expect(v.showInline).toBe(false);
    expect(v.showCountdown).toBe(false);
    expect(v.submitDisabled).toBe(false);
  });

  it('shows an inline field error for an unknown code', () => {
    const v = errorView(err('unknown-code'));
    expect(v.showInline).toBe(true);
    expect(v.showCountdown).toBe(false);
    expect(v.submitDisabled).toBe(false);
  });

  it('shows the already-member message without disabling submit', () => {
    const v = errorView(err('already-member'));
    expect(v.showInline).toBe(true);
    expect(v.message).toBe('msg');
    expect(v.submitDisabled).toBe(false);
  });

  it('shows a countdown and disables submit while a rate-limit window is live', () => {
    const v = errorView(err('rate-limited', 30), 12);
    expect(v.showCountdown).toBe(true);
    expect(v.submitDisabled).toBe(true);
    expect(v.countdownSeconds).toBe(12);
  });

  it('re-enables submit once the rate-limit countdown reaches zero', () => {
    const v = errorView(err('rate-limited', 30), 0);
    expect(v.showCountdown).toBe(false);
    expect(v.submitDisabled).toBe(false);
  });
});
