import type { SubmitError } from './join.store';

export const JOIN_CODE_LENGTH = 8;

export const JOIN_NOTE_MAX = 500;

export const JOIN_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const ALLOWED = new Set(JOIN_CODE_ALPHABET);

export function sanitizeJoinCode(raw: string): string {
  let out = '';
  for (const ch of raw.toUpperCase()) {
    if (ALLOWED.has(ch)) {
      out += ch;
      if (out.length === JOIN_CODE_LENGTH) break;
    }
  }
  return out;
}

export function noteRemaining(note: string): number {
  return Math.max(0, JOIN_NOTE_MAX - note.length);
}

export interface RateLimitWindow {
  readonly startedAtMs: number;
  readonly retryAfterSeconds: number;
}

export function countdownRemaining(window: RateLimitWindow, nowMs: number): number {
  const elapsed = Math.floor((nowMs - window.startedAtMs) / 1000);
  return Math.max(0, window.retryAfterSeconds - elapsed);
}

export interface ErrorView {
  readonly showInline: boolean;
  readonly showCountdown: boolean;
  readonly submitDisabled: boolean;
  readonly message: string;
  readonly countdownSeconds: number;
}

const EMPTY: ErrorView = {
  showInline: false,
  showCountdown: false,
  submitDisabled: false,
  message: '',
  countdownSeconds: 0,
};

export function errorView(error: SubmitError | null, countdownSeconds = 0): ErrorView {
  if (!error) return EMPTY;
  if (error.kind === 'rate-limited') {
    const live = countdownSeconds > 0;
    return {
      showInline: false,
      showCountdown: live,
      submitDisabled: live,
      message: error.message,
      countdownSeconds,
    };
  }
  return {
    showInline: true,
    showCountdown: false,
    submitDisabled: false,
    message: error.message,
    countdownSeconds: 0,
  };
}
