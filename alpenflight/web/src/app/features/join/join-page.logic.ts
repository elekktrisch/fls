import type { SubmitError } from './join.store';

/** Length of a club join code (S-179 field spec). */
export const JOIN_CODE_LENGTH = 8;

/** Optional join-note cap (S-179 field spec). */
export const JOIN_NOTE_MAX = 500;

/**
 * The join-code alphabet — uppercase letters and digits with the visually
 * ambiguous glyphs (I, O, 0, 1) removed, so a code read off a screen or a chat
 * message can't be mistyped. The producer (`Club.rotateJoinCode`) draws from
 * the same set; sanitizing the input to it keeps a pasted code valid.
 */
export const JOIN_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const ALLOWED = new Set(JOIN_CODE_ALPHABET);

/**
 * Normalize raw code-field input: uppercase, drop anything outside the
 * alphabet (whitespace/punctuation a pilot pastes from chat), cap at the code
 * length. Bound to the field's `(input)` so what the pilot sees is exactly what
 * submits.
 */
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

/** Characters left in the note budget, floored at zero for the counter. */
export function noteRemaining(note: string): number {
  return Math.max(0, JOIN_NOTE_MAX - note.length);
}

/** A live rate-limit window: when the 429 arrived and how long it lasts. */
export interface RateLimitWindow {
  readonly startedAtMs: number;
  readonly retryAfterSeconds: number;
}

/**
 * Whole seconds left before submit re-enables, given the window and the current
 * wall clock. Floored at zero — once elapsed the countdown clears and submit
 * is allowed again.
 */
export function countdownRemaining(window: RateLimitWindow, nowMs: number): number {
  const elapsed = Math.floor((nowMs - window.startedAtMs) / 1000);
  return Math.max(0, window.retryAfterSeconds - elapsed);
}

/** What the form should render for the current submit error + countdown. */
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

/**
 * Map the store's structured submit error (+ a live countdown value, if any)
 * to the form's render state: 404 unknown-code and 409 already-member render
 * inline; a 429 rate-limit shows a countdown and disables submit until it
 * reaches zero, at which point the form returns to a clean submittable state.
 */
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
